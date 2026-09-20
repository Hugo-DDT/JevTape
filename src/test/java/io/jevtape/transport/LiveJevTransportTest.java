package io.jevtape.transport;

import io.jevtape.shared.UpstreamTimeout;
import io.jevtape.shared.UpstreamUnavailable;
import io.jevtape.testing.FakeJevServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveJevTransportTest {

    private static final byte[] REQUEST_BODY =
            "{\"state\":{\"ticket\":\"工单 42\"},\"questions\":[\"Choice\",\"Score\"]}".getBytes(StandardCharsets.UTF_8);

    private FakeJevServer server;

    @BeforeEach
    void startServer() {
        server = new FakeJevServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void forwardsRequestAndReturnsUpstreamResponseByteForByte() {
        String upstreamBody = "{\"choice\":\"路由到 A 组\",\"confidence\":0.93,\"answers\":[{\"id\":1}]}";
        server.stub(200, Map.of("Content-Type", "application/json", "X-Jev-Model", "jev-1.13.0"),
                upstreamBody.getBytes(StandardCharsets.UTF_8));

        JevResponse response = transport(Duration.ofSeconds(10)).send(request(Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer sk-live-secret",
                "X-Trace", "abc")));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(upstreamBody.getBytes(StandardCharsets.UTF_8));
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(upstreamBody);
        assertThat(header(response, "Content-Type")).isEqualTo("application/json");
        assertThat(header(response, "X-Jev-Model")).isEqualTo("jev-1.13.0");

        FakeJevServer.Received received = server.received().get(0);
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.path()).isEqualTo("/v1/systemone?trace=1");
        assertThat(received.body()).isEqualTo(REQUEST_BODY);
        assertThat(received.header("X-Trace")).isEqualTo("abc");
    }

    @Test
    void forwardsAuthorizationUnchangedBecauseRedactionIsStorageOnly() {
        server.stub(200, "{}");

        transport(Duration.ofSeconds(10)).send(request(Map.of("Authorization", "Bearer sk-live-secret")));

        assertThat(server.received().get(0).header("Authorization")).isEqualTo("Bearer sk-live-secret");
    }

    @Test
    void returnsErrorStatusesAsResponsesInsteadOfThrowing() {
        for (int status : List.of(400, 401, 429, 500, 529)) {
            server.stub(status, Map.of("Retry-After", "30"), "{\"error\":\"upstream\"}".getBytes(StandardCharsets.UTF_8));

            JevResponse response = transport(Duration.ofSeconds(10)).send(request(Map.of()));

            assertThat(response.status()).isEqualTo(status);
            assertThat(response.body()).isEqualTo("{\"error\":\"upstream\"}".getBytes(StandardCharsets.UTF_8));
            assertThat(header(response, "Retry-After")).isEqualTo("30");
        }
    }

    @Test
    void dropsHopByHopAndClientRestrictedHeaders() {
        server.stub(200, "{}");

        transport(Duration.ofSeconds(10)).send(request(Map.of(
                "Connection", "close",
                "Keep-Alive", "timeout=5",
                "Transfer-Encoding", "chunked",
                "Host", "evil.example",
                "Content-Length", "999",
                "X-Trace", "abc")));

        FakeJevServer.Received received = server.received().get(0);
        assertThat(received.header("X-Trace")).isEqualTo("abc");
        assertThat(received.header("Keep-Alive")).isNull();
        assertThat(received.header("Connection")).isNotEqualTo("close");
        assertThat(received.header("Host")).isNotEqualTo("evil.example");
        assertThat(received.body()).isEqualTo(REQUEST_BODY);
    }

    @Test
    void resolvesRequestPathAgainstConfiguredBaseUrl() {
        server.stub(200, "{}");
        int port = server.baseUrl().getPort();

        new LiveJevTransport(URI.create("http://127.0.0.1:" + port + "/api/"), Duration.ofSeconds(10))
                .send(request(Map.of()));

        assertThat(server.received().get(0).path()).isEqualTo("/api/v1/systemone?trace=1");
    }

    @Test
    void mapsUnresponsiveUpstreamToUpstreamTimeout() {
        server.delay(Duration.ofSeconds(2));

        assertThatThrownBy(() -> transport(Duration.ofMillis(150)).send(request(Map.of())))
                .isInstanceOf(UpstreamTimeout.class)
                .hasMessageContaining("150 ms")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void mapsUnreachableUpstreamToUpstreamUnavailable() throws IOException {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> transport(Duration.ofSeconds(5), URI.create("http://127.0.0.1:" + deadPort))
                .send(request(Map.of())))
                .isInstanceOf(UpstreamUnavailable.class)
                .hasMessageContaining("could not be reached")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void defaultsToTheDocumentedUpstream() {
        assertThat(LiveJevTransport.DEFAULT_BASE_URL).isEqualTo(URI.create("https://api.typesafe.ai"));
    }

    private LiveJevTransport transport(Duration timeout) {
        return transport(timeout, server.baseUrl());
    }

    private LiveJevTransport transport(Duration timeout, URI baseUrl) {
        return new LiveJevTransport(baseUrl, timeout);
    }

    private JevRequest request(Map<String, String> headers) {
        return new JevRequest("POST", "/v1/systemone?trace=1", singleValued(headers), REQUEST_BODY);
    }

    private static Map<String, List<String>> singleValued(Map<String, String> headers) {
        return headers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.of(e.getValue())));
    }

    private static String header(JevResponse response, String name) {
        return response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> entry.getValue().get(0))
                .findFirst()
                .orElse(null);
    }
}
