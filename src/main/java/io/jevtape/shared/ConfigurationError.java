package io.jevtape.shared;

/** CLI 参数、环境变量或 config.json 缺失、格式错误或相互冲突。 */
public final class ConfigurationError extends JevTapeException {

    public ConfigurationError(String message) {
        super(message);
    }

    public ConfigurationError(String message, Throwable cause) {
        super(message, cause);
    }
}
