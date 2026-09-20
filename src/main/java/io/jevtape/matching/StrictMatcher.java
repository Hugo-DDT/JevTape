package io.jevtape.matching;

import io.jevtape.cassette.Cassette;

import java.util.List;
import java.util.Objects;

/**
 * STRICT：request fingerprint 相等才命中（charter §31）。V1 唯一策略。
 *
 * <p>request fingerprint 已经把 method、model、path、state 指纹与 contract 指纹全都卷进去了，因此
 * "逐字符相等"这一个判断就是全部的匹配语义 —— 没有相似度、没有降级、没有"这两个请求差不多"的猜测。
 * 语义匹配会让一个 Record/Replay 工具反过来依赖 AI，V1 明确不做。
 *
 * <p>本类只做决策：不读文件、不联网、不算指纹（charter §46）。
 */
public final class StrictMatcher {

    private StrictMatcher() {
    }

    /**
     * @param requestFingerprint 这次请求的 replay key
     * @param candidates         候选 cassette；顺序即优先级，命中的是第一个 fingerprint 相等的
     */
    public static MatchResult match(String requestFingerprint, List<Cassette> candidates) {
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(candidates, "candidates");
        // 反过来的 equals：cassette 是人可以手改的 JSON，缺了 request 指纹的那一个只是不命中，不该炸掉整次扫描。
        // ponytail: 每次请求线性扫一遍候选；天花板是 cassette 多到扫描本身变慢（几千个量级），
        // 届时在装载时按 request fingerprint 建索引即可，匹配语义不变。
        for (Cassette candidate : candidates) {
            if (requestFingerprint.equals(candidate.fingerprints().request())) {
                return new MatchResult.Hit(candidate);
            }
        }
        return new MatchResult.Miss(requestFingerprint);
    }
}
