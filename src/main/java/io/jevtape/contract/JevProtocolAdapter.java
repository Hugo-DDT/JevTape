package io.jevtape.contract;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
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
import java.util.Locale;
import java.util.Map;

/**
 * JevTape 对 System One HTTP 协议的全部理解都集中在这一个类里（charter §69）：server / cli / cassette /
 * matching 都不许自己解析 Jev 字段，因此上游协议变化时只有这里需要改。
 *
 * <p>请求解析失败是显式的 {@link InvalidJevRequest}，不是裸 {@code RuntimeException}；响应则永远解析得动，
 * 因为错误状态码同样属于 tape（charter §54）。这里也是唯一把 JSON 字段翻译成 {@link DecisionContract} 与
 * {@link DecisionAnswers} 的地方，于是 verify / diff 的比较逻辑不必认识 System One 的字段名；simulate 往
 * 应答里写覆盖也走这里，因此"字段叫什么"这件事在整个工程里只有一份。
 *
 * <p>协议形状以当前官方规范为准（{@code fixtures/jev-protocol}）：question type 是小写的
 * {@code choice} / {@code score} / {@code noul}，三类的可选项都叫 {@code criteria}，只是形状各不相同。
 * 已发布 v1 磁带里的大写 type 与 {@code options[]} / {@code levels[]} 仍能读 —— 旧磁带不该因为上游改了
 * 文档就失效（charter §63）—— 但那只是历史兼容分支，不是主路径。
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

    /**
     * 请求侧的解析比响应侧严格：重复键（后者静默覆盖前者）与尾随内容（{@code readTree} 只读第一个值）都会让
     * "JevTape 理解的 body"与"上游收到的 body"不是同一份 —— 指纹于是描述了一个上游从来没见过的请求。
     * JevTape 不重新实现官方的请求校验器，但这类有歧义的输入必须在转发之前就拒掉。
     */
    private static final JsonFactory STRICT_JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private JevProtocolAdapter() {
    }

    /**
     * 从**原始**请求 body 中提取语义字段。必须在任何脱敏之前调用：脱敏后的内容不能当 fingerprint 的源
     * （charter §52）。
     *
     * @throws InvalidJevRequest 当 body 不是一个 JSON object，或者不是一份无歧义的 JSON —— 那不是 JevTape
     *                           支持的 System One 请求
     */
    public static Decision parseRequest(byte[] body) {
        JsonNode root = tryParseRequest(body);
        if (root == null) {
            // 严格解析不给原因（重复键的名字与尾随内容都可能带着用户 state），只在错误路径上多说一句是哪种。
            throw new InvalidJevRequest("A System One request must carry a JSON object body, but this body "
                    + (tryParse(body) == null ? "is not JSON at all"
                            : "is ambiguous JSON: it repeats a key or carries trailing content"));
        }
        if (!root.isObject()) {
            throw new InvalidJevRequest("A System One request must carry a JSON object body, got: "
                    + root.getNodeType());
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
     * 三类 question 归一成同一种形状（charter §28 列的正是这些内容）："一个标签 + 一段 criteria 文本"，
     * 于是一次比较只需要一条路径而不是三条。
     *
     * <p>官方协议里三类的可选项都叫 {@code criteria}，只是形状不同：Choice 是 map（键就是选项值），Score 是
     * **有序** array（下标就是等级，官方应答的 {@code legend} 用的正是这个下标），Noul 是带 {@code true} /
     * {@code false} 两个键的 object（两个键各是一条**有标签**的规则）。v1 磁带的大写 type 走
     * {@code options[]} / {@code levels[]} / 单条无标签 criteria，只作为历史兼容分支保留。
     *
     * <p>认不出来的类型没有可比的结构，只留下名字与类型 —— 它若真的变了，contract fingerprint 会说话。
     * type 的原文不被改写（不做大小写归一化）：原始 questions 是指纹的源，显示层要统一叫法也不该在这里动手。
     */
    private static DecisionContract.Question question(String name, JsonNode node) {
        JsonNode type = node.get("type");
        String questionType = type != null && type.isTextual() ? type.asText() : "Question";
        return new DecisionContract.Question(name, questionType, criteriaNoun(questionType),
                field(node, "instructions"), criteria(questionType, node));
    }

    /** 按 type 认形状；官方形状看 {@code criteria} 到底是什么，认不出来再退回 v1 的 {@code options[]} 等。 */
    private static List<DecisionContract.Criterion> criteria(String questionType, JsonNode node) {
        JsonNode criteria = node.get("criteria");
        return switch (questionType.toLowerCase(Locale.ROOT)) {
            case "choice" -> criteria != null && criteria.isObject()
                    ? fromMap(criteria)
                    : fromLabelledArray(node.get("options"), "value");
            case "score" -> criteria != null && criteria.isArray()
                    ? fromOrderedArray(criteria)
                    : fromLabelledArray(node.get("levels"), "score");
            case "noul" -> criteria != null && criteria.isObject()
                    ? fromMap(criteria)
                    : fromSingle(criteria);
            default -> List.of();
        };
    }

    /**
     * Choice 的 criteria map 与 Noul 的 true / false object：键就是标签，值就是那一条 rubric。
     *
     * <p>标签按字典序给出，不照抄请求里的键序：object 的 key 序不参与 canonical JSON，因此也不参与指纹，
     * 照抄会让"同一份契约、键序不同"在诊断里多出一行 {@code ~ order}，而判定又是 PASS —— 报告自相矛盾。
     */
    private static List<DecisionContract.Criterion> fromMap(JsonNode criteria) {
        List<String> labels = new ArrayList<>();
        criteria.fieldNames().forEachRemaining(labels::add);
        labels.sort(null);
        return labels.stream()
                .map(label -> new DecisionContract.Criterion(label, criteriaText(criteria.get(label))))
                .toList();
    }

    /** Score 的 criteria array：顺序就是等级，因此标签是下标本身 —— 换个顺序在诊断里是"level 1 变了"。 */
    private static List<DecisionContract.Criterion> fromOrderedArray(JsonNode criteria) {
        List<DecisionContract.Criterion> result = new ArrayList<>();
        for (int level = 0; level < criteria.size(); level++) {
            result.add(new DecisionContract.Criterion(Integer.toString(level), criteriaText(criteria.get(level))));
        }
        return result;
    }

    /** v1 的 Noul：整条 criteria 就是一个字符串，没有标签可打。写成 {@code null} 是"没写"，不凭空造一条。 */
    private static List<DecisionContract.Criterion> fromSingle(JsonNode criteria) {
        return criteria == null || !criteria.isValueNode() || criteria.isNull()
                ? List.of()
                : List.of(new DecisionContract.Criterion("", criteriaText(criteria)));
    }

    /** v1 的 Choice {@code options[]} 与 Score {@code levels[]}：标签藏在每一项的某个字段里。 */
    private static List<DecisionContract.Criterion> fromLabelledArray(JsonNode array, String labelField) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<DecisionContract.Criterion> result = new ArrayList<>();
        for (JsonNode item : array) {
            result.add(new DecisionContract.Criterion(label(item.get(labelField)),
                    criteriaText(item.get("criteria"))));
        }
        return result;
    }

    /** 可选项在诊断里叫什么，是 Jev 的知识，因此在这里定下来而不是留给渲染的一方去猜。 */
    private static String criteriaNoun(String questionType) {
        return switch (questionType.toLowerCase(Locale.ROOT)) {
            case "choice" -> "option";
            case "score" -> "level";
            default -> "criteria";
        };
    }

    /** v1 的标签既可能是字符串（Choice 的 value）也可能是数字（Score 的 score）；缺失时是空串。 */
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
                // 官方 Noul 的作答字段叫 noul，v1 磁带里叫 probability：--probability 两边都得落得下去。
                target(result, node, "probability", overrides.probability());
                target(result, node, "noul", overrides.probability());
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

    /**
     * 一条 criteria 的正文。官方允许它写成 object 或 array（例如 {@code {"summary": "…", "examples": […]}}），
     * 只接受字符串的读法会把整条 rubric 丢成 null，于是两份**不同**的 criteria 在契约里长得一模一样。
     * 没写（缺失或 JSON null）与写了一段空文案仍然是两件事，前者是 null。
     */
    private static String criteriaText(JsonNode node) {
        return node == null || node.isNull() ? null : display(node);
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

    /** 请求专用的严格解析，见 {@link #STRICT_JSON}；有歧义时返回 null，由 parseRequest 变成具名错误。 */
    private static JsonNode tryParseRequest(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try (JsonParser parser = STRICT_JSON.createParser(body)) {
            JsonNode node = MAPPER.readTree(parser);
            // readTree 只读第一个值：后面还有 token 就说明这份 body 不是一次请求，而是两边各看一半的输入。
            return node == null || node.isMissingNode() || parser.nextToken() != null ? null : node;
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
