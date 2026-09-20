package io.jevtape.cassette;

/**
 * The three fingerprints of a recorded call, each {@code sha256:<hex>} over canonical JSON
 * (charter §28). {@code request} is the replay key; {@code contract} and {@code state} are kept
 * separately so a miss can say which part changed (charter §29, §32).
 *
 * <p>Computed by the fingerprint module — this record only stores the result.
 */
public record Fingerprints(String request, String contract, String state) {
}
