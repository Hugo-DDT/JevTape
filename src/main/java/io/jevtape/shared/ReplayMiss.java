package io.jevtape.shared;

/** No recorded interaction matches the request fingerprint. */
public final class ReplayMiss extends JevTapeException {

    public ReplayMiss(String message) {
        super(message);
    }
}
