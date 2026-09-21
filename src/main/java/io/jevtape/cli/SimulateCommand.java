package io.jevtape.cli;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.config.ConfigLoader;
import io.jevtape.config.JevTapeConfig;
import io.jevtape.contract.AnswerOverrides;
import io.jevtape.contract.JevProtocolAdapter;
import io.jevtape.matching.MatchResult;
import io.jevtape.server.JevProxyServer;
import io.jevtape.shared.ConfigurationError;
import io.jevtape.transport.ReplayJevTransport;
import io.jevtape.transport.SimulatingJevTransport;
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

import static io.jevtape.cli.Report.Line;
import static io.jevtape.cli.Report.section;

/**
 * {@code jevtape simulate [<name>] [injections]}：用磁带应答，但把应答改成一个边界场景（charter §11, §20）——
 * 压一段 latency、换成 429 / 500 / 529、把 confidence 压到应用的阈值之下、换一个 choice、改一个 score 或
 * Noul 的 probability。稳定地制造出平时碰不到的场景，正是录制回放之外的那半个理由。
 *
 * <p>这一层只做参数解析、装配与输出：注入的语义在 {@link SimulatingJevTransport}，应答字段的改写在
 * {@link JevProtocolAdapter}，HIT / MISS 的排版与 {@code replay} 共用 {@link MatchBlock}。
 *
 * <p>不给 {@code <name>} 时服务目录里的全部磁带 —— 只服务一盘的话，应用其余的 Jev 调用都会 MISS，测到的就
 * 是"没录这个请求"而不是想要注入的那个故障。
 *
 * <p>{@code Network} 一行永远是 OFF，这不是判断出来的：装配里只有 replay 与它外面的注入装饰器，没有任何
 * 指向上游的东西（不变量 1、2）。也因此 {@code onMiss} 在这里不参与合并 —— simulate 没有 live 可回退。
 */
@Command(name = "simulate",
        mixinStandardHelpOptions = true,
        description = "Replay cassettes with injected latency, HTTP status or answer overrides; always offline.")
