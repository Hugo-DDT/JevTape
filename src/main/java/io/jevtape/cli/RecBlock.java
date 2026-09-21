package io.jevtape.cli;

import io.jevtape.cassette.Cassette;
import io.jevtape.contract.DecisionContract;
import io.jevtape.contract.JevProtocolAdapter;

import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * REC 块（charter §56）。{@code record} 与 {@code replay --on-miss record} 共用这一份输出 —— 各写一遍只会让
 * "补录的磁带"和"专门录的磁带"在终端上长得不一样。
 *
 * <p>调用方负责串行化：这个方法跑在代理线程上，整块输出必须原子。
 */
final class RecBlock {

    private RecBlock() {
    }

    static void print(PrintWriter out, Path cassettes, Cassette cassette) {
        out.println();
        out.println("REC  " + cassette.name());
        for (DecisionContract.Question question : JevProtocolAdapter.questions(cassette.request().questions())) {
            out.println("     " + question.type() + " " + question.name());
        }
        out.println();
        String model = cassette.request().resolvedModel() != null
                ? cassette.request().resolvedModel()
                : cassette.request().requestedModel();
        if (model != null) {
            out.println("     " + model);
        }
        out.println("     " + cassette.metadata().durationMs() + " ms");
        out.println();
        out.println("     saved:");
        out.println("     " + cassettes.resolve(cassette.name() + ".json"));
        out.flush();
    }
}
