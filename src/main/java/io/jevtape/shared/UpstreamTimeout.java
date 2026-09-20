package io.jevtape.shared;

/** 上游 Jev API 未在配置的超时时间内响应。 */
public final class UpstreamTimeout extends JevTapeException {

    public UpstreamTimeout(String message, Throwable cause) {
        super(message, cause);
    }
}
