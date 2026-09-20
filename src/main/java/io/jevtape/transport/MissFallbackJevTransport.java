package io.jevtape.transport;

import io.jevtape.shared.ReplayMiss;

import java.util.Objects;

/**
 * miss 策略 {@code live} / {@code record} 的装配点（charter §33）：先问磁带，磁带没有答案时才把这一次请求
 * 交给上游。上游那一层是谁由装配方决定 —— {@link LiveJevTransport} 就是 live，
 * {@link RecordingJevTransport} 就是 record。
 *
 * <p>它是一个装饰器，而不是 {@link ReplayJevTransport} 里的一个分支：默认的 replay 装配里根本没有这个类，
 * 也没有任何指向上游的东西，于是"replay 默认离线"仍然是构造出来的事实（不变量 2），而不是
 * {@code if (onMiss != error) callNetwork()} 的行为。
 *
 * <p>只有 {@link ReplayMiss} 会触发转发。磁带损坏、请求认不出来这些失败照原样抛出 —— 它们不是"没有答案"，
 * 拿它们去请求线上只会把一个本地问题伪装成一次真实调用。
 */
public final class MissFallbackJevTransport implements JevTransport {

    private final JevTransport replay;
    private final JevTransport upstream;

    /**
     * @param replay   先问的那一层，通常是 {@link ReplayJevTransport}
     * @param upstream MISS 之后转发的那一层；本类不知道也不关心它会不会落盘
     */
    public MissFallbackJevTransport(JevTransport replay, JevTransport upstream) {
        this.replay = Objects.requireNonNull(replay, "replay");
        this.upstream = Objects.requireNonNull(upstream, "upstream");
    }

    @Override
    public JevResponse send(JevRequest request) {
        try {
            return replay.send(request);
        } catch (ReplayMiss ignored) {
            return upstream.send(request);
        }
    }
}
