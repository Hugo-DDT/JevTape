package io.jevtape.shared;

/** A cassette declares a schemaVersion this build cannot read. */
public final class CassetteVersionUnsupported extends JevTapeException {

    public CassetteVersionUnsupported(String message) {
        super(message);
    }
}
