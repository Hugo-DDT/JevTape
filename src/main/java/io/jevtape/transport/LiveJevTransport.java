package io.jevtape.transport;

import io.jevtape.shared.UpstreamTimeout;
import io.jevtape.shared.UpstreamUnavailable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 通过 {@link HttpClient} 把请求转发到实时的 Jev API。
 *
 * <p>上游响应原样透传 —— 状态码、相关 headers 以及完整的 body —— 因此每个状态码，包括
 * 400/401/429/500/529，都会作为 {@link JevResponse} 返回并可被记录（charter §53, §54）。
 * 只有传输层级别的失败才变成异常：API 未及时应答时为 {@link UpstreamTimeout}，完全无法连接时为
 * {@link UpstreamUnavailable}。
 *
 * <p>Headers 原样转发，{@code Authorization} 也不例外。剥离凭证是 redaction 模块的职责，且只作用于
 * cassette 和日志，绝不作用于实时调用。
 */
public final class LiveJevTransport implements JevTransport {

    public static final URI DEFAULT_BASE_URL = URI.create("https://api.typesafe.ai");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** 逐跳 headers，加上 {@code java.net.http} 不允许客户端设置的那些。 */
    private static final Set<String> NOT_FORWARDED = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "content-length", "host", "expect", "via", "date",
            "from", "warning");

    private final HttpClient client;
    private final URI baseUrl;
    private final Duration timeout;

    public LiveJevTransport() {
        this(DEFAULT_BASE_URL, DEFAULT_TIMEOUT);
    }

    public LiveJevTransport(URI baseUrl, Duration timeout) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public JevResponse send(JevRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(upstreamUri(request.path()))
                .method(request.method(), HttpRequest.BodyPublishers.ofByteArray(request.body()))
                .timeout(timeout);
        String[] headers = flatten(forwardable(request.headers()));
        if (headers.length > 0) {
            // HttpRequest.Builder#headers 会拒绝空的 varargs 数组。
            builder.headers(headers);
        }

        HttpResponse<byte[]> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException e) {
            throw new UpstreamUnavailable("Upstream " + baseUrl + " did not accept a connection within "
                    + timeout.toMillis() + " ms", e);
        } catch (HttpTimeoutException e) {
            throw new UpstreamTimeout("Upstream " + baseUrl + " did not respond within "
                    + timeout.toMillis() + " ms", e);
        } catch (IOException e) {
            throw new UpstreamUnavailable("Upstream " + baseUrl + " could not be reached", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailable("Interrupted while calling upstream " + baseUrl, e);
        }
        return new JevResponse(response.statusCode(), forwardable(response.headers().map()), response.body());
    }

    private URI upstreamUri(String path) {
        String base = baseUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + (path.startsWith("/") ? path : "/" + path));
    }

    /** 丢弃逐跳与 HTTP/2 伪 headers；其余保持其原始大小写与顺序。 */
    private static Map<String, List<String>> forwardable(Map<String, List<String>> headers) {
        Map<String, List<String>> kept = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (name != null && !name.startsWith(":")
                    && !NOT_FORWARDED.contains(name.toLowerCase(Locale.ROOT))) {
                kept.put(name, values);
            }
        });
        return kept;
    }

    private static String[] flatten(Map<String, List<String>> headers) {
        List<String> pairs = new ArrayList<>();
        headers.forEach((name, values) -> values.forEach(value -> {
            pairs.add(name);
            pairs.add(value);
        }));
        return pairs.toArray(String[]::new);
    }
}
