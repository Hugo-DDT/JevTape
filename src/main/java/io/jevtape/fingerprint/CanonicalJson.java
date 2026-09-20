package io.jevtape.fingerprint;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical JSON，V1 的固定规则（charter §30）。fingerprint 永远对这个文本计算，而不是对上游给的原始
 * 字节 —— 于是 {@code {"a":1,"b":2}} 与 {@code {"b":2,"a":1}} 得到相同的 hash，而数组换个顺序就不同。
 *
 * <ul>
 *   <li>object key 按 Unicode 码点升序（即 {@link String#compareTo}）；</li>
 *   <li>array **保持原顺序**，顺序敏感；</li>
 *   <li>number 用稳定表达：整数一律按 {@code BigInteger} 写、小数一律按 {@code BigDecimal} 写，
 *       因此 {@code 1} 不会因为被解析成 int 还是 long 而改变文本；</li>
 *   <li>string 保留 Unicode 原值，**不做** trim、lowercase 或任何语义归一化 —— Jev 对文字变化可能敏感，
 *       JevTape 不猜"这两个请求语义差不多"。</li>
 * </ul>
 */
public final class CanonicalJson {

    private static final JsonFactory FACTORY = new JsonFactory();

    private CanonicalJson() {
    }

    /** {@code null} 与 JSON null 同样对待，输出 {@code null}。 */
    public static String of(JsonNode node) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator generator = FACTORY.createGenerator(out, JsonEncoding.UTF8)) {
            write(node == null ? NullNode.getInstance() : node, generator);
        } catch (IOException e) {
            // 目标是内存流，正常情况下不可能失败。
            throw new UncheckedIOException("Cannot write canonical JSON", e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void write(JsonNode node, JsonGenerator generator) throws IOException {
        switch (node.getNodeType()) {
            case OBJECT -> {
                generator.writeStartObject();
                List<String> names = new ArrayList<>();
                node.fieldNames().forEachRemaining(names::add);
                names.sort(null);
                for (String name : names) {
                    generator.writeFieldName(name);
                    write(node.get(name), generator);
                }
                generator.writeEndObject();
            }
            case ARRAY -> {
                generator.writeStartArray();
                for (JsonNode item : node) {
                    write(item, generator);
                }
                generator.writeEndArray();
            }
            case STRING -> generator.writeString(node.textValue());
            case NUMBER -> {
                if (node.isIntegralNumber()) {
                    generator.writeNumber(node.bigIntegerValue());
                } else {
                    generator.writeNumber(node.decimalValue());
                }
            }
            case BOOLEAN -> generator.writeBoolean(node.booleanValue());
            case NULL -> generator.writeNull();
            default -> throw new IllegalArgumentException("Cannot canonicalize a " + node.getNodeType() + " node");
        }
    }
}
