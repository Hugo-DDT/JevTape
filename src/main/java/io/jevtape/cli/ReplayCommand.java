package io.jevtape.cli;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.config.ConfigLoader;
import io.jevtape.config.JevTapeConfig;
import io.jevtape.matching.MatchResult;
import io.jevtape.server.JevProxyServer;
import io.jevtape.transport.ReplayJevTransport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code jevtape replay}：启动本地代理，用已录制的 cassette 应答每一次 Jev 调用，全程离线（charter §16, §57）。
 *
 * <p>这一层只做参数解析、装配与输出：配置合并交给 {@link ConfigLoader}，装载交给
 * {@link FileCassetteRepository}，匹配与应答交给 {@link ReplayJevTransport}，HTTP 进出交给
 * {@link JevProxyServer}。命令本身一直阻塞到进程被中断。
 *
 * <p>注意这里没有上游地址可传 —— {@link ReplayJevTransport} 根本不接收上游客户端，所以离线是装配出来的
 * 事实，不需要命令行去保证（charter §58）。
 */
@Command(name = "replay",
        mixinStandardHelpOptions = true,
        description = "Replay recorded Jev decisions from cassettes, fully offline.")
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

    @Option(names = "--config", description = "Path of config.json (default: .jevtape/config.json).")
    private Path configFile;

    @Override
    public void run() {
        JevTapeConfig config = new ConfigLoader().load(cliOverrides(), System.getenv(),
                configFile == null ? DEFAULT_CONFIG_FILE : configFile);
        List<Cassette> loaded = new FileCassetteRepository(Path.of(config.cassetteDir())).loadAll();
        ReplayJevTransport transport = new ReplayJevTransport(loaded, this::render);

        try (JevProxyServer proxy = new JevProxyServer(transport, config.listen(), config.port())) {
            PrintWriter out = spec.commandLine().getOut();
            out.println("JevTape REPLAY");
            out.println();
            out.println(loaded.size() + (loaded.size() == 1 ? " cassette loaded" : " cassettes loaded"));
            out.println();
            out.println("Listening:");
            out.println(proxy.baseUrl());
            out.println();
            out.println("Network:");
            out.println("OFF");
            out.println();
            out.println("Waiting for Jev requests...");
            out.flush();
            awaitShutdown();
        }
    }

    /**
     * 渲染一个 HIT 或 MISS 块（charter §57），排版与 REC 块对齐。它在代理线程上跑，因此整块输出要原子。
     *
     * <p>HIT 里的耗时是**录制那一次**决策的耗时，不是回放耗时 —— 回放不走网络，量它没有信息量。
     * MISS 的诊断对比（State / Contract / Model）属于 miss 策略，见路线图任务 10。
     */
    private synchronized void render(MatchResult result) {
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
            }
        }
        out.flush();
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
