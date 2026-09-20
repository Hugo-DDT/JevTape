package io.jevtape.transport;

/**
 * 发送一个 Jev 请求并返回响应。
 *
 * <p>实时转发、cassette 回放与录制都实现本接口，因此核心从不依赖具体的 HTTP 客户端或服务端框架。
 * 失败由 {@link io.jevtape.shared.JevTapeException} 的子类表示；上游应答的每一个 HTTP 状态码都是正常响应。
 */
public interface JevTransport {

    JevResponse send(JevRequest request);
}
