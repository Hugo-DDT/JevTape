package io.jevtape.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.Fingerprints;
import io.jevtape.cassette.RecordedResponse;
import io.jevtape.contract.JevProtocolAdapter;
import io.jevtape.fingerprint.FingerprintEngine;
import io.jevtape.matching.MatchResult;
import io.jevtape.matching.StrictMatcher;
import io.jevtape.shared.CassetteCorrupted;
import io.jevtape.shared.ReplayMiss;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * replay 模式：只从 cassette 里取应答，永远不联网（charter §16, §58）。
 *
 * <p>离线是**构造**出来的，不是判断出来的：本类没有任何指向上游的字段 —— 没有 {@code HttpClient}、
 * 没有 base URL、也没有可委托的 {@link JevTransport}，连磁盘都不碰（cassette 由调用方装载好交进来）。
 * 于是"replay 期间零上游请求"是类型层面的事实，而不是 {@code if (replay) maybeCallNetwork()} 这种
 * 分支的行为。
 *
 * <p>命中时交回录制的响应：状态码、headers 与 body 都是当年那一份（headers 是脱敏后的，凭证从来没被
 * 存下来）。body 是 cassette 里那份结构化 JSON 的紧凑序列化 —— 格式保留的是语义而非空白字符。
 *
 * <p>未命中抛 {@link ReplayMiss}：默认策略是 error，绝不"找不到就去请求线上"（charter §33）。
 */
public final class ReplayJevTransport implements JevTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Cassette> cassettes;
    private final Consumer<MatchResult> onMatch;

    /**
     * @param cassettes 已装载的候选 cassette；本类不会再读磁盘
     * @param onMatch   每次匹配决策回调一次，CLI 用它渲染 HIT / MISS
     */
    public ReplayJevTransport(List<Cassette> cassettes, Consumer<MatchResult> onMatch) {
        this.cassettes = List.copyOf(Objects.requireNonNull(cassettes, "cassettes"));
        this.onMatch = Objects.requireNonNull(onMatch, "onMatch");
    }

    @Override
    public JevResponse send(JevRequest request) {
        // 指纹的源永远是**原始**请求（charter §52）；认不出来的 Jev 请求在这里就被 InvalidJevRequest 拦下。
        JevProtocolAdapter.Decision decision = JevProtocolAdapter.parseRequest(request.body());
        Fingerprints fingerprints = FingerprintEngine.of(request.method(), request.path(), decision);

        MatchResult result = StrictMatcher.match(fingerprints.request(), cassettes);
        onMatch.accept(result);
        return switch (result) {
            case MatchResult.Hit hit -> replay(hit.cassette().response());
            case MatchResult.Miss miss -> throw new ReplayMiss("No cassette matches request fingerprint "
                    + miss.requestFingerprint() + " (" + cassettes.size() + " loaded)");
        };
    }

    private static JevResponse replay(RecordedResponse recorded) {
        return new JevResponse(recorded.status(), recorded.headers(), bodyBytes(recorded.body()));
    }

    /**
     * 录制时非 JSON 的原件退化成 TextNode、空 body 退化成 NullNode（见
     * {@link JevProtocolAdapter#parseResponse}），回放要把这两种退化还原成当年的字节，而不是给它们加上
     * 引号或写出 {@code null}。
     *
     * <p>ponytail: 代价是上游真回了一个 JSON {@code null} body 时，回放得到空 body —— cassette 格式
     * 不区分这两者，要区分就得存原始字节，那 cassette 就不再可读了。
     */
    private static byte[] bodyBytes(JsonNode body) {
        if (body.isTextual()) {
            return body.textValue().getBytes(StandardCharsets.UTF_8);
        }
        if (body.isNull()) {
            return new byte[0];
        }
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new CassetteCorrupted("Cassette holds a response body that cannot be written back as JSON", e);
        }
    }
}
