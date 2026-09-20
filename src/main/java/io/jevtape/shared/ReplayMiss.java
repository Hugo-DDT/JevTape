package io.jevtape.shared;

/** 没有任何已录制的交互与该请求的 fingerprint 匹配。 */
public final class ReplayMiss extends JevTapeException {

    public ReplayMiss(String message) {
        super(message);
    }
}
