package io.jevtape.shared;

/** cassette 声明的 schemaVersion 超出当前构建的读取能力。 */
public final class CassetteVersionUnsupported extends JevTapeException {

    public CassetteVersionUnsupported(String message) {
        super(message);
    }
}
