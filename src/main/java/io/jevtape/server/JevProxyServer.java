package io.jevtape.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jevtape.shared.ConfigurationError;
import io.jevtape.shared.JevTapeException;
import io.jevtape.transport.JevRequest;
import io.jevtape.transport.JevResponse;
import io.jevtape.transport.JevTransport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用把它们的 base URL 指向这里的本地接入层。
 *
 * <p>这是一个显式代理，而非中间人：不拦截 TLS，也不安装 CA。客户端的 {@code Host} 从不决定目的地 ——
 * 请求目标的 path 与 query 会交给所注入的 {@link JevTransport} 所拥有的那个上游，因此把 base URL 指向这里
 * 是进入的唯一途径。
 *
 * <p>只负责 HTTP 进出，仅此而已（charter §35, §41）：匹配、存储与 fingerprint 全都位于
 * {@code JevTransport} 之后。状态码与 body 都按传输层返回的原样写回，包括错误状态码；header 名称会采用
 * JDK 服务端的规范大小写，这无关紧要，因为 HTTP header 名称本就大小写不敏感。
 */
public final class JevProxyServer implements AutoCloseable {

    /** 逐跳 headers，加上 JDK 服务端自行加框或加盖的那些。 */
    private static final Set<String> NOT_WRITTEN = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "te", "trailer", "transfer-encoding",
            "upgrade", "content-length", "date", "server");

    private final HttpServer server;
    private final ExecutorService executor;

    /** 立即绑定并开始服务；要靠 {@link #close()} 停止。 */
    public JevProxyServer(JevTransport transport, String listen, int port) {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(listen, "listen");
        try {
            server = HttpServer.create(new InetSocketAddress(listen, port), 0);
        } catch (IOException | IllegalArgumentException e) {
            throw new ConfigurationError("Cannot listen on " + listen + ":" + port
                    + " — " + e.getMessage(), e);
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> handle(exchange, transport));
        server.start();
    }

    /** 应用应当把 base URL 指向这里，例如 {@code http://127.0.0.1:8787}。 */
    public URI baseUrl() {
        InetSocketAddress bound = server.getAddress();
        return URI.create("http://" + bound.getHostString() + ":" + bound.getPort());
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static void handle(HttpExchange exchange, JevTransport transport) throws IOException {
        try (exchange) {
            JevRequest request = new JevRequest(
                    exchange.getRequestMethod(),
                    target(exchange.getRequestURI()),
                    Map.copyOf(exchange.getRequestHeaders()),
                    exchange.getRequestBody().readAllBytes());
            JevResponse response;
            try {
                response = transport.send(request);
            } catch (JevTapeException e) {
                writeFailure(exchange, e);
                return;
            }
            write(exchange, response);
        }
    }

    /** 以原始百分号编码给出的 path 与 query，不含 scheme 与 authority。 */
    private static String target(URI uri) {
        String query = uri.getRawQuery();
        return query == null ? uri.getRawPath() : uri.getRawPath() + "?" + query;
    }

    private static void write(HttpExchange exchange, JevResponse response) throws IOException {
        Headers out = exchange.getResponseHeaders();
        response.headers().forEach((name, values) -> {
            if (!NOT_WRITTEN.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> out.add(name, value));
            }
        });
        byte[] body = response.body();
        // -1 表示"完全没有 body"；0 会让 JDK 服务端改用分块传输编码。
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
    }

    /**
     * 即便传输层失败，欠客户端的答复仍要给出，因此连接从不被静默丢弃。
     *
     * <p>ponytail: 不论类型，每个失败都映射为 502；待 miss 策略落地后，replay-miss 诊断需要它们自己的
     * 状态码与 body。
     */
    private static void writeFailure(HttpExchange exchange, JevTapeException failure) throws IOException {
        byte[] body = ("jevtape: " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(502, body.length);
        exchange.getResponseBody().write(body);
    }
}
