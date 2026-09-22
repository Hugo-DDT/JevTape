package io.jevtape.transport;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.CassetteMetadata;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.cassette.RecordedRequest;
import io.jevtape.contract.JevProtocolAdapter;
import io.jevtape.fingerprint.FingerprintEngine;
import io.jevtape.shared.InvalidJevRequest;
import io.jevtape.shared.UpstreamUnavailable;
import io.jevtape.testing.FakeJevServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * record 闭环：应用经过 JevTape 访问 Jev 行为不变，同时落盘一个脱敏后的 cassette。
 */
class RecordingJevTransportTest {

    private static final String API_KEY = "sk-live-9f2c1d4b7a6e5840";

    private static final String REQUEST_BODY = """
            {"model":"jev-latest","state":{"ticket":{"id":"SUP-4821","subject":"Cannot export invoice PDF"}},\
            "questions":{"route":{"type":"Choice","instructions":"Choose the team.",\
            "options":[{"value":"billing","criteria":"Invoices."},{"value":"technical","criteria":"Bugs."}]}}}""";

    private static final String RESPONSE_BODY =
            "{\"model\":\"jev-1.13.0\",\"answers\":{\"route\":{\"choice\":\"billing\",\"confidence\":0.74}}}";

    /**
     * F04 用的是一对**已知**碰撞输入，不在 CI 里搜索：两个 state 的 request 指纹完整值不同，前 8 位十六进制
     * 却都是 {@code 405767e3}，而 v0.5.0 的自动命名只用这 8 位。审查时的探针遍历了 50 万个整数才找到它，
     * 这里把结果固定下来。
     */
    private static final String COLLIDING_STATE_A =
            "{\"model\":\"jev-latest\",\"state\":{\"value\":5298},\"questions\":{}}";

    private static final String COLLIDING_STATE_B =
            "{\"model\":\"jev-latest\",\"state\":{\"value\":80011},\"questions\":{}}";

    private static final String COLLIDING_SHORT_HASH = "405767e3";

    /** F06 用的合成凭证：形状像真的，但哪里都无效。 */
    private static final String QUERY_SECRET = "sk-live-DUMMY-9f2c1d4b";

    private static final String QUERY_SECRET_PERCENT_ENCODED = "sk%2Dlive%2DDUMMY%2D9f2c1d4b";

    @TempDir
    Path cassetteDir;

    private final List<Cassette> recorded = new CopyOnWriteArrayList<>();
    private FakeJevServer upstream;

    @BeforeEach
    void startUpstream() {
        upstream = new FakeJevServer();
    }

    @AfterEach
    void stopUpstream() {
        upstream.close();
    }

    @Test
    void returnsTheUpstreamResponseByteForByteAndReportsWhatItStored() throws IOException {
        upstream.stub(200, Map.of("Content-Type", "application/json"), bytes(RESPONSE_BODY));

        JevResponse response = recording().send(request(Map.of("Content-Type", "application/json")));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(bytes(RESPONSE_BODY));
        assertThat(recorded).hasSize(1);
        assertThat(new FileCassetteRepository(cassetteDir).read(recorded.get(0).name())).isEqualTo(recorded.get(0));
        assertThat(cassetteFiles()).hasSize(1);
    }

