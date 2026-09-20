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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
