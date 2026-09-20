package io.jevtape.shared;

/** The incoming request is not a Jev request JevTape can interpret. */
public final class InvalidJevRequest extends JevTapeException {

    public InvalidJevRequest(String message) {
        super(message);
    }
}
