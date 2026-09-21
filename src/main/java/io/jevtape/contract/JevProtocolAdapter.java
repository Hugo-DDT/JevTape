package io.jevtape.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.jevtape.shared.InvalidJevRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * JevTape 对 System One HTTP 协议的全部理解都集中在这一个类里（charter §69）：server / cli / cassette /
 * matching 都不许自己解析 Jev 字段，因此上游协议变化时只有这里需要改。
 *
 * <p>请求解析失败是显式的 {@link InvalidJevRequest}，不是裸 {@code RuntimeException}；响应则永远解析得动，
 * 因为错误状态码同样属于 tape（charter §54）。这里也是唯一把 JSON 字段翻译成 {@link DecisionContract}
 * 的地方，于是 verify 的比较逻辑不必认识 System One 的字段名。
 */
public final class JevProtocolAdapter {

    /**
     * 一次 System One 请求里参与 fingerprint 与存储的语义字段。
     *
     * <p>{@code requestedModel} 是调用方请求的别名（例如 {@code jev-latest}），缺失时为 null；
     * {@code state} 与 {@code questions} 缺失时是 JSON null 而不是 Java null，这样它们能原样进 cassette。
     */
    public record Decision(String requestedModel, JsonNode state, JsonNode questions) {
    }

    /**
     * 一次 System One 响应：完整的结构化 body，以及上游实际解析到的模型。
     *
     * <p>{@code resolvedModel} 在无法确定时为 null —— 4xx/5xx 的响应体里通常没有它（charter §34）。
     */
    public record Response(JsonNode body, String resolvedModel) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JevProtocolAdapter() {
    }

    /**
     * 从**原始**请求 body 中提取语义字段。必须在任何脱敏之前调用：脱敏后的内容不能当 fingerprint 的源
     * （charter §52）。
     *
     * @throws InvalidJevRequest 当 body 不是 JSON object —— 那不是 JevTape 支持的 System One 请求
     */
    public static Decision parseRequest(byte[] body) {
        JsonNode root = tryParse(body);
        if (root == null || !root.isObject()) {
            throw new InvalidJevRequest("A System One request must carry a JSON object body"
                    + (root == null ? ", but this body is not JSON at all" : ", got: " + root.getNodeType()));
        }
        return new Decision(text(root.get("model")), field(root, "state"), field(root, "questions"));
    }

    /**
     * 把响应 body 保留为结构化 JSON。上游若回了非 JSON（空 body、中间设备的 HTML 错误页……），就退化成
     * 一个文本节点：内容不丢，cassette 仍是合法 JSON。
     */
    public static Response parseResponse(byte[] body) {
        JsonNode root = tryParse(body);
        if (root == null) {
            JsonNode fallback = body == null || body.length == 0
                    ? NullNode.getInstance()
                    : TextNode.valueOf(new String(body, StandardCharsets.UTF_8));
            return new Response(fallback, null);
        }
        return new Response(root, text(root.get("model")));
    }

    /**
     * 按请求里的原有顺序列出每个 question（charter §17, §56）。渲染成什么样子由调用方决定：REC 块打
     * {@code <type> <name>}，inspect 打对齐的两列。{@code questions} 不是 object 时返回空列表 —— 渲染一份
     * 没有 question 的磁带不该失败。
     */
    public static List<DecisionContract.Question> questions(JsonNode questions) {
        return questions == null || !questions.isObject() ? List.of() : parseQuestions(questions);
    }

    /**
     * 把 {@code questions} 解析成 Decision Contract（charter §18, §44），verify 用它逐项比较。record / replay
     * 不走这里：指纹只认 canonical JSON，不需要领域模型。
     *
     * @throws InvalidJevRequest 当 {@code questions} 不是一个 object —— 那就无从谈起契约
     */
    public static DecisionContract decisionContract(JsonNode questions) {
        if (questions == null || !questions.isObject()) {
            throw new InvalidJevRequest("A Decision Contract needs a 'questions' object"
                    + (questions == null ? ", but there is none" : ", got: " + questions.getNodeType()));
        }
        return new DecisionContract(parseQuestions(questions));
    }

    private static List<DecisionContract.Question> parseQuestions(JsonNode questions) {
        List<DecisionContract.Question> result = new ArrayList<>();
        questions.properties().forEach(entry -> result.add(question(entry.getKey(), entry.getValue())));
        return result;
    }

    /**
     * 三种 question 归一成同一种形状（charter §28 列的正是这些内容）：Choice 读 {@code options[].value} 与
     * 其 {@code criteria}，Score 读 {@code levels[].score} 与其 {@code criteria}，Noul 只有一条
     * {@code criteria}（因此标签是空串）。认不出来的类型没有可比的结构，只留下名字与类型 —— 它若真的变了，
     * contract fingerprint 会说话。
     */
    private static DecisionContract.Question question(String name, JsonNode node) {
        JsonNode type = node.get("type");
        String questionType = type != null && type.isTextual() ? type.asText() : "Question";
        List<DecisionContract.Criterion> criteria = switch (questionType) {
            case "Choice" -> parseCriteria(node.get("options"), "value");
            case "Score" -> parseCriteria(node.get("levels"), "score");
            case "Noul" -> node.path("criteria").isValueNode()
                    ? List.of(new DecisionContract.Criterion("", text(node.get("criteria"))))
                    : List.of();
            default -> List.of();
        };
        return new DecisionContract.Question(name, questionType, criteriaNoun(questionType),
                text(node.get("instructions")), criteria);
    }

    private static List<DecisionContract.Criterion> parseCriteria(JsonNode array, String labelField) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<DecisionContract.Criterion> result = new ArrayList<>();
        for (JsonNode item : array) {
            result.add(new DecisionContract.Criterion(label(item.get(labelField)), text(item.get("criteria"))));
        }
        return result;
    }

    /** 可选项在诊断里叫什么，是 Jev 的知识，因此在这里定下来而不是留给渲染的一方去猜。 */
    private static String criteriaNoun(String questionType) {
        return switch (questionType) {
            case "Choice" -> "option";
            case "Score" -> "level";
            default -> "criteria";
        };
    }

    /** 标签既可能是字符串（Choice 的 value）也可能是数字（Score 的 score）；缺失时是空串。 */
    private static String label(JsonNode node) {
        return node == null || node.isNull() || !node.isValueNode() ? "" : node.asText();
    }

    /** 应答里的作答子树（{@code answers}）。没有时是 JSON null —— 4xx/5xx 的应答通常没有。 */
    public static JsonNode answers(JsonNode body) {
        return field(body, "answers");
    }

    /** 应答里的 token 用量子树（{@code usage}）。没有时是 JSON null。 */
    public static JsonNode tokens(JsonNode body) {
        return field(body, "usage");
    }

    /** 解析不出来时返回 null，由调用方决定是报错还是退化。 */
    private static JsonNode tryParse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            return node == null || node.isMissingNode() ? null : node;
        } catch (IOException e) {
            return null;
        }
    }

    private static JsonNode field(JsonNode root, String name) {
        JsonNode node = root.get(name);
        return node == null ? NullNode.getInstance() : node;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
