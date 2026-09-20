package io.jevtape.shared;

/** A cassette file exists but cannot be parsed as valid JevTape JSON. */
public final class CassetteCorrupted extends JevTapeException {

    public CassetteCorrupted(String message, Throwable cause) {
        super(message, cause);
    }
}
