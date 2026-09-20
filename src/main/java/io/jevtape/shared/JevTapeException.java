package io.jevtape.shared;

/**
 * Root of the explicit JevTape error taxonomy. Abstract so a failure can never be thrown
 * without naming its type; the CLI renders these as a single line instead of a stack trace.
 */
public abstract class JevTapeException extends RuntimeException {

    protected JevTapeException(String message) {
        super(message);
    }

    protected JevTapeException(String message, Throwable cause) {
        super(message, cause);
    }
}
