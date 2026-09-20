package io.jevtape.shared;

/** 请求位置不存在对应的 cassette。 */
public final class CassetteNotFound extends JevTapeException {

    public CassetteNotFound(String message) {
        super(message);
    }
}
