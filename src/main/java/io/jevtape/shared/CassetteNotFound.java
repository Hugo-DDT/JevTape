package io.jevtape.shared;

/** No cassette exists at the requested location. */
public final class CassetteNotFound extends JevTapeException {

    public CassetteNotFound(String message) {
        super(message);
    }
}
