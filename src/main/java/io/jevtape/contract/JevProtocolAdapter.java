package io.jevtape.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.jevtape.shared.CassetteCorrupted;
import io.jevtape.shared.InvalidJevRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JevTape 对 System One HTTP 协议的全部理解都集中在这一个类里（charter §69）：server / cli / cassette /
 * matching 都不许自己解析 Jev 字段，因此上游协议变化时只有这里需要改。
 *
 * <p>请求解析失败是显式的 {@link InvalidJevRequest}，不是裸 {@code RuntimeException}；响应则永远解析得动，
 * 因为错误状态码同样属于 tape（charter §54）。这里也是唯一把 JSON 字段翻译成 {@link DecisionContract} 与
 * {@link DecisionAnswers} 的地方，于是 verify / diff 的比较逻辑不必认识 System One 的字段名；simulate 往
 * 应答里写覆盖也走这里，因此"字段叫什么"这件事在整个工程里只有一份。
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

    /**
     * 把应答里的 {@code answers} 解析成 {@link DecisionAnswers}（charter §17, §19），diff 用它逐项比较两盘
     * 磁带答了什么。没有作答（4xx/5xx 的应答通常没有）时是 {@link DecisionAnswers#NONE} —— 比一盘录了错误
     * 状态码的磁带不该失败，它只是没有答案可比。
     */
    public static DecisionAnswers decisionAnswers(JsonNode body) {
        JsonNode answers = field(body, "answers");
        if (!answers.isObject()) {
            return DecisionAnswers.NONE;
        }
        List<DecisionAnswers.Answer> result = new ArrayList<>();
        answers.properties().forEach(entry -> result.add(answer(entry.getKey(), entry.getValue())));
        return new DecisionAnswers(result);
    }

    /**
     * 一个 question 的答案：概率分布单独成表（于是 diff 能按选项对齐），其余字段全部留在 {@code scalars}
     * 里。不是 object 的答案（上游只回了一个字符串之类）退化成一项 {@code value}，内容不丢。
     */
    private static DecisionAnswers.Answer answer(String name, JsonNode node) {
        if (!node.isObject()) {
            return new DecisionAnswers.Answer(name, Map.of(), Map.of("value", display(node)));
        }
        Map<String, String> probabilities = new LinkedHashMap<>();
        Map<String, String> scalars = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (entry.getKey().equals("probabilities") && value.isObject()) {
                value.properties().forEach(option -> probabilities.put(option.getKey(), display(option.getValue())));
            } else {
                scalars.put(entry.getKey(), display(value));
            }
        });
        return new DecisionAnswers.Answer(name, probabilities, scalars);
    }

    /**
     * 这些覆盖落不落得到这份应答上：{@code question} 存在（若指定了），且至少有一个待覆盖的字段确实存在。
     * simulate 在启动时问一次，于是"--confidence 写了却什么都没改"是一行错误，而不是半小时的调试。
     */
    public static boolean canOverride(JsonNode body, AnswerOverrides overrides) {
        return !targets(body, overrides).isEmpty();
    }

    /**
     * 把覆盖写进应答 body，返回改写后的字节；没有任何可写的目标时返回原件，于是不做覆盖的 simulate 与
     * replay 给出的是逐字节相同的响应。
     *
     * <p>认不出来的 body（空 body、中间设备的 HTML 错误页）同样原样返回：那里没有作答可覆盖，把它换成一份
     * 编造的 JSON 只会让客户端去解析一个上游从来不会回的东西。
     *
     * @throws CassetteCorrupted 当磁带里的 body 是一棵写不回字节的 JSON 树
     */
    public static byte[] overrideAnswers(byte[] body, AnswerOverrides overrides) {
        if (overrides.empty()) {
            return body;
        }
        JsonNode root = tryParse(body);
        if (root == null || !root.isObject()) {
            return body;
        }
        List<Target> targets = targets(root, overrides);
        if (targets.isEmpty()) {
            return body;
        }
        targets.forEach(target -> target.answer().set(target.field(), target.value()));
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new CassetteCorrupted("Cassette holds a response body that cannot be written back as JSON", e);
        }
    }

    /**
     * 要写的那些 {@code (答案节点, 字段名, 新值)}。只收**已经存在**的字段：给一个 Score 的答案凭空加一个
     * confidence，测出来的就不是应用的边界而是它对假响应的解析。
     */
    private static List<Target> targets(JsonNode body, AnswerOverrides overrides) {
        List<Target> result = new ArrayList<>();
        if (overrides.empty() || body == null) {
            return result;
        }
        JsonNode answers = field(body, "answers");
        if (!answers.isObject()) {
            return result;
        }
        answers.properties().forEach(entry -> {
            if (overrides.question() != null && !overrides.question().equals(entry.getKey())) {
                return;
            }
            if (entry.getValue() instanceof ObjectNode node) {
                target(result, node, "confidence", overrides.confidence());
                target(result, node, "choice", overrides.choice());
                target(result, node, "score", overrides.score());
                target(result, node, "probability", overrides.probability());
            }
        });
        return result;
    }

    private static void target(List<Target> sink, ObjectNode answer, String field, Object value) {
        if (value != null && answer.has(field)) {
            sink.add(new Target(answer, field, value instanceof Double number
                    ? DoubleNode.valueOf(number)
                    : TextNode.valueOf(value.toString())));
        }
    }

    /** 一处待写入的覆盖。 */
    private record Target(ObjectNode answer, String field, JsonNode value) {
    }

    /** 标量取文本，容器取紧凑 JSON —— 于是任何字段都留在比较里，不会因为"不认识"而被丢掉。 */
    private static String display(JsonNode node) {
        return node.isValueNode() ? node.asText() : node.toString();
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
