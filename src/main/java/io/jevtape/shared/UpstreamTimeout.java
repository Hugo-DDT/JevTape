package io.jevtape.shared;

/** The upstream Jev API did not answer within the configured timeout. */
public final class UpstreamTimeout extends JevTapeException {

    public UpstreamTimeout(String message, Throwable cause) {
        super(message, cause);
    }
}
