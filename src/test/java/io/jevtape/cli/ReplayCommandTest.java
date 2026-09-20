package io.jevtape.cli;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.testing.FakeJevServer;
import io.jevtape.transport.JevRequest;
import io.jevtape.transport.LiveJevTransport;
import io.jevtape.transport.RecordingJevTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * replay 命令的端到端闭环：先对着 FakeJevServer 录，把上游关掉，再让 {@code jevtape replay} 用磁带应答 ——
 * 相同请求得到相同响应，MISS 时输出诊断（charter §32, §57），而凭证既不进磁带也不进输出。
 *
 * <p>miss 策略的两个非默认取值各自需要一个活的上游，因此它们在 FakeJevServer 还在的时候跑；默认策略那一个
 * 用的是 {@code new ReplayCommand()}，上游地址是真实的 Jev API，可它根本不会被装配进 transport 链 ——
 * MISS 得到的是 {@code JEVTAPE_REPLAY_MISS}，而不是一次联网的尝试。
 */
class ReplayCommandTest {

    private static final String API_KEY = "sk-live-9f2c1d4b7a6e5840";

    private static final String REQUEST_BODY = """
            {"model":"jev-latest","state":{"ticket":{"id":"SUP-4821","subject":"Cannot export invoice PDF"}},\
            "questions":{"route":{"type":"Choice","instructions":"Choose the team.",\
            "options":[{"value":"billing","criteria":"Invoices."}]}}}""";

    private static final String RESPONSE_BODY =
            "{\"model\":\"jev-1.13.0\",\"answers\":{\"route\":{\"choice\":\"billing\"}}}";

    /** 改了 state 里的一个字段：contract 与 model 都没动。 */
    private static final String CHANGED_STATE_BODY = REQUEST_BODY.replace("Cannot export invoice PDF", "Cannot upload");

    /** 改了 question 的 instructions：state 与 model 都没动。 */
    private static final String CHANGED_QUESTION_BODY = REQUEST_BODY.replace("Choose the team.", "Pick the team.");

    private static final String LIVE_BODY =
            "{\"model\":\"jev-1.13.0\",\"answers\":{\"route\":{\"choice\":\"security\"}}}";

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void replaysRecordedDecisionsOfflineAndDiagnosesEveryMiss() throws Exception {
        Path cassettes = dir.resolve("cassettes");
        record(cassettes, REQUEST_BODY, RESPONSE_BODY);
        record(cassettes, REQUEST_BODY.replace("SUP-4821", "SUP-9999"),
                RESPONSE_BODY.replace("billing", "technical"));

        try (Running replay = start(new ReplayCommand(), cassettes, freePort())) {
            String nl = System.lineSeparator();
            assertThat(replay.out().toString())
                    .contains("JevTape REPLAY")
                    .contains("2 cassettes loaded")
                    .contains("Listening:" + nl + "http://127.0.0.1:" + replay.port())
                    .contains("Network:" + nl + "OFF")
                    .doesNotContain("Upstream");

            HttpResponse<byte[]> hit = post(replay.target(), REQUEST_BODY);
            await(replay.out(), "HIT  systemone-");
            assertThat(hit.statusCode()).isEqualTo(200);
            assertThat(hit.body()).isEqualTo(RESPONSE_BODY.getBytes(StandardCharsets.UTF_8));
            assertThat(replay.out().toString()).containsPattern("HIT  systemone-[0-9a-f]{8}" + nl
                    + "     sha256:[0-9a-f]{64}" + nl
                    + "     \\d+ ms");

            // state 变了的请求：MISS，诊断指名 state。
            HttpResponse<byte[]> stateMiss = post(replay.target(), CHANGED_STATE_BODY);
            await(replay.out(), "state     CHANGED");
            assertThat(stateMiss.statusCode()).isEqualTo(502);
            assertThat(new String(stateMiss.body(), StandardCharsets.UTF_8))
                    .contains("JEVTAPE_REPLAY_MISS")
                    .contains("state CHANGED, contract MATCH, model MATCH");
            assertThat(replay.out().toString()).containsPattern("MISS" + nl
                    + "     sha256:[0-9a-f]{64}" + nl
                    + "     closest   systemone-[0-9a-f]{8}" + nl
                    + "     state     CHANGED" + nl
                    + "     contract  MATCH" + nl
                    + "     model     MATCH" + nl);

            // contract 变了的请求：MISS，诊断指名 contract —— 任务 10 的验收。
            post(replay.target(), CHANGED_QUESTION_BODY);
            await(replay.out(), "contract  CHANGED");
            assertThat(replay.out().toString())
                    .doesNotContain("forwarded");

            assertThat(replay.out().toString())
                    .doesNotContainIgnoringCase(API_KEY, "bearer", "authorization", "cookie");
        }
    }

