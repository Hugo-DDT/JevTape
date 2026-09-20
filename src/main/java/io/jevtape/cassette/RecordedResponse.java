package io.jevtape.cassette;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete response the client actually received — status, relevant headers and the whole
 * structured body, not a summary of it (charter §53). Error statuses are responses too and are
 * recorded and replayed like any other (charter §54).
 *
 * <p>Headers keep their recorded order so rewriting a cassette produces no diff.
 */
public record RecordedResponse(int status, Map<String, List<String>> headers, JsonNode body) {

    public RecordedResponse {
        headers = headers == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        // JSON null binds to NullNode, so a null component would not survive a round trip.
        body = body == null ? NullNode.getInstance() : body;
    }
}
