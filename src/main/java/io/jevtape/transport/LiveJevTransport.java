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
 * Forwards requests to the live Jev API over {@link HttpClient}.
 *
 * <p>The upstream response is passed through untouched — status, relevant headers and the complete
 * body — so every status, including 400/401/429/500/529, comes back as a {@link JevResponse} and
 * can be recorded (charter §53, §54). Only a transport-level failure becomes an exception:
 * {@link UpstreamTimeout} when the API does not answer in time, {@link UpstreamUnavailable} when it
 * cannot be reached at all.
 *
 * <p>Headers are forwarded verbatim, {@code Authorization} included. Stripping credentials is the
 * redaction module's job and applies to cassettes and logs only, never to the live call.
 */
public final class LiveJevTransport implements JevTransport {

    public static final URI DEFAULT_BASE_URL = URI.create("https://api.typesafe.ai");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** Hop-by-hop headers, plus the ones {@code java.net.http} refuses to let a client set. */
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
            // HttpRequest.Builder#headers rejects an empty varargs array.
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

    /** Drops hop-by-hop and HTTP/2 pseudo headers; the rest keeps its original casing and order. */
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
