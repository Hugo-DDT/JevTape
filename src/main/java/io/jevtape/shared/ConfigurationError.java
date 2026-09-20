package io.jevtape.shared;

/** CLI arguments, environment variables, or config.json are missing, malformed, or conflicting. */
public final class ConfigurationError extends JevTapeException {

    public ConfigurationError(String message) {
        super(message);
    }

    public ConfigurationError(String message, Throwable cause) {
        super(message, cause);
    }
}
