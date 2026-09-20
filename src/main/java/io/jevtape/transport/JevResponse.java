package io.jevtape.transport;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 一个 Jev 响应，原样透传：状态码、相关 headers 以及完整的 body（charter §53, §54）。
 * 错误状态码也是响应 —— 只有传输层失败才会变成异常。
 *
 * <p>相等性按值判断，body 也包含在内，因为"相同请求、相同响应"是回放必须证明的不变量。
 */
public record JevResponse(int status, Map<String, List<String>> headers, byte[] body) {

    public JevResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JevResponse response
                && status == response.status
                && headers.equals(response.headers)
                && Arrays.equals(body, response.body);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * status + headers.hashCode()) + Arrays.hashCode(body);
    }
}
