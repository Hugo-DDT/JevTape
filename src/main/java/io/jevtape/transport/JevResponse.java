package io.jevtape.transport;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A Jev response, passed through untouched: status, relevant headers and the complete body
 * (charter §53, §54). Error statuses are responses too — only transport failures become exceptions.
 *
 * <p>Equality is by value, body included, because "same request, same response" is the invariant
 * replay has to prove.
 */
public record JevResponse(int status, Map<String, List<String>> headers, byte[] body) {

    public JevResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JevResponse response
                && status == response.status
                && headers.equals(response.headers)
                && Arrays.equals(body, response.body);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * status + headers.hashCode()) + Arrays.hashCode(body);
    }
}
