package io.jevtape.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.testing.FakeJevServer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F05：录下来的响应正文必须能原样回去。v0.5.0 在三处做不到，都是同一个根因 —— cassette 的
 * {@code response.body} 只有一个 {@code JsonNode} 槽位，装不下"这段字节原本是什么"：
 *
 * <ul>
 *   <li>gzip 正文 Jackson 解析不动，退化成 TextNode；回放时 {@code Content-Encoding: gzip} 的头还在，
 *       正文却已经不是合法 gzip（S07）；</li>
 *   <li>JSON 标量 {@code "hello"} 存成 TextNode，回放按"非 JSON 原件"的规则去掉引号，客户端拿到非法
 *       JSON（V03）；</li>
 *   <li>JSON 字面量 {@code null} 与零字节空 body 都存成 NullNode，回放时都变成空 —— 两者再也分不开
 *       （V03）。</li>
 * </ul>
 *
 * <p>三条都带 {@code @Disabled} 并注明所属修复任务。其中"空 body 回放成空"今天是对的，由
 * {@code ReplayJevTransportTest#replaysAnEmptyBodyAsEmpty} 守着，这里守的是被它掩盖掉的另一半。
 */
class ResponseFidelityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String REQUEST_BODY =
            "{\"model\":\"jev-latest\",\"state\":{\"value\":1},\"questions\":{}}";

    private static final String JSON_BODY = "{\"answers\":{\"q\":{\"score\":1}}}";

    @TempDir
    Path cassetteDir;

    /**
     * S07 的验收链路：FakeJevServer 回 gzip JSON → 录制侧的客户端拿到原始压缩字节 → 关掉上游 → replay
     * 得到语义等价的 JSON，且不再声称自己是 gzip。
     */
    @Test
    @Disabled("S07：gzip 正文被存成文本，回放时头说是 gzip、正文却解压不了")
    void aGzippedJsonResponseReplaysAsEquivalentJson() throws IOException {
        byte[] compressed = gzip(JSON_BODY);
        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.stub(200, Map.of("Content-Type", "application/json", "Content-Encoding", "gzip"),
                    compressed);

            JevResponse live = recording(upstream).send(request(Map.of("Accept-Encoding", List.of("gzip"))));

            // 转发一侧不变：客户端拿到的仍是上游那份压缩字节。
            assertThat(live.body()).isEqualTo(compressed);
        }

        JevResponse replayed = replaying().send(request(Map.of()));

        // 存储一侧解压后按 JSON 语义回放，因此比的是解析结果，不是空白字符。
        assertThat(MAPPER.readTree(inflate(replayed.body()))).isEqualTo(MAPPER.readTree(JSON_BODY));
        assertThat(headerNames(replayed)).doesNotContain("content-encoding");
    }

    @Test
    @Disabled("V03：JSON 字符串响应回放时丢了引号，客户端拿到非法 JSON")
    void aJsonStringBodyReplaysWithItsQuotes() {
        record(200, Map.of("Content-Type", "application/json"), "\"hello\"");

        assertThat(replaying().send(request(Map.of())).body()).isEqualTo(bytes("\"hello\""));
    }

    @Test
    @Disabled("V03：JSON 字面量 null 与空 body 目前都回放成零字节")
    void aJsonNullBodyIsNotReplayedAsAnEmptyBody() {
        record(200, Map.of("Content-Type", "application/json"), "null");

        assertThat(replaying().send(request(Map.of())).body()).isEqualTo(bytes("null"));
    }

    /** 用真实的 record 路径产出一个 cassette，然后把上游关掉 —— 之后的 replay 无处可去。 */
    private void record(int status, Map<String, String> headers, String responseBody) {
        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.stub(status, headers, bytes(responseBody));
            recording(upstream).send(request(Map.of()));
        }
    }

    private RecordingJevTransport recording(FakeJevServer upstream) {
        return new RecordingJevTransport(new LiveJevTransport(upstream.baseUrl(), Duration.ofSeconds(10)),
                new FileCassetteRepository(cassetteDir), "0.5.0-test", cassette -> {
                });
    }

    /** 每次都从磁盘重新装载，因此 replay 看到的就是 record 真的写下来的那一份。 */
    private ReplayJevTransport replaying() {
        return new ReplayJevTransport(new FileCassetteRepository(cassetteDir).loadAll(), result -> {
        });
    }

    private static JevRequest request(Map<String, List<String>> headers) {
        return new JevRequest("POST", "/v1/systemone", headers, bytes(REQUEST_BODY));
    }

    private static List<String> headerNames(JevResponse response) {
        return response.headers().keySet().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
    }

    private static byte[] gzip(String body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream stream = new GZIPOutputStream(out)) {
            stream.write(bytes(body));
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        try (GZIPInputStream stream = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return stream.readAllBytes();
        }
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
