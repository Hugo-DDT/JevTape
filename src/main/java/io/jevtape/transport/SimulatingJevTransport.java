package io.jevtape.transport;

import io.jevtape.contract.AnswerOverrides;
import io.jevtape.contract.JevProtocolAdapter;

import java.util.Objects;

/**
 * simulate 模式的故障注入（charter §11, §20）：装饰 replay，把录制的应答改成一个边界场景 —— 慢一点、
 * 换一个状态码、把 confidence 压到应用的阈值之下。
 *
 * <p>它是 {@link ReplayJevTransport} **外面**的装饰器，与 {@link MissFallbackJevTransport} 同一个位置，
 * 方向却相反：那一层往外接上游，这一层只改手上这一份应答，自己同样不持有任何上游客户端 —— 注入 latency
 * 不会让 JevTape 去问线上"这次要多久"，注入 429 也不会真的触发一次限流。simulate 因此和 replay 一样，
 * 离线是装配出来的事实（不变量 1、2），banner 里的 {@code Network: OFF} 说的就是这件事。
 *
 * <p>MISS 不被注入：磁带里没有答案是一个本地事实，把它伪装成 429 会让"我没录这个请求"与"上游限流了"在
 * 客户端看来一模一样 —— 那正好是 simulate 要帮人区分开的两件事。
 *
 * <p>latency 是真的睡过去，于是"模拟超时"是被测应用**自己**超时：把 {@code --latency} 设到超过客户端的
 * timeout，客户端经历的就是一次真的读超时，而不是 JevTape 编出来的一个错误码。
 */
public final class SimulatingJevTransport implements JevTransport {

    private final JevTransport replay;
    private final long latencyMs;
    private final Integer httpStatus;
    private final AnswerOverrides answers;

    /**
     * @param replay     被装饰的那一层，通常是 {@link ReplayJevTransport}
     * @param latencyMs  每个应答要压住多久，0 表示不压
     * @param httpStatus 要换成的状态码，null 表示照录制的回
     * @param answers    要写进应答的作答覆盖；{@link AnswerOverrides#NONE} 表示一个都不改
     */
    public SimulatingJevTransport(JevTransport replay, long latencyMs, Integer httpStatus, AnswerOverrides answers) {
        this.replay = Objects.requireNonNull(replay, "replay");
        this.latencyMs = latencyMs;
        this.httpStatus = httpStatus;
        this.answers = Objects.requireNonNull(answers, "answers");
    }

    @Override
    public JevResponse send(JevRequest request) {
        // 未命中时这里就把 ReplayMiss 抛出去了：注入只作用于真的被服务的那些应答。
        JevResponse recorded = replay.send(request);
        hold();
        return new JevResponse(httpStatus == null ? recorded.status() : httpStatus, recorded.headers(),
                JevProtocolAdapter.overrideAnswers(recorded.body(), answers));
    }

    /** 被中断（代理正在关闭）就停止等待并照常应答，不为一次注入编造一个失败出来。 */
    private void hold() {
        if (latencyMs <= 0) {
            return;
        }
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
