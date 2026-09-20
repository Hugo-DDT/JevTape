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
 * <p>第一阶段只做字段提取，不建立 Choice / Score / Noul 的领域模型 —— Decision Contract 是后续版本的事。
 * 请求解析失败是显式的 {@link InvalidJevRequest}，不是裸 {@code RuntimeException}；响应则永远解析得动，
 * 因为错误状态码同样属于 tape（charter §54）。
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
     * 每个 question 一行 {@code <type> <name>}，保持请求里的原有顺序，供 CLI 渲染 REC 输出
     * （charter §56）。{@code questions} 不是 object 时返回空列表。
     */
    public static List<String> questionSummaries(JsonNode questions) {
        if (questions == null || !questions.isObject()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        questions.properties().forEach(entry -> {
            JsonNode type = entry.getValue().get("type");
            lines.add((type != null && type.isTextual() ? type.asText() : "Question") + " " + entry.getKey());
        });
        return lines;
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
