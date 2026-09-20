package io.jevtape.cassette;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端实际收到的完整响应 —— 状态码、相关 headers 以及整个结构化 body，而非其摘要（charter §53）。
 * 错误状态码也是响应，和其他响应一样被记录与回放（charter §54）。
 *
 * <p>Headers 保持记录时的顺序，因此重写一个 cassette 不会产生 diff。
 */
public record RecordedResponse(int status, Map<String, List<String>> headers, JsonNode body) {

    public RecordedResponse {
        headers = headers == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        // JSON null 会绑定为 NullNode，因此 null 分量无法在一次往返中存活。
        body = body == null ? NullNode.getInstance() : body;
    }
}
