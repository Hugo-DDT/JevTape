package io.jevtape.shared;

/** Cassette storage could not be read from or written to. */
public final class StorageFailure extends JevTapeException {

    public StorageFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