final class SimulateCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<name>",
            description = "Cassette to serve. Without it every cassette in the directory is served.")
    private String name;

    @Option(names = "--listen", description = "Address the local proxy listens on.")
    private String listen;

    @Option(names = {"-p", "--port"}, description = "Port the local proxy listens on.")
    private Integer port;

    @Option(names = {"-d", "--cassette-dir"}, description = "Directory cassettes are read from.")
    private String cassetteDir;

    @Option(names = "--config", description = "Path of config.json (default: .jevtape/config.json).")
    private Path configFile;

    @Option(names = "--latency", paramLabel = "<ms>",
            description = "Hold every response back this long. Set it above the client's own timeout to"
                    + " simulate a timeout the client really experiences.")
    private Long latency;

    @Option(names = "--http-status", paramLabel = "<code>",
            description = "Answer with this status instead of the recorded one, e.g. 429, 500, 529."
                    + " The recorded body is served unchanged.")
    private Integer httpStatus;

    @Option(names = "--question", paramLabel = "<name>",
            description = "Override the answer of this question only (default: every answer).")
    private String question;

    @Option(names = "--confidence", paramLabel = "<value>",
            description = "Report this confidence instead of the recorded one.")
    private Double confidence;

    @Option(names = "--choice", paramLabel = "<value>",
            description = "Report this choice instead of the recorded one.")
    private String choice;

    @Option(names = "--score", paramLabel = "<value>",
            description = "Report this score instead of the recorded one.")
    private Double score;

    @Option(names = "--probability", paramLabel = "<value>",
            description = "Report this Noul probability instead of the recorded one.")
    private Double probability;

    @Override
    public void run() {
        JevTapeConfig config = new ConfigLoader().load(cliOverrides(), System.getenv(),
                configFile == null ? ConfigLoader.DEFAULT_FILE : configFile);
        List<Cassette> loaded = load(config);
        long delay = latency();
        Integer status = status();
        AnswerOverrides overrides = new AnswerOverrides(question, confidence, choice, score, probability);
        // 落在任何一个答案上都没有的覆盖是配置错，不是"什么都不改"，因此在开始监听之前就拦下来。
        requireApplicable(overrides, loaded);

        try (JevProxyServer proxy = new JevProxyServer(new SimulatingJevTransport(
                new ReplayJevTransport(loaded, this::render), delay, status, overrides),
                config.listen(), config.port())) {

            List<String> output = new ArrayList<>(List.of(
                    "JevTape SIMULATE",
                    "",
                    loaded.size() + (loaded.size() == 1 ? " cassette loaded" : " cassettes loaded"),
                    "",
                    "Listening:",
                    proxy.baseUrl().toString(),
                    "",
                    "Network:",
                    "OFF"));
            section(output, "Injected", injected(delay, status, overrides));
            output.add("");
            output.add("Waiting for Jev requests...");

            PrintWriter out = spec.commandLine().getOut();
            out.println(String.join(System.lineSeparator(), output));
            out.flush();
            awaitShutdown();
        }
    }

    /** 不给名字就服务全部磁带：注入要的是一整个应用跑起来的每一次 Jev 调用都撞上这个场景。 */
    private List<Cassette> load(JevTapeConfig config) {
        FileCassetteRepository repository = new FileCassetteRepository(Path.of(config.cassetteDir()));
        return name == null ? repository.loadAll() : List.of(repository.read(name));
    }

    private List<Line> injected(long delay, Integer status, AnswerOverrides overrides) {
        List<Line> lines = new ArrayList<>();
        if (delay > 0) {
            lines.add(new Line("latency", delay + " ms"));
        }
        if (status != null) {
            lines.add(new Line("http-status", String.valueOf(status)));
        }
        overrides.fields().forEach(field -> lines.add(new Line(field.name(), field.value())));
        if (overrides.question() != null) {
            lines.add(new Line("question", overrides.question()));
        }
        // 什么都没注入也要说一声：此时服务的是磁带原件，与 replay 没有区别，用户多半是漏写了参数。
        return lines.isEmpty() ? List.of(new Line("none", null)) : lines;
    }

    private static void requireApplicable(AnswerOverrides overrides, List<Cassette> loaded) {
        boolean applicable = loaded.stream()
                .anyMatch(cassette -> JevProtocolAdapter.canOverride(cassette.response().body(), overrides));
        if (!overrides.empty() && !applicable) {
            throw new ConfigurationError(describe(overrides) + " matches no answer in " + scope(loaded)
                    + " — only a field an answer already carries can be overridden");
        }
    }

    private static String describe(AnswerOverrides overrides) {
        return String.join(", ", overrides.fields().stream().map(AnswerOverrides.Field::toString).toList());
    }

    private static String scope(List<Cassette> loaded) {
        return loaded.size() == 1
                ? "cassette '" + loaded.getFirst().name() + "'"
                : "any of the " + loaded.size() + " loaded cassettes";
    }

    private long latency() {
        if (latency == null) {
            return 0;
        }
        if (latency < 0) {
            throw new ConfigurationError("--latency must not be negative, got " + latency + " ms");
        }
        return latency;
    }

    /** 范围外的状态码写不进 HTTP 响应行，与其让 JDK 抛一个看不懂的东西，不如在这里说清楚。 */
    private Integer status() {
        if (httpStatus == null) {
            return null;
        }
        if (httpStatus < 100 || httpStatus > 599) {
            throw new ConfigurationError("--http-status must be between 100 and 599, got " + httpStatus);
        }
        return httpStatus;
    }

    /** HIT / MISS 块与 replay 共用一份排版；simulate 没有上游，因此永远不会打 forwarded 那一行。 */
    private synchronized void render(MatchResult result) {
        MatchBlock.print(spec.commandLine().getOut(), result, null);
    }

    /** CLI 参数只把用户真的写了的那些交给配置合并，其余层级照常生效。 */
    private Map<String, String> cliOverrides() {
        Map<String, String> cli = new HashMap<>();
        if (listen != null) {
            cli.put("listen", listen);
        }
        if (port != null) {
            cli.put("port", String.valueOf(port));
        }
        if (cassetteDir != null) {
            cli.put("cassetteDir", cassetteDir);
        }
        return cli;
    }

    /** 一直等到被中断；Ctrl-C 时 JVM 直接退出，监听 socket 随进程释放。 */
    private static void awaitShutdown() {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
