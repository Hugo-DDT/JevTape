package io.jevtape.matching;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.Fingerprints;

import java.util.List;
import java.util.Objects;

/**
 * 一次 MISS 的解释（charter §32）：这次的 replay key，加上最接近的那个 cassette 的逐项对比。
 *
 * <p>"最接近"是数出来的，不是猜出来的：state / contract / model 三项里 MATCH 最多的那一个胜出，并列时取
 * 候选顺序里的第一个（{@code loadAll} 按名称排序，因此结果确定）。三项各自都是逐字符相等的判断 —— 没有
 * 相似度、没有语义比较，诊断不会比匹配本身更聪明。
 *
 * <p>ponytail: method 与 path 也进 request 指纹，但不在诊断的三项里（charter §32 只列这三项）；天花板是
 * "三项全 MATCH 却还是 MISS"，那说明 method 或 path 变了，届时再加第四项。
 */
public record MissDiagnosis(String requestFingerprint, String closestCassette,
                            Verdict state, Verdict contract, Verdict model) {

    /** UNKNOWN 表示无从比较：一个候选都没有，或者那个 cassette 缺了这一项指纹（人可以手改 JSON）。 */
    public enum Verdict { MATCH, CHANGED, UNKNOWN }

    public MissDiagnosis {
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(model, "model");
    }

    /** 一个候选都没有时 {@code closestCassette} 为 null，三项都是 UNKNOWN —— 没有什么可比。 */
    public static MissDiagnosis of(Fingerprints request, String requestedModel, List<Cassette> candidates) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidates, "candidates");
        MissDiagnosis closest = null;
        int best = -1;
        for (Cassette candidate : candidates) {
            MissDiagnosis diagnosis = compare(request, requestedModel, candidate);
            int score = matches(diagnosis);
            // 严格大于才替换，于是并列时取候选顺序里的第一个。
            if (score > best) {
                best = score;
                closest = diagnosis;
            }
        }
        return closest != null ? closest : new MissDiagnosis(request.request(), null,
                Verdict.UNKNOWN, Verdict.UNKNOWN, Verdict.UNKNOWN);
    }

    /** 一行诊断，给异常消息与 HTTP 错误 body 用；完整的对比由 CLI 逐行渲染。 */
    public String summary() {
        return closestCassette == null
                ? "no cassette to compare against"
                : "closest " + closestCassette + " (state " + state + ", contract " + contract
                        + ", model " + model + ")";
    }

    private static int matches(MissDiagnosis diagnosis) {
        return (diagnosis.state() == Verdict.MATCH ? 1 : 0)
                + (diagnosis.contract() == Verdict.MATCH ? 1 : 0)
                + (diagnosis.model() == Verdict.MATCH ? 1 : 0);
    }

    private static MissDiagnosis compare(Fingerprints request, String requestedModel, Cassette candidate) {
        Fingerprints recorded = candidate.fingerprints();
        return new MissDiagnosis(request.request(), candidate.name(),
                verdict(request.state(), recorded.state()),
                verdict(request.contract(), recorded.contract()),
                // model 缺席本身就是一种取值：两边都没有 model 是同一件事，因此比的是相等而不是"有没有"。
                Objects.equals(requestedModel, candidate.request().requestedModel())
                        ? Verdict.MATCH : Verdict.CHANGED);
    }

    /** 两边都有值才比得动；cassette 缺了这一项就是 UNKNOWN，而不是假装它变了。 */
    private static Verdict verdict(String requested, String recorded) {
        if (requested == null || recorded == null) {
            return Verdict.UNKNOWN;
        }
        return requested.equals(recorded) ? Verdict.MATCH : Verdict.CHANGED;
    }
}
