package io.jevtape.transport;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 应用所发出的 Jev 请求。{@code path} 是请求目标，可能带有查询字符串；{@code body} 是原始载荷，
 * 以字节形式保留，以便回放保持逐字节一致。
 *
 * <p>无值相等语义：请求的身份由 fingerprint 定义，而非由字节定义。
 */
public record JevRequest(String method, String path, Map<String, List<String>> headers, byte[] body) {

    public JevRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }
}
