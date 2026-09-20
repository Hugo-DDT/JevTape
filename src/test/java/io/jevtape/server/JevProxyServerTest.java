package io.jevtape.server;

import io.jevtape.shared.ConfigurationError;
import io.jevtape.shared.JevTapeException;
import io.jevtape.shared.UpstreamTimeout;
import io.jevtape.testing.FakeJevServer;
import io.jevtape.transport.JevRequest;
import io.jevtape.transport.JevResponse;
import io.jevtape.transport.JevTransport;
import io.jevtape.transport.LiveJevTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JevProxyServerTest {

    private static final byte[] REQUEST_BODY =
            "{\"state\":{\"ticket\":\"工单 42\"},\"questions\":[\"Choice\"]}".getBytes(StandardCharsets.UTF_8);
    private static final String UPSTREAM_BODY = "{\"choice\":\"路由到 A 组\",\"confidence\":0.93}";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void forwardsMethodTargetHeadersAndBodyToTheTransport() throws Exception {
        StubTransport transport = StubTransport.responding(ok(UPSTREAM_BODY));

        try (JevProxyServer proxy = new JevProxyServer(transport, "127.0.0.1", 0)) {
            post(proxy.baseUrl().resolve("/v1/systemone?trace=1&tag=a%20b"), REQUEST_BODY,
                    "Content-Type", "application/json",
                    "X-Trace", "abc",
                    "Accept", "application/json",
                    "Accept", "text/plain");
        }

        JevRequest forwarded = transport.received.get(0);
        assertThat(forwarded.method()).isEqualTo("POST");
        assertThat(forwarded.path()).isEqualTo("/v1/systemone?trace=1&tag=a%20b");
        assertThat(forwarded.body()).isEqualTo(REQUEST_BODY);
        assertThat(header(forwarded.headers(), "Content-Type")).isEqualTo("application/json");
        assertThat(header(forwarded.headers(), "X-Trace")).isEqualTo("abc");
        assertThat(forwarded.headers().get("Accept")).containsExactly("application/json", "text/plain");
    }

    @Test
    void passesRequestHeadersVerbatimBecauseFilteringBelongsToTheTransport() throws Exception {
        StubTransport transport = StubTransport.responding(ok(UPSTREAM_BODY));

        try (JevProxyServer proxy = new JevProxyServer(transport, "127.0.0.1", 0)) {
            post(proxy.baseUrl().resolve("/v1/systemone"), REQUEST_BODY,
                    "Authorization", "Bearer sk-live-secret");
        }

        JevRequest forwarded = transport.received.get(0);
        assertThat(header(forwarded.headers(), "Authorization")).isEqualTo("Bearer sk-live-secret");
        assertThat(header(forwarded.headers(), "Host")).startsWith("127.0.0.1:");
        assertThat(header(forwarded.headers(), "Content-Length"))
                .isEqualTo(String.valueOf(REQUEST_BODY.length));
    }

    @Test
    void writesTheTransportResponseBackUntouched() throws Exception {
        byte[] body = UPSTREAM_BODY.getBytes(StandardCharsets.UTF_8);
        StubTransport transport = StubTransport.responding(new JevResponse(200, Map.of(
                "Content-Type", List.of("application/json"),
                "X-Jev-Model", List.of("jev-1.13.0"),
                "X-Jev-Trace", List.of("first", "second")), body));

        HttpResponse<byte[]> response;
        try (JevProxyServer proxy = new JevProxyServer(transport, "127.0.0.1", 0)) {
            response = post(proxy.baseUrl().resolve("/v1/systemone"), REQUEST_BODY);
        }

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(UPSTREAM_BODY);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(response.headers().firstValue("X-Jev-Model")).contains("jev-1.13.0");
        assertThat(response.headers().allValues("X-Jev-Trace")).containsExactly("first", "second");
    }

    @Test
    void passesEveryUpstreamErrorStatusThroughAsAResponse() throws Exception {
        for (int status : List.of(400, 401, 429, 500, 529)) {
            StubTransport transport = StubTransport.responding(new JevResponse(status, Map.of(
                    "Retry-After", List.of("30"),
                    "Content-Type", List.of("application/json")),
                    "{\"error\":\"upstream\"}".getBytes(StandardCharsets.UTF_8)));

            HttpResponse<byte[]> response;
            try (JevProxyServer proxy = new JevProxyServer(transport, "127.0.0.1", 0)) {
                response = post(proxy.baseUrl().resolve("/v1/systemone"), REQUEST_BODY);
            }

            assertThat(response.statusCode()).isEqualTo(status);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"error\":\"upstream\"}");
            assertThat(response.headers().firstValue("Retry-After")).contains("30");
        }
    }

    @Test
    void framesTheResponseItselfEvenWhenTheTransportSendsFramingHeaders() throws Exception {
        byte[] body = UPSTREAM_BODY.getBytes(StandardCharsets.UTF_8);
        StubTransport transport = StubTransport.responding(new JevResponse(200, Map.of(
                "Content-Length", List.of("999"),
                "Transfer-Encoding", List.of("chunked"),
                "Connection", List.of("close"),
                "Date", List.of("Mon, 01 Jan 2024 00:00:00 GMT"),
                "Content-Type", List.of("application/json")), body));

        HttpResponse<byte[]> response;
        try (JevProxyServer proxy = new JevProxyServer(transport, "127.0.0.1", 0)) {
            response = post(proxy.baseUrl().resolve("/v1/systemone"), REQUEST_BODY);
        }

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
    }

    @Test
    void sendsNoBodyWhenTheTransportHasNone() throws Exception {
        StubTransport transport = StubTransport.responding(
                new JevResponse(204, Map.of("X-Jev-Model", List.of("jev-1.13.0")), new byte[0]));

        HttpResponse<byte[]> response;
        try (JevProxyServer proxy = new JevProxyServer(transport, "127.0.0.1", 0)) {
            response = post(proxy.baseUrl().resolve("/v1/systemone"), REQUEST_BODY);
        }

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("X-Jev-Model")).contains("jev-1.13.0");
    }

    @Test
    void answersAGatewayErrorInsteadOfDroppingTheConnectionWhenTheTransportFails() throws Exception {
        StubTransport transport = StubTransport.failing(new UpstreamTimeout(
                "Upstream https://api.typesafe.ai did not respond within 150 ms",
                new HttpTimeoutException("request timed out")));

        HttpResponse<byte[]> response;
        try (JevProxyServer proxy = new JevProxyServer(transport, "127.0.0.1", 0)) {
            response = post(proxy.baseUrl().resolve("/v1/systemone"), REQUEST_BODY);
        }

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.headers().firstValue("Content-Type")).contains("text/plain; charset=utf-8");
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .contains("UpstreamTimeout")
                .contains("did not respond within 150 ms");
        assertThat(transport.received).hasSize(1);
    }

    @Test
    void servesAnApplicationEndToEndThroughTheLiveTransport() throws Exception {
        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.stub(200, Map.of("Content-Type", "application/json", "X-Jev-Model", "jev-1.13.0"),
                    UPSTREAM_BODY.getBytes(StandardCharsets.UTF_8));
            LiveJevTransport live = new LiveJevTransport(upstream.baseUrl(), Duration.ofSeconds(10));

            HttpResponse<byte[]> response;
            try (JevProxyServer proxy = new JevProxyServer(live, "127.0.0.1", 0)) {
                response = post(proxy.baseUrl().resolve("/v1/systemone?trace=1"), REQUEST_BODY,
                        "Content-Type", "application/json",
                        "Authorization", "Bearer sk-live-secret");
            }

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(UPSTREAM_BODY.getBytes(StandardCharsets.UTF_8));
            assertThat(response.headers().firstValue("X-Jev-Model")).contains("jev-1.13.0");

            FakeJevServer.Received received = upstream.received().get(0);
            assertThat(received.method()).isEqualTo("POST");
            assertThat(received.path()).isEqualTo("/v1/systemone?trace=1");
            assertThat(received.body()).isEqualTo(REQUEST_BODY);
            assertThat(received.header("Authorization")).isEqualTo("Bearer sk-live-secret");
        }
    }

    @Test
    void exposesTheAddressItBoundTo() throws Exception {
        try (JevProxyServer proxy = new JevProxyServer(StubTransport.responding(ok("{}")), "127.0.0.1", 0)) {
            URI baseUrl = proxy.baseUrl();

            assertThat(baseUrl.toString()).isEqualTo("http://127.0.0.1:" + baseUrl.getPort());
            assertThat(baseUrl.getPort()).isPositive();
            assertThat(post(baseUrl.resolve("/v1/systemone"), REQUEST_BODY).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void reportsAnUnusableListenAddressAsAConfigurationError() {
        assertThatThrownBy(() -> new JevProxyServer(StubTransport.responding(ok("{}")), "127.0.0.1", 70000))
                .isInstanceOf(ConfigurationError.class)
                .hasMessageContaining("Cannot listen on 127.0.0.1:70000");
    }

    @Test
    void reportsAnOccupiedPortAsAConfigurationError() {
        try (JevProxyServer first = new JevProxyServer(StubTransport.responding(ok("{}")), "127.0.0.1", 0)) {
            int port = first.baseUrl().getPort();

            assertThatThrownBy(() -> new JevProxyServer(StubTransport.responding(ok("{}")), "127.0.0.1", port))
                    .isInstanceOf(ConfigurationError.class)
                    .hasMessageContaining("Cannot listen on 127.0.0.1:" + port);
        }
    }

    @Test
    void stopsAcceptingRequestsWhenClosed() {
        JevProxyServer proxy = new JevProxyServer(StubTransport.responding(ok("{}")), "127.0.0.1", 0);
        URI target = proxy.baseUrl().resolve("/v1/systemone");
        proxy.close();

        assertThatThrownBy(() -> post(target, REQUEST_BODY)).isInstanceOf(IOException.class);
    }

    private static JevResponse ok(String body) {
        return new JevResponse(200, Map.of("Content-Type", List.of("application/json")),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> post(URI target, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .method("POST", HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(10));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String header(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> entry.getValue().get(0))
                .findFirst()
                .orElse(null);
    }

    /** Stands in for record/replay: remembers what arrived, answers with a canned response. */
    private static final class StubTransport implements JevTransport {

        private final List<JevRequest> received = new CopyOnWriteArrayList<>();
        private final JevResponse response;
        private final JevTapeException failure;

        private StubTransport(JevResponse response, JevTapeException failure) {
            this.response = response;
            this.failure = failure;
        }

        static StubTransport responding(JevResponse response) {
            return new StubTransport(response, null);
        }

        static StubTransport failing(JevTapeException failure) {
            return new StubTransport(null, failure);
        }

        @Override
        public JevResponse send(JevRequest request) {
            received.add(request);
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
