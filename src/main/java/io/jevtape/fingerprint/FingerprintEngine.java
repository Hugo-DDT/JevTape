package io.jevtape.fingerprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jevtape.cassette.Fingerprints;
import io.jevtape.contract.JevProtocolAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 三个 fingerprint（charter §27, §28），全部是 canonical JSON 上的 SHA-256：
 *
 * <pre>
 * state    = sha256(canonical(state))
 * contract = sha256(canonical(questions))
 * request  = sha256(canonical({method, model, path, state, contract}))   ← Replay Key
 * </pre>
 *
 * <p>输入必须是**原始请求**的语义字段（charter §52：Matching Representation ≠ Storage Representation）。
 * 脱敏后的内容一律不参与计算，否则两个用户的凭证都变成 {@code [REDACTED]} 之后会得到相同指纹，replay 就会
 * 错误命中。反过来，header 根本不进指纹：凭证不是请求语义，同一决策换了 API Key 仍然是同一决策。
 *
 * <p>{@code request} 用 {@code requestedModel}（例如 {@code jev-latest}）而不是上游解析出的
 * {@code resolvedModel} —— replay 时手上只有请求，指纹必须能只凭请求算出来。
 */
public final class FingerprintEngine {

    private static final String PREFIX = "sha256:";

    private FingerprintEngine() {
    }

    public static Fingerprints of(String method, String path, JevProtocolAdapter.Decision decision) {
        String state = sha256(CanonicalJson.of(decision.state()));
        String contract = contract(decision.questions());
        ObjectNode source = JsonNodeFactory.instance.objectNode()
                .put("method", method)
                .put("model", decision.requestedModel())
                .put("path", pathWithoutQuery(path))
                .put("state", state)
                .put("contract", contract);
        return new Fingerprints(sha256(CanonicalJson.of(source)), contract, state);
    }

    /**
     * contract 指纹单独可用：verify 要为一份还没发出的请求算出它，再与 cassette 里存的那一个比
     * （charter §18, §28）。
     */
    public static String contract(JsonNode questions) {
        return sha256(CanonicalJson.of(questions));
    }

    /**
     * query 不参与指纹：System One 的语义字段都在 body 里，而 query 常带 trace id 之类每次调用都变的东西，
     * 算进来会让 replay 永远 MISS。
     *
     * <p>ponytail: 天花板是"query 里永远没有语义字段"；若将来某个 Jev 接口把语义放进 query，这里要改成
     * 白名单式提取而不是整体丢弃。
     */
    private static String pathWithoutQuery(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    private static String sha256(String canonicalJson) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandated by the JCA specification", e);
        }
        byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
        return PREFIX + HexFormat.of().formatHex(hash);
    }
}
