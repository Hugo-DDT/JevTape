package io.jevtape.shared;

/** 没有任何已录制的交互与该请求的 fingerprint 匹配。 */
public final class ReplayMiss extends JevTapeException {

    /** 客户端可以据此断定"磁带里没有答案"，而不是把它当成上游的失败（charter §32）。 */
    public static final String CODE = "JEVTAPE_REPLAY_MISS";

    public ReplayMiss(String message) {
        super(message);
    }

    @Override
    public String code() {
        return CODE;
    }
}
