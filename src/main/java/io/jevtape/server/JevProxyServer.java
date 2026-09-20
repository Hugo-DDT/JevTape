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
 * The local access layer applications point their base URL at.
 *
 * <p>This is an explicit proxy, not a man in the middle: no TLS is intercepted and no CA is
 * installed. The client's {@code Host} never selects a destination — the request target's path and
 * query go to whichever upstream the injected {@link JevTransport} owns, so pointing the base URL
 * here is the only way in.
 *
 * <p>HTTP in/out and nothing else (charter §35, §41): matching, storage and fingerprints all live
 * behind {@code JevTransport}. Status and body are written back exactly as the transport returned
 * them, error statuses included; header names get the JDK server's canonical casing, which does not
 * matter because HTTP header names are case-insensitive.
 */
public final class JevProxyServer implements AutoCloseable {

    /** Hop-by-hop headers plus the ones the JDK server frames or stamps itself. */
    private static final Set<String> NOT_WRITTEN = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "te", "trailer", "transfer-encoding",
            "upgrade", "content-length", "date", "server");

    private final HttpServer server;
    private final ExecutorService executor;

    /** Binds and starts serving immediately; {@link #close()} is what stops it. */
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

    /** Where applications should point their base URL, e.g. {@code http://127.0.0.1:8787}. */
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

    /** Path and query in their original percent-encoding, without scheme or authority. */
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
        // -1 means "no body at all"; 0 would switch the JDK server to chunked framing.
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
    }

    /**
     * A failed transport still owes the client an answer, so the connection is never dropped
     * silently.
     *
     * <p>ponytail: every failure maps to 502 regardless of type; replay-miss diagnostics need their
     * own status and body once miss policy lands.
     */
    private static void writeFailure(HttpExchange exchange, JevTapeException failure) throws IOException {
        byte[] body = ("jevtape: " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(502, body.length);
        exchange.getResponseBody().write(body);
    }
}
