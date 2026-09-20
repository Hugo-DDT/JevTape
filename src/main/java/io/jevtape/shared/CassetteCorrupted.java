package io.jevtape.shared;

/** cassette 文件存在，但无法解析为有效的 JevTape JSON。 */
public final class CassetteCorrupted extends JevTapeException {

    public CassetteCorrupted(String message) {
        super(message);
    }

    public CassetteCorrupted(String message, Throwable cause) {
        super(message, cause);
    }
}
