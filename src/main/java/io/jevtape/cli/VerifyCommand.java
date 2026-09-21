package io.jevtape.cli;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.config.ConfigLoader;
import io.jevtape.config.JevTapeConfig;
import io.jevtape.contract.ContractDiff;
import io.jevtape.contract.JevProtocolAdapter;
import io.jevtape.fingerprint.FingerprintEngine;
import io.jevtape.shared.ConfigurationError;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

import static io.jevtape.cli.Report.Line;
import static io.jevtape.cli.Report.section;

/**
 * {@code jevtape verify <name> --request <file>}：把当前请求的 Decision Contract 与 cassette 录下来的那一份
 * 逐项对比（charter §18）。这是 JevTape 区别于普通 HTTP VCR 的地方 —— 它能说出"哪一条 criteria 变了"，
 * 而不只是"请求变了"。
 *
 * <p>这一层只做参数解析与渲染：契约的形状与比较在 {@link ContractDiff}，Jev 字段的取值在
 * {@link JevProtocolAdapter}，指纹在 {@link FingerprintEngine}，因此这里不出现任何 System One 的字段名。
 *
 * <p>退出码就是 CI 的闸门：FAIL 是 1，PASS / WARN 是 0。报告无论如何都打全 —— 闸门拦下来之后，得能就地
 * 看清是哪一条变了。整个过程只读本地文件，不碰网络。
 */
@Command(name = "verify",
        mixinStandardHelpOptions = true,
        description = "Compare the Decision Contract of a current request against the one a cassette recorded."
                + " Exits 1 on FAIL, 0 on PASS or WARN.")
final class VerifyCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", paramLabel = "<name>",
            description = "Cassette name, without the directory or the .json suffix.")
    private String name;

    @Option(names = {"-r", "--request"}, required = true, paramLabel = "<file>",
            description = "JSON file holding the current System One request: {model, state, questions}.")
    private Path requestFile;

    @Option(names = {"-d", "--cassette-dir"}, description = "Directory the cassette is read from.")
    private String cassetteDir;

    @Option(names = "--config", description = "Path of config.json (default: .jevtape/config.json).")
    private Path configFile;

    @Override
    public Integer call() {
        JevTapeConfig config = new ConfigLoader().load(cliOverrides(), System.getenv(),
                configFile == null ? ConfigLoader.DEFAULT_FILE : configFile);
        Cassette cassette = new FileCassetteRepository(Path.of(config.cassetteDir())).read(name);
        JevProtocolAdapter.Decision decision = JevProtocolAdapter.parseRequest(readRequest());

        // 录制那一份指纹取自 cassette：它是 replay 真正会去比的那一个，重算一遍只会掩盖磁带被手改过的事实。
        ContractDiff diff = ContractDiff.between(
                cassette.fingerprints().contract(),
                FingerprintEngine.contract(decision.questions()),
                JevProtocolAdapter.decisionContract(cassette.request().questions()),
                JevProtocolAdapter.decisionContract(decision.questions()));

        List<String> output = new ArrayList<>();
        section(output, "Verify", List.of(
                new Line("Verdict", diff.status().name()),
                new Line("Cassette", cassette.name()),
                new Line("Request", requestFile.toString())));
        section(output, "Changes", changes(diff));
        section(output, "Contract", List.of(
                new Line("Recorded", dash(diff.recordedContract())),
                new Line("Current", dash(diff.currentContract()))));

        PrintWriter out = spec.commandLine().getOut();
        out.println(String.join(System.lineSeparator(), output));
        out.flush();
        return diff.status() == ContractDiff.Status.FAIL ? 1 : 0;
    }

    /**
     * 变化按 question 分组：组名一行，组内一行一条变化，符号是 {@code +} 新增、{@code -} 删除、{@code ~}
     * 改动（charter §18）。criteria 正文不打出来 —— 它可以很长，会把真正要看的东西挤出屏幕。
     */
    private static List<Line> changes(ContractDiff diff) {
        if (diff.changes().isEmpty()) {
            // 指纹不等却没有任何可比的变化：说清楚这不是"没变"，而是变得这个模型不认识。
            return diff.status() == ContractDiff.Status.FAIL
                    ? List.of(new Line("changed outside the fields JevTape models", null))
                    : List.of();
        }
        List<Line> lines = new ArrayList<>();
        String group = null;
        for (ContractDiff.Change change : diff.changes()) {
            if (!change.question().equals(group)) {
                if (group != null) {
                    lines.add(Line.BLANK);
                }
                group = change.question();
                lines.add(new Line(group, null));
            }
            lines.add(new Line("  " + symbol(change.kind()) + " " + change.subject(), detail(change)));
        }
        return lines;
    }

    private static String symbol(ContractDiff.Change.Kind kind) {
        return switch (kind) {
            case QUESTION_ADDED, CRITERION_ADDED -> "+";
            case QUESTION_REMOVED, CRITERION_REMOVED -> "-";
            default -> "~";
        };
    }

    /** 只有类型与顺序值得把两侧都打出来；其余变化打完"哪一条变了"就够了。 */
    private static String detail(ContractDiff.Change change) {
        return change.before() == null ? null : change.before() + " → " + change.after();
    }

    private static String dash(String value) {
        return Objects.toString(value, "-");
    }

    /** 请求文件的路径是用户给的，读不到就是参数问题，与 cassette 的存储失败分开说。 */
    private byte[] readRequest() {
        try {
            return Files.readAllBytes(requestFile);
        } catch (NoSuchFileException e) {
            throw new ConfigurationError("No request file " + requestFile, e);
        } catch (IOException e) {
            throw new ConfigurationError("Cannot read request file " + requestFile, e);
        }
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