    @Test
    void missPolicyLiveForwardsOnlyTheMissesAndSavesNothing() throws Exception {
        Path cassettes = dir.resolve("cassettes");
        record(cassettes, REQUEST_BODY, RESPONSE_BODY);

        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.stub(200, Map.of("Content-Type", "application/json"), bytes(LIVE_BODY));
            try (Running replay = start(new ReplayCommand(upstream.baseUrl()), cassettes, freePort(),
                    "--on-miss", "live")) {
                assertThat(replay.out().toString()).contains("Network:").contains("ON — only on miss");

                HttpResponse<byte[]> hit = post(replay.target(), REQUEST_BODY);
                await(replay.out(), "HIT  systemone-");
                assertThat(hit.body()).isEqualTo(bytes(RESPONSE_BODY));

                HttpResponse<byte[]> miss = post(replay.target(), CHANGED_QUESTION_BODY);
                await(replay.out(), "forwarded " + upstream.baseUrl());
                assertThat(miss.statusCode()).isEqualTo(200);
                assertThat(miss.body()).isEqualTo(bytes(LIVE_BODY));

                // 命中那一次不该出网，只有 MISS 出了；live 策略不写磁带。
                assertThat(upstream.received()).hasSize(1);
                assertThat(cassetteNames(cassettes)).hasSize(1);
            }
        }
    }

    @Test
    void missPolicyRecordForwardsTheMissAndSavesItAsACassette() throws Exception {
        Path cassettes = dir.resolve("cassettes");
        record(cassettes, REQUEST_BODY, RESPONSE_BODY);

        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.stub(200, Map.of("Content-Type", "application/json"), bytes(LIVE_BODY));
            try (Running replay = start(new ReplayCommand(upstream.baseUrl()), cassettes, freePort(),
                    "--on-miss", "record")) {
                HttpResponse<byte[]> miss = post(replay.target(), CHANGED_QUESTION_BODY);
                await(replay.out(), "REC  systemone-");
                assertThat(miss.body()).isEqualTo(bytes(LIVE_BODY));
                assertThat(replay.out().toString()).contains("Network:").contains("and saved");

                // 补录出来的是一份正常的 v1 cassette，录的正是那个 MISS 的请求，因此下一次 replay 就能命中它。
                assertThat(cassetteNames(cassettes)).hasSize(2);
                assertThat(new FileCassetteRepository(cassettes).loadAll())
                        .anySatisfy(cassette -> assertThat(cassette.request().questions().toString())
                                .contains("Pick the team."));
            }
        }
    }

    @Test
    void anUnknownMissPolicyIsRejectedBeforeAnythingStartsListening() {
        StringWriter err = new StringWriter();

        int exitCode = JevTapeCli.commandLine()
                .setOut(new PrintWriter(new StringWriter()))
                .setErr(new PrintWriter(err))
                .execute("replay",
                        "--cassette-dir", dir.resolve("cassettes").toString(),
                        "--on-miss", "ignore",
                        "--config", dir.resolve("absent-config.json").toString());

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString())
                .contains("jevtape: onMiss must be one of [error, live, record]")
                .doesNotContain("\tat ");
    }

    @Test
    void replayWithoutACassetteDirectoryFailsInsteadOfServingMisses() {
        StringWriter err = new StringWriter();

        // 走真正的入口，于是断言的是用户实际看到的那一行，而不是 picocli 的默认堆栈渲染。
        int exitCode = JevTapeCli.commandLine()
                .setOut(new PrintWriter(new StringWriter()))
                .setErr(new PrintWriter(err))
                .execute("replay",
                        "--cassette-dir", dir.resolve("absent").toString(),
                        "--config", dir.resolve("absent-config.json").toString());

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString())
                .contains("jevtape: No cassette directory")
                .doesNotContain("\tat ");
    }

    /** 一次跑起来的 {@code jevtape replay}；关掉它就是中断那根一直阻塞着的 CLI 线程。 */
    private record Running(StringBuffer out, Thread cli, AtomicInteger exitCode, URI target, int port)
            implements AutoCloseable {

        @Override
        public void close() throws InterruptedException {
            cli.interrupt();
            cli.join(Duration.ofSeconds(10).toMillis());
            assertThat(cli.isAlive()).isFalse();
            assertThat(exitCode).hasValue(0);
        }
    }

    private Running start(ReplayCommand command, Path cassettes, int port, String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--listen", "127.0.0.1",
                "--port", String.valueOf(port),
                "--cassette-dir", cassettes.toString(),
                "--config", dir.resolve("absent-config.json").toString()));
        args.addAll(List.of(extraArgs));

        StringBuffer out = new StringBuffer();
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        CommandLine commandLine = new CommandLine(command)
                .setOut(printer(out))
                .setErr(printer(new StringBuffer()));
        Thread cli = Thread.ofVirtual().start(() -> exitCode.set(commandLine.execute(args.toArray(String[]::new))));

        Running running = new Running(out, cli, exitCode,
                URI.create("http://127.0.0.1:" + port + "/v1/systemone"), port);
        await(out, "Waiting for Jev requests...");
        return running;
    }

    /** 走真实的 record 路径产出 cassette，然后把上游关掉 —— replay 期间物理上没有上游可去。 */
    private static void record(Path cassettes, String requestBody, String responseBody) {
        List<Cassette> recorded = new ArrayList<>();
        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.stub(200, Map.of("Content-Type", "application/json"), bytes(responseBody));
            new RecordingJevTransport(new LiveJevTransport(upstream.baseUrl(), Duration.ofSeconds(10)),
                    new FileCassetteRepository(cassettes), "0.1.0-test", recorded::add)
                    .send(new JevRequest("POST", "/v1/systemone",
                            Map.of("Authorization", List.of("Bearer " + API_KEY)),
                            bytes(requestBody)));
        }
        assertThat(recorded).hasSize(1);
    }

    private static List<String> cassetteNames(Path cassettes) throws IOException {
        try (Stream<Path> files = Files.list(cassettes)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private HttpResponse<byte[]> post(URI target, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(target)
                        .method("POST", HttpRequest.BodyPublishers.ofByteArray(bytes(body)))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + API_KEY)
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /** 轮询代理线程写进来的输出；超时就把已经拿到的部分连同失败一起报出来。 */
    private static void await(StringBuffer out, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (out.toString().contains(expected)) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("jevtape replay never printed '%s'. Output so far:%n%s", expected, out);
    }

    /** PrintWriter 直写 StringBuffer，于是测试线程能立刻读到代理线程的输出。 */
    private static PrintWriter printer(StringBuffer sink) {
        return new PrintWriter(new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) {
                sink.append(buffer, offset, length);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        }, true);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
