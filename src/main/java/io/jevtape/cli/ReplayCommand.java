package io.jevtape.cli;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.config.ConfigLoader;
import io.jevtape.config.JevTapeConfig;
import io.jevtape.config.MissPolicy;
import io.jevtape.matching.MatchResult;
import io.jevtape.matching.MissDiagnosis;
import io.jevtape.server.JevProxyServer;
import io.jevtape.shared.JevTapeVersion;
import io.jevtape.transport.JevTransport;
import io.jevtape.transport.LiveJevTransport;
import io.jevtape.transport.MissFallbackJevTransport;
import io.jevtape.transport.RecordingJevTransport;
import io.jevtape.transport.ReplayJevTransport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code jevtape replay}：启动本地代理，用已录制的 cassette 应答每一次 Jev 调用（charter §16, §57）。
 *
 * <p>这一层只做参数解析、装配与输出：配置合并交给 {@link ConfigLoader}，装载交给
 * {@link FileCassetteRepository}，匹配与应答交给 {@link ReplayJevTransport}，HTTP 进出交给
 * {@link JevProxyServer}。命令本身一直阻塞到进程被中断。
 *
 * <p>默认的 miss 策略是 error，此时装配出来的 transport 链里没有任何指向上游的东西，离线是构造出来的事实
 * （charter §58）。只有用户显式写下 {@code --on-miss live} 或 {@code --on-miss record}，上游才会被
 * {@link MissFallbackJevTransport} 接进来 —— 那时 banner 的 {@code Network} 一行也会跟着变成 ON。
 */
@Command(name = "replay",
        mixinStandardHelpOptions = true,
        description = "Replay recorded Jev decisions from cassettes; offline unless --on-miss asks for the live API.")
final class ReplayCommand implements Runnable {

    private static final Path DEFAULT_CONFIG_FILE = Path.of(".jevtape", "config.json");

    @Spec
    private CommandSpec spec;

    @Option(names = "--listen", description = "Address the local proxy listens on.")
    private String listen;

    @Option(names = {"-p", "--port"}, description = "Port the local proxy listens on.")
    private Integer port;

    @Option(names = {"-d", "--cassette-dir"}, description = "Directory cassettes are read from.")
    private String cassetteDir;

    @Option(names = "--on-miss",
            description = "What to do when no cassette matches: error (default), live, record."
                    + " live and record call the real Jev API.")
    private String onMiss;

    @Option(names = "--config", description = "Path of config.json (default: .jevtape/config.json).")
    private Path configFile;

    /** 上游地址，只有 miss 策略是 live / record 时才会用到；默认策略下它根本不会被读。测试注入 FakeJevServer。 */
    private final URI upstream;

    ReplayCommand() {
        this(LiveJevTransport.DEFAULT_BASE_URL);
    }

    ReplayCommand(URI upstream) {
        this.upstream = upstream;
    }

    @Override
    public void run() {
        JevTapeConfig config = new ConfigLoader().load(cliOverrides(), System.getenv(),
                configFile == null ? DEFAULT_CONFIG_FILE : configFile);
        Path cassettes = Path.of(config.cassetteDir());
        List<Cassette> loaded = new FileCassetteRepository(cassettes).loadAll();

        try (JevProxyServer proxy = new JevProxyServer(transport(config, cassettes, loaded),
                config.listen(), config.port())) {
            PrintWriter out = spec.commandLine().getOut();
            out.println("JevTape REPLAY");
            out.println();
            out.println(loaded.size() + (loaded.size() == 1 ? " cassette loaded" : " cassettes loaded"));
            out.println();
            out.println("Listening:");
            out.println(proxy.baseUrl());
            out.println();
            out.println("Network:");
            out.println(network(config.onMiss()));
            out.println();
            out.println("Waiting for Jev requests...");
            out.flush();
            awaitShutdown();
        }
    }

    /**
     * 按 miss 策略装配 transport（charter §33）。error 就是 {@link ReplayJevTransport} 本身 —— 没有装饰器、
     * 没有上游客户端，因此"默认不联网"不需要任何分支去保证。
     *
     * <p>ponytail: 补录的 cassette 不会被本次运行重新装载，于是 {@code --on-miss record} 下同一个未录制的请求
     * 每来一次就走一次上游（并覆盖同名文件）；天花板是"录完当次即可命中"，届时让 replay 持有 cassette 的
     * 供给方而不是一份快照即可。
     */
    private JevTransport transport(JevTapeConfig config, Path cassettes, List<Cassette> loaded) {
        MissPolicy policy = config.onMiss();
        ReplayJevTransport replay = new ReplayJevTransport(loaded, result -> render(result, policy));
        return switch (policy) {
            case ERROR -> replay;
            case LIVE -> new MissFallbackJevTransport(replay,
                    new LiveJevTransport(upstream, LiveJevTransport.DEFAULT_TIMEOUT));
            case RECORD -> new MissFallbackJevTransport(replay, new RecordingJevTransport(
                    new LiveJevTransport(upstream, LiveJevTransport.DEFAULT_TIMEOUT),
                    new FileCassetteRepository(cassettes),
                    JevTapeVersion.current(),
                    cassette -> renderRecorded(cassettes, cassette)));
        };
    }

    /** banner 里这一行必须说真话：默认策略下物理上没有上游，显式启用后只有 MISS 才会联网。 */
    private String network(MissPolicy policy) {
        return switch (policy) {
            case ERROR -> "OFF";
            case LIVE -> "ON — only on miss, forwarded to " + upstream;
            case RECORD -> "ON — only on miss, forwarded to " + upstream + " and saved";
        };
    }

    /**
     * 渲染一个 HIT 或 MISS 块（charter §57），排版与 REC 块对齐。它在代理线程上跑，因此整块输出要原子。
     *
     * <p>HIT 里的耗时是**录制那一次**决策的耗时，不是回放耗时 —— 回放不走网络，量它没有信息量。MISS 里逐项
     * 列出最接近那个 cassette 的对比（charter §32），开发者应当一眼看出是 state 变了、question 变了还是
     * model 变了；一个候选都没有时没有可比的东西，只留 replay key。
     */
    private synchronized void render(MatchResult result, MissPolicy policy) {
        PrintWriter out = spec.commandLine().getOut();
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
                if (policy != MissPolicy.ERROR) {
                    // 联网这件事必须说出来：开发者不该从一片安静的输出里猜"刚刚是不是偷偷请求了线上"。
                    out.println("     forwarded " + upstream);
                }
            }
        }
        out.flush();
    }

    /** {@code --on-miss record} 补录出来的 cassette，用的是与 {@code jevtape record} 完全相同的 REC 块。 */
    private synchronized void renderRecorded(Path cassettes, Cassette cassette) {
        RecBlock.print(spec.commandLine().getOut(), cassettes, cassette);
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
        if (onMiss != null) {
            cli.put("onMiss", onMiss);
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
