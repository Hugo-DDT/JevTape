package io.jevtape.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code fixtures/jev-protocol} 的读取口。那里放的是按官方规范手写的 System One 请求 / 响应样例（来源见该
 * 目录的 README），**不是** cassette：每个文件形如
 * {@code {"request": {...}, "response": {"status": 200, "body": {...}}}}，一对 HTTP 消息。
 *
 * <p>本类把这两个消息各自还原成能直接喂给 {@code JevProtocolAdapter} 的字节，于是测试里不必重复
 * readTree / writeValueAsBytes 这一圈。样例是紧凑序列化后交出去的：格式保留语义而非空白，而 fingerprint
 * 只认 canonical JSON，因此这与上游给的原始字节等价。
 */
public final class JevProtocolFixtures {

    private static final Path DIR = Path.of("fixtures", "jev-protocol");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JevProtocolFixtures() {
    }

    /** 全部样例名（不含 {@code .json}），按字典序。 */
    public static List<String> names() {
        try (Stream<Path> files = Files.list(DIR)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list protocol fixtures under " + DIR, e);
        }
    }

    /** 整份样例文档，用来遍历它的 questions / answers。 */
    public static JsonNode root(String name) {
        try {
            return MAPPER.readTree(DIR.resolve(name + ".json").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read protocol fixture " + name, e);
        }
    }

    /** 原始请求 body 的字节。 */
    public static byte[] request(String name) {
        return bytes(root(name).path("request"));
    }

    /** 请求里的 {@code questions} 子树；样例没有它就是 missing node，不是 null。 */
    public static JsonNode questions(String name) {
        return root(name).path("request").path("questions");
    }

    /** 应答的 HTTP 状态码。 */
    public static int status(String name) {
        return root(name).path("response").path("status").asInt();
    }

    /** 原始响应 body 的字节。 */
    public static byte[] responseBody(String name) {
        return bytes(root(name).path("response").path("body"));
    }

    private static byte[] bytes(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot serialize a protocol fixture node", e);
        }
    }
}
