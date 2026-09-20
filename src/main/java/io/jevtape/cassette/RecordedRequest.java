package io.jevtape.cassette;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Objects;

/**
 * 记录下来的请求：已经过净化，且绝不是用于计算 fingerprint 的那些字节
 * （charter §52 —— Matching Representation ≠ Storage Representation）。
 *
 * <p>刻意不含 headers：值得保留的都是凭证，而凭证绝不能进入 cassette。{@code state} 和
 * {@code questions} 保持为结构化 JSON，以便它们仍可检视、可 diff；把它们解析成 Jev 概念是
 * {@code JevProtocolAdapter} 的职责，而非本 record 的职责。
 *
 * <p>{@code requestedModel} 与 {@code resolvedModel} 都予以保留，因为 {@code jev-latest} 并不承诺
 * 永远指向同一个模型（charter §34）。
 */
public record RecordedRequest(String method,
                              String path,
                              String requestedModel,
                              String resolvedModel,
                              JsonNode state,
                              JsonNode questions) {

    public RecordedRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        // JSON null 会绑定为 NullNode，因此 null 分量无法在一次往返中存活。
        state = state == null ? NullNode.getInstance() : state;
        questions = questions == null ? NullNode.getInstance() : questions;
    }
}
