package io.jevtape.cli;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.config.ConfigLoader;
import io.jevtape.config.JevTapeConfig;
import io.jevtape.contract.AnswersDiff;
import io.jevtape.contract.JevProtocolAdapter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.jevtape.cli.Report.Line;
import static io.jevtape.cli.Report.section;

/**
 * {@code jevtape diff <old> <new>}：比较两盘磁带答了什么（charter §19）—— Choice 的概率分布、选了哪一个、
 * Score 的分数、Noul 的 probability、各自的 confidence，逐项 {@code old → new}。
 *
 * <p>这一层只做参数解析与渲染：装载交给 {@link FileCassetteRepository}，作答的取值交给
 * {@link JevProtocolAdapter}，比较交给 {@link AnswersDiff}，因此这里不出现任何 System One 的字段名。
 *
 * <p>只有真的有差异的 question 才成节；成节的那一个把全部字段都列出来，包括没变的 —— 看到
 * {@code choice billing → billing} 才知道"选项没换，只是概率挪了"。
 *
 * <p>退出码恒为 0（除非出错）：diff 报的是数据差异，不是"哪一盘更好"的裁决（charter §19），因此它不当
 * 闸门用；要闸门的是 {@code verify}。全程只读本地文件，不碰网络。
 */
@Command(name = "diff",
        mixinStandardHelpOptions = true,
        description = "Compare what two cassettes answered: choices, scores, probabilities, confidence.")
final class DiffCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", paramLabel = "<old>",
            description = "Cassette name on the left, without the directory or the .json suffix.")
    private String oldName;

    @Parameters(index = "1", paramLabel = "<new>",
            description = "Cassette name on the right, without the directory or the .json suffix.")
    private String newName;

    @Option(names = {"-d", "--cassette-dir"}, description = "Directory both cassettes are read from.")
    private String cassetteDir;

    @Option(names = "--config", description = "Path of config.json (default: .jevtape/config.json).")
    private Path configFile;

    @Override
    public void run() {
        JevTapeConfig config = new ConfigLoader().load(cliOverrides(), System.getenv(),
                configFile == null ? ConfigLoader.DEFAULT_FILE : configFile);
        FileCassetteRepository repository = new FileCassetteRepository(Path.of(config.cassetteDir()));
        Cassette old = repository.read(oldName);
        Cassette now = repository.read(newName);

        AnswersDiff diff = AnswersDiff.between(
                JevProtocolAdapter.decisionAnswers(old.response().body()),
                JevProtocolAdapter.decisionAnswers(now.response().body()));

        List<String> output = new ArrayList<>();
        section(output, "Diff", header(old, now, diff));
        diff.questions().forEach(question -> section(output, title(question), rows(question)));

        PrintWriter out = spec.commandLine().getOut();
        out.println(String.join(System.lineSeparator(), output));
        out.flush();
    }

    /** 两边一样的字段只打一个值：{@code Model jev-1.13.0 → jev-1.13.0} 是噪音，不是信息。 */
    private static List<Line> header(Cassette old, Cassette now, AnswersDiff diff) {
        int changed = diff.questions().size();
        return List.of(
                new Line("Old", old.name()),
                new Line("New", now.name()),
                new Line("Model", change(model(old), model(now))),
                new Line("Status", change(String.valueOf(old.response().status()),
                        String.valueOf(now.response().status()))),
                new Line("Differences", changed == 0 ? "none"
                        : changed + (changed == 1 ? " question" : " questions")));
    }

    private static String title(AnswersDiff.Question question) {
        String scope = switch (question.presence()) {
            case BOTH -> "";
            case ONLY_OLD -> " (only in old)";
            case ONLY_NEW -> " (only in new)";
        };
        return "Question: " + question.name() + scope;
    }

    private static List<Line> rows(AnswersDiff.Question question) {
        // 一个字段都没有的答案（{}）只可能出现在只有一边有的 question 上；说出来，别让它整节消失。
        if (question.fields().isEmpty()) {
            return List.of(new Line("(empty answer)", null));
        }
        return question.fields().stream()
                .map(row -> new Line(row.label(), dash(row.before()) + " → " + dash(row.after())))
                .toList();
    }

    /** 与 REC / inspect / list 同一个规则：上游解析出来的模型优先，没有就用请求的那个。 */
    private static String model(Cassette cassette) {
        String resolved = cassette.request().resolvedModel();
        return Objects.toString(resolved != null ? resolved : cassette.request().requestedModel(), "-");
    }

    private static String change(String before, String after) {
        return before.equals(after) ? before : before + " → " + after;
    }

    private static String dash(String value) {
        return Objects.toString(value, "-");
    }

    /** CLI 参数只把用户真的写了的那些交给配置合并，其余层级照常生效。 */
    private Map<String, String> cliOverrides() {
        Map<String, String> cli = new HashMap<>();
        if (cassetteDir != null) {
            cli.put("cassetteDir", cassetteDir);
        }
        return cli;
    }
}
