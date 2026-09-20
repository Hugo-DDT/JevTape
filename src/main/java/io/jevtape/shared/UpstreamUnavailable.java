package io.jevtape.shared;

/** 完全无法连接上游 Jev API。 */
public final class UpstreamUnavailable extends JevTapeException {

    public UpstreamUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
