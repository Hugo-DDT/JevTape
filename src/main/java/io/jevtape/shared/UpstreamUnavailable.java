package io.jevtape.shared;

/** The upstream Jev API could not be reached at all. */
public final class UpstreamUnavailable extends JevTapeException {

    public UpstreamUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
