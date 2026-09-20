package io.jevtape.cassette;

/**
 * Everything one Jev call left behind: metadata, request, response and fingerprints (charter §23).
 *
 * <p>Phase 1 stores exactly one interaction per cassette (charter §22), so the four groups sit at
 * the document root and this record is the shape {@link Cassette} hands to callers that only care
 * about the call itself.
 */
public record Interaction(CassetteMetadata metadata,
                          RecordedRequest request,
                          RecordedResponse response,
                          Fingerprints fingerprints) {
}
