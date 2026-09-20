package io.jevtape.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Jev API 的最小进程内替身,使构建过程完全不依赖网络或凭据。
 * Task 11 会将其扩展为验收测试套件所驱动的完整状态矩阵。
 */
public final class FakeJevServer implements AutoCloseable {

    /** 模拟上游收到的一次请求。Header 查找不区分大小写。 */
    public record Received(String method, String path, Map<String, List<String>> headers, byte[] body) {

        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();

    private volatile int status = 200;
    private volatile Map<String, String> responseHeaders = Map.of();
    private volatile byte[] responseBody = new byte[0];
    private volatile Duration delay = Duration.ZERO;

    public FakeJevServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not bind fake Jev server", e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    /** 之后所有请求都以此为固定应答。 */
    public FakeJevServer stub(int status, Map<String, String> headers, byte[] body) {
        this.status = status;
        this.responseHeaders = Map.copyOf(headers);
        this.responseBody = body;
        return this;
    }

    public FakeJevServer stub(int status, String jsonBody) {
        return stub(status, Map.of("Content-Type", "application/json"),
                jsonBody.getBytes(StandardCharsets.UTF_8));
    }

    /** 延迟指定时长后才应答,用于测试上游超时。 */
    public FakeJevServer delay(Duration delay) {
        this.delay = delay;
        return this;
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(exchange.getRequestHeaders());
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                Collections.unmodifiableMap(headers), body));

        sleep(delay);
        responseHeaders.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        exchange.sendResponseHeaders(status, responseBody.length == 0 ? -1 : responseBody.length);
        if (responseBody.length > 0) {
            exchange.getResponseBody().write(responseBody);
        }
        exchange.close();
    }

    private static void sleep(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
