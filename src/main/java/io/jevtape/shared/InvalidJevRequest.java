package io.jevtape.shared;

/** 传入的请求不是 JevTape 可解析的 Jev 请求。 */
public final class InvalidJevRequest extends JevTapeException {

    public InvalidJevRequest(String message) {
        super(message);
    }
}
