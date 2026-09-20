package io.jevtape.cassette;

/**
 * When and by what a cassette was recorded (charter §25, §54).
 *
 * <p>{@code recordedAt} is an ISO-8601 UTC instant in second precision, e.g.
 * {@code 2026-09-20T10:20:30Z} — the textual form the format promises, kept as text so the file
 * stays readable and sorts lexicographically.
 */
public record CassetteMetadata(String recordedAt, String jevtapeVersion, long durationMs) {
}
