package io.jevtape.transport;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A Jev request as the application sent it. {@code path} is the request target and may carry a
 * query string; {@code body} is the raw payload, kept as bytes so replay stays byte-identical.
 *
 * <p>No value equality: request identity is defined by fingerprints, not by bytes.
 */
public record JevRequest(String method, String path, Map<String, List<String>> headers, byte[] body) {

    public JevRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }
}
