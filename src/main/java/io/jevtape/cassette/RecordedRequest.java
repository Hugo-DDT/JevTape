package io.jevtape.cassette;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Objects;

/**
 * The request as it was recorded: already sanitized, never the bytes that were fingerprinted
 * (charter §52 — Matching Representation ≠ Storage Representation).
 *
 * <p>No headers on purpose: the ones worth keeping are credentials, and those must never reach a
 * cassette. {@code state} and {@code questions} stay structured JSON so they remain inspectable and
 * diffable; parsing them into Jev concepts is {@code JevProtocolAdapter}'s job, not this record's.
 *
 * <p>{@code requestedModel} and {@code resolvedModel} are both kept because {@code jev-latest} does
 * not promise to point at the same model forever (charter §34).
 */
public record RecordedRequest(String method,
                              String path,
                              String requestedModel,
                              String resolvedModel,
                              JsonNode state,
                              JsonNode questions) {

    public RecordedRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        // JSON null binds to NullNode, so a null component would not survive a round trip.
        state = state == null ? NullNode.getInstance() : state;
        questions = questions == null ? NullNode.getInstance() : questions;
    }
}
