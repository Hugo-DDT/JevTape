package io.jevtape.cli;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.config.ConfigLoader;
import io.jevtape.config.JevTapeConfig;
import io.jevtape.contract.JevProtocolAdapter;
import io.jevtape.server.JevProxyServer;
import io.jevtape.shared.JevTapeVersion;
import io.jevtape.transport.LiveJevTransport;
import io.jevtape.transport.RecordingJevTransport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code jevtape record}：启动本地代理，把经过它的每一次 Jev 调用录成 cassette（charter §15, §56）。
 *
 * <p>这一层只做参数解析、装配与输出：配置合并交给 {@link ConfigLoader}，转发交给
 * {@link LiveJevTransport}，脱敏与落盘交给 {@link RecordingJevTransport}，HTTP 进出交给
 * {@link JevProxyServer}。命令本身一直阻塞到进程被中断。
 */
@Command(name = "record",
        mixinStandardHelpOptions = true,
        description = "Record Jev decisions into a cassette.")
final class RecordCommand implements Runnable {

    private static final Path DEFAULT_CONFIG_FILE = Path.of(".jevtape", "config.json");

    @Spec
    private CommandSpec spec;

    @Option(names = "--listen", description = "Address the local proxy listens on.")
    private String listen;

    @Option(names = {"-p", "--port"}, description = "Port the local proxy listens on.")
    private Integer port;

    @Option(names = {"-d", "--cassette-dir"}, description = "Directory cassettes are written to.")
    private String cassetteDir;

    @Option(names = "--config", description = "Path of config.json (default: .jevtape/config.json).")
    private Path configFile;

    /** 上游地址。默认是真实的 Jev API；测试注入 FakeJevServer，于是构建全程不联网、不需要凭证。 */
    private final URI upstream;

    RecordCommand() {
        this(LiveJevTransport.DEFAULT_BASE_URL);
    }

    RecordCommand(URI upstream) {
        this.upstream = upstream;
    }

    @Override
    public void run() {
        JevTapeConfig config = new ConfigLoader().load(cliOverrides(), System.getenv(),
                configFile == null ? DEFAULT_CONFIG_FILE : configFile);
        Path cassettes = Path.of(config.cassetteDir());
        RecordingJevTransport transport = new RecordingJevTransport(
                new LiveJevTransport(upstream, LiveJevTransport.DEFAULT_TIMEOUT),
                new FileCassetteRepository(cassettes),
                JevTapeVersion.current(),
                cassette -> render(cassettes, cassette));

        try (JevProxyServer proxy = new JevProxyServer(transport, config.listen(), config.port())) {
            PrintWriter out = spec.commandLine().getOut();
            out.println("JevTape RECORD");
            out.println();
            out.println("Listening:");
            out.println(proxy.baseUrl());
            out.println();
            out.println("Upstream:");
            out.println(upstream);
            out.println();
            out.println("Cassette directory:");
            out.println(cassettes);
            out.println();
            out.println("Waiting for Jev requests...");
            out.flush();
            awaitShutdown();
        }
    }

    /** 渲染一个 REC 块（charter §56）。它在代理线程上跑，因此整块输出要原子。 */
    private synchronized void render(Path cassettes, Cassette cassette) {
        PrintWriter out = spec.commandLine().getOut();
        out.println();
        out.println("REC  " + cassette.name());
        for (String question : JevProtocolAdapter.questionSummaries(cassette.request().questions())) {
            out.println("     " + question);
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
