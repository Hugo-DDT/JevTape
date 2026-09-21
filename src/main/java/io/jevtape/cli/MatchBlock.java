package io.jevtape.cli;

import io.jevtape.cassette.Cassette;
import io.jevtape.matching.MatchResult;
import io.jevtape.matching.MissDiagnosis;

import java.io.PrintWriter;

/**
 * HIT / MISS 块（charter §57）。{@code replay} 与 {@code simulate} 共用这一份输出 —— 两个命令跑的是同一个
 * 匹配决策，终端上不该长出两种样子（REC 块同理，见 {@link RecBlock}）。
 *
 * <p>调用方负责串行化：这个方法跑在代理线程上，整块输出必须原子。
 */
final class MatchBlock {

    private MatchBlock() {
    }

    /**
     * {@code forwarded} 非 null 时在 MISS 块末尾多打一行 —— 联网这件事必须说出来，开发者不该从一片安静的
     * 输出里猜"刚刚是不是偷偷请求了线上"（charter §33）。simulate 永远传 null：它没有上游可转发。
     *
     * <p>HIT 里的耗时是**录制那一次**决策的耗时，不是回放耗时 —— 回放不走网络，量它没有信息量。MISS 里逐项
     * 列出最接近那个 cassette 的对比（charter §32），开发者应当一眼看出是 state 变了、question 变了还是
     * model 变了；一个候选都没有时没有可比的东西，只留 replay key。
     */
    static void print(PrintWriter out, MatchResult result, String forwarded) {
        out.println();
        switch (result) {
            case MatchResult.Hit hit -> {
                Cassette cassette = hit.cassette();
                out.println("HIT  " + cassette.name());
                out.println("     " + cassette.fingerprints().request());
                out.println("     " + cassette.metadata().durationMs() + " ms");
            }
            case MatchResult.Miss miss -> {
                out.println("MISS");
                out.println("     " + miss.requestFingerprint());
                MissDiagnosis diagnosis = miss.diagnosis();
                if (diagnosis.closestCassette() != null) {
                    out.println("     closest   " + diagnosis.closestCassette());
                    out.println("     state     " + diagnosis.state());
                    out.println("     contract  " + diagnosis.contract());
                    out.println("     model     " + diagnosis.model());
                }
                if (forwarded != null) {
                    out.println("     " + forwarded);
                }
            }
        }
        out.flush();
    }
}