    @Test
    void forwardsCredentialsUpstreamButKeepsThemOutOfTheCassette() throws IOException {
        upstream.stub(200, Map.of("Content-Type", "application/json",
                "Set-Cookie", "session=abc123; Path=/", "Retry-After", "30"), bytes(RESPONSE_BODY));

        recording().send(request(Map.of(
                "Authorization", "Bearer " + API_KEY,
                "Cookie", "session=abc123",
                "X-Api-Key", API_KEY)));

        // 转发这一侧行为不变：上游拿到的凭证一字不改。
        FakeJevServer.Received received = upstream.received().get(0);
        assertThat(received.header("Authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(received.header("Cookie")).isEqualTo("session=abc123");
        assertThat(received.header("X-Api-Key")).isEqualTo(API_KEY);

        // 存储这一侧：连 header 名字都不留。
        assertThat(headerNames(recorded.get(0)))
                .containsExactlyInAnyOrder("content-type", "retry-after");
        assertThat(Files.readString(cassetteFiles().get(0), StandardCharsets.UTF_8))
                .doesNotContainIgnoringCase(API_KEY, "bearer", "authorization", "cookie",
                        "x-api-key", "session=abc123", "set-cookie");
    }

    /**
     * F06：header 侧的凭证有 {@link #forwardsCredentialsUpstreamButKeepsThemOutOfTheCassette} 守着，URL 侧
     * 却没有 —— {@code request.path} 里的 query 原样落盘。v1 已经明确 query 不参与指纹，因此最小安全实现是
     * 把整个 query 从存储的 path 里去掉：不必认识每一种凭证参数名，也就没有"漏掉一个名字"的天花板。
     *
     * <p>五种写法都要拦住：普通参数名、大写参数名、混在别的参数中间、重复出现，以及百分号编码过的值。
     */
    @ParameterizedTest
    @Disabled("S06：query 里的凭证目前原样落盘到 request.path")
    @ValueSource(strings = {
            "/v1/systemone?api_key=" + QUERY_SECRET,
            "/v1/systemone?API_KEY=" + QUERY_SECRET,
            "/v1/systemone?trace=1&api_key=" + QUERY_SECRET,
            "/v1/systemone?api_key=" + QUERY_SECRET + "&api_key=" + QUERY_SECRET,
            "/v1/systemone?api_key=" + QUERY_SECRET_PERCENT_ENCODED})
    void aCredentialInTheQueryNeverReachesTheCassette(String path) throws IOException {
        upstream.stub(200, Map.of("Content-Type", "application/json"), bytes(RESPONSE_BODY));

        recording().send(new JevRequest("POST", path, Map.of(), bytes(REQUEST_BODY)));

        // 转发这一侧行为不变：上游拿到的仍是带原始 query 的目标。
        assertThat(upstream.received().get(0).path()).isEqualTo(path);
        // 存储这一侧：整个 query 都不留，于是任何编码形式的凭证都不可能落盘。
        assertThat(recorded.get(0).request().path()).isEqualTo("/v1/systemone");
        assertThat(Files.readString(cassetteFiles().get(0), StandardCharsets.UTF_8))
                .doesNotContain(QUERY_SECRET, QUERY_SECRET_PERCENT_ENCODED, "api_key", "API_KEY", "?");
    }

    @Test
    void fingerprintsComeFromTheOriginalRequestNotFromTheRedactedCopy() {
        upstream.stub(200, Map.of("Content-Type", "application/json"), bytes(RESPONSE_BODY));

        recording().send(request(Map.of("Authorization", "Bearer user-a-key")));
        var userA = recorded.get(0).fingerprints();
        recorded.clear();
        recording().send(request(Map.of("Authorization", "Bearer user-b-key")));
        var userB = recorded.get(0).fingerprints();

        // 凭证不是请求语义：换了 API Key 仍是同一个决策，指纹必须一致。
        assertThat(userA).isEqualTo(userB);
        // 而指纹的源确实是那份**原始**请求 —— 独立算一遍得到同样的结果。
        assertThat(userA).isEqualTo(FingerprintEngine.of("POST", "/v1/systemone?trace=1",
                JevProtocolAdapter.parseRequest(bytes(REQUEST_BODY))));
        // 语义变了，指纹就得变。
        assertThat(FingerprintEngine.of("POST", "/v1/systemone?trace=1",
                JevProtocolAdapter.parseRequest(bytes(REQUEST_BODY.replace("SUP-4821", "SUP-9999")))).request())
                .isNotEqualTo(userA.request());
    }

    @Test
    void namesCassettesByContentSoReRecordingIsIdempotent() throws IOException {
        upstream.stub(200, Map.of("Content-Type", "application/json"), bytes(RESPONSE_BODY));

        recording().send(request(Map.of()));
        recording().send(request(Map.of()));

        assertThat(recorded).hasSize(2);
        assertThat(recorded.get(0).name())
                .isEqualTo(recorded.get(1).name())
                .matches("systemone-[0-9a-f]{8}");
        assertThat(cassetteFiles()).hasSize(1);
    }

    @Test
    void aDifferentDecisionGetsItsOwnCassette() throws IOException {
        upstream.stub(200, Map.of("Content-Type", "application/json"), bytes(RESPONSE_BODY));

        recording().send(request(Map.of()));
        recording().send(new JevRequest("POST", "/v1/systemone", Map.of(),
                bytes(REQUEST_BODY.replace("SUP-4821", "SUP-9999"))));

        assertThat(recorded.get(0).name()).isNotEqualTo(recorded.get(1).name());
        assertThat(cassetteFiles()).hasSize(2);
    }

    /**
     * 这条**现在就跑**：它把 F04 那对碰撞输入固定成事实 —— 完整指纹不同、前 8 位相同。于是 S05 的用例不必
     * 在 CI 里搜碰撞，而且万一哪天有人改了这两份 state，这里会先说清楚"你选的例子已经不撞了"。
     */
    @Test
    void theKnownCollidingInputsShareOnlyTheFirstEightHexDigits() {
        String a = requestFingerprintOf(COLLIDING_STATE_A);
        String b = requestFingerprintOf(COLLIDING_STATE_B);

        assertThat(a).isNotEqualTo(b);
        assertThat(a).startsWith("sha256:" + COLLIDING_SHORT_HASH);
        assertThat(b).startsWith("sha256:" + COLLIDING_SHORT_HASH);
    }

    /**
     * F04：自动命名只取 request 指纹的前 8 位，因此这两个不同的决策会被算成同一个名字
     * {@code systemone-405767e3}，第二次录制直接覆盖第一次 —— 前一份应答从此丢失，而 replay 会拿它去回答
     * 另一个请求（或者 MISS，取决于谁最后写）。
     */
    @Test
    @Disabled("S05：短哈希同名，第二次录制直接覆盖第一次")
    void bothRequestsWithTheSameShortHashStayReplayable() throws IOException {
        record(COLLIDING_STATE_A, "{\"answer\":\"A\"}");
        record(COLLIDING_STATE_B, "{\"answer\":\"B\"}");

        assertThat(cassetteFiles()).hasSize(2);

        ReplayJevTransport replay = new ReplayJevTransport(
                new FileCassetteRepository(cassetteDir).loadAll(), result -> {
                });
        assertThat(replay.send(systemOne(COLLIDING_STATE_A)).body()).isEqualTo(bytes("{\"answer\":\"A\"}"));
        assertThat(replay.send(systemOne(COLLIDING_STATE_B)).body()).isEqualTo(bytes("{\"answer\":\"B\"}"));
    }

    @Test
    void keepsRequestedAndResolvedModelApart() {
        upstream.stub(200, Map.of("Content-Type", "application/json"), bytes(RESPONSE_BODY));

        recording().send(request(Map.of()));

        RecordedRequest stored = recorded.get(0).request();
        assertThat(stored.method()).isEqualTo("POST");
        assertThat(stored.path()).isEqualTo("/v1/systemone?trace=1");
        assertThat(stored.requestedModel()).isEqualTo("jev-latest");
        assertThat(stored.resolvedModel()).isEqualTo("jev-1.13.0");
        assertThat(stored.state().path("ticket").path("id").asText()).isEqualTo("SUP-4821");
        assertThat(stored.questions().path("route").path("type").asText()).isEqualTo("Choice");
    }

    @Test
    void recordsErrorStatusesWithTheirHeadersInsteadOfFailing() {
        upstream.stub(429, Map.of("Content-Type", "application/json", "Retry-After", "30"),
                bytes("{\"error\":{\"type\":\"rate_limit_exceeded\"}}"));

        JevResponse response = recording().send(request(Map.of()));

        assertThat(response.status()).isEqualTo(429);
        Cassette cassette = recorded.get(0);
        assertThat(cassette.response().status()).isEqualTo(429);
        assertThat(cassette.response().body().path("error").path("type").asText())
                .isEqualTo("rate_limit_exceeded");
        assertThat(cassette.request().resolvedModel()).isNull();
        assertThat(headerNames(cassette)).contains("retry-after");
    }

    @Test
    void keepsANonJsonResponseBodyAsText() {
        upstream.stub(502, Map.of("Content-Type", "text/html"), bytes("<html>bad gateway</html>"));

        JevResponse response = recording().send(request(Map.of()));

        assertThat(response.body()).isEqualTo(bytes("<html>bad gateway</html>"));
        assertThat(recorded.get(0).response().body().asText()).isEqualTo("<html>bad gateway</html>");
    }

    @Test
    void stampsWhenHowLongAndWithWhichVersion() {
        upstream.stub(200, Map.of("Content-Type", "application/json"), bytes(RESPONSE_BODY));

        recording().send(request(Map.of()));

        Cassette cassette = recorded.get(0);
        CassetteMetadata metadata = cassette.metadata();
        assertThat(cassette.schemaVersion()).isEqualTo(1);
        assertThat(metadata.recordedAt()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(metadata.jevtapeVersion()).isEqualTo("0.1.0-test");
        assertThat(metadata.durationMs()).isNotNegative();
    }

    @Test
    void rejectsANonJevRequestBeforeItEverReachesUpstream() throws IOException {
        assertThatThrownBy(() -> recording().send(new JevRequest("POST", "/v1/systemone", Map.of(),
                bytes("<html>definitely not jev</html>"))))
                .isInstanceOf(InvalidJevRequest.class)
                .hasMessageContaining("JSON object");

        assertThat(upstream.received()).isEmpty();
        assertThat(recorded).isEmpty();
        assertThat(cassetteFiles()).isEmpty();
    }

    @Test
    void writesNothingWhenTheUpstreamCallFails() throws IOException {
        URI dead = URI.create("http://127.0.0.1:" + freePort());

        assertThatThrownBy(() -> recording(dead).send(request(Map.of())))
                .isInstanceOf(UpstreamUnavailable.class);

        assertThat(recorded).isEmpty();
        assertThat(cassetteFiles()).isEmpty();
    }

    /** 录一份指定请求 + 指定应答，走的是真实的 record 路径。 */
    private void record(String requestBody, String responseBody) {
        upstream.stub(200, Map.of("Content-Type", "application/json"), bytes(responseBody));
        recording().send(systemOne(requestBody));
    }

    private static JevRequest systemOne(String body) {
        return new JevRequest("POST", "/v1/systemone", Map.of(), bytes(body));
    }

    private static String requestFingerprintOf(String body) {
        return FingerprintEngine.of("POST", "/v1/systemone", JevProtocolAdapter.parseRequest(bytes(body)))
                .request();
    }

    private RecordingJevTransport recording() {
        return recording(upstream.baseUrl());
    }

    private RecordingJevTransport recording(URI baseUrl) {
        return new RecordingJevTransport(new LiveJevTransport(baseUrl, Duration.ofSeconds(10)),
                new FileCassetteRepository(cassetteDir), "0.1.0-test", recorded::add);
    }

    private static JevRequest request(Map<String, String> headers) {
        return new JevRequest("POST", "/v1/systemone?trace=1",
                headers.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.of(e.getValue()))),
                bytes(REQUEST_BODY));
    }

    private static List<String> headerNames(Cassette cassette) {
        return cassette.response().headers().keySet().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
    }

    private List<Path> cassetteFiles() throws IOException {
        try (var files = Files.list(cassetteDir)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
