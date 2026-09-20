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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * replay 命令的端到端闭环：先对着 FakeJevServer 录，把上游关掉，再让 {@code jevtape replay} 用磁带应答 ——
 * 相同请求得到相同响应，输出 charter §57 的 Replay UX，而凭证既不进磁带也不进输出。
 */
class ReplayCommandTest {

    private static final String API_KEY = "sk-live-9f2c1d4b7a6e5840";

    private static final String REQUEST_BODY = """
            {"model":"jev-latest","state":{"ticket":{"id":"SUP-4821","subject":"Cannot export invoice PDF"}},\
            "questions":{"route":{"type":"Choice","instructions":"Choose the team.",\
            "options":[{"value":"billing","criteria":"Invoices."}]}}}""";

    private static final String RESPONSE_BODY =
            "{\"model\":\"jev-1.13.0\",\"answers\":{\"route\":{\"choice\":\"billing\"}}}";

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void replaysRecordedDecisionsOfflineAndPrintsTheReplayUx() throws Exception {
        Path cassettes = dir.resolve("cassettes");
        record(cassettes, REQUEST_BODY, RESPONSE_BODY);
        record(cassettes, REQUEST_BODY.replace("SUP-4821", "SUP-9999"),
                RESPONSE_BODY.replace("billing", "technical"));

        int port = freePort();
        URI target = URI.create("http://127.0.0.1:" + port + "/v1/systemone");
        StringBuffer out = new StringBuffer();
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        CommandLine commandLine = new CommandLine(new ReplayCommand())
                .setOut(printer(out))
                .setErr(printer(new StringBuffer()));
        Thread cli = Thread.ofVirtual().start(() -> exitCode.set(commandLine.execute(
                "--listen", "127.0.0.1",
                "--port", String.valueOf(port),
                "--cassette-dir", cassettes.toString(),
                "--config", dir.resolve("absent-config.json").toString())));

        try {
            await(out, "Waiting for Jev requests...");
            String nl = System.lineSeparator();
            assertThat(out.toString())
                    .contains("JevTape REPLAY")
                    .contains("2 cassettes loaded")
                    .contains("Listening:" + nl + "http://127.0.0.1:" + port)
                    .contains("Network:" + nl + "OFF")
                    .doesNotContain("Upstream");

            HttpResponse<byte[]> hit = post(target, REQUEST_BODY);
            await(out, "HIT  systemone-");
            assertThat(hit.statusCode()).isEqualTo(200);
            assertThat(hit.body()).isEqualTo(RESPONSE_BODY.getBytes(StandardCharsets.UTF_8));
            assertThat(out.toString()).containsPattern("HIT  systemone-[0-9a-f]{8}" + nl
                    + "     sha256:[0-9a-f]{64}" + nl
                    + "     \\d+ ms");

            // 从没录过的请求：MISS，而且默认策略下没有任何上游可以 fallback。
            HttpResponse<byte[]> miss = post(target, REQUEST_BODY.replace("Cannot export invoice PDF", "Cannot upload"));
            await(out, "MISS");
            assertThat(miss.statusCode()).isEqualTo(502);
            assertThat(new String(miss.body(), StandardCharsets.UTF_8)).contains("ReplayMiss");

            assertThat(out.toString()).doesNotContainIgnoringCase(API_KEY, "bearer", "authorization", "cookie");
        } finally {
            cli.interrupt();
            cli.join(Duration.ofSeconds(10).toMillis());
        }

        assertThat(cli.isAlive()).isFalse();
        assertThat(exitCode.get()).isZero();
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

    /** 走真实的 record 路径产出 cassette，然后把上游关掉 —— replay 期间物理上没有上游可去。 */
    private static void record(Path cassettes, String requestBody, String responseBody) {
        List<Cassette> recorded = new ArrayList<>();
        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.stub(200, Map.of("Content-Type", "application/json"),
                    responseBody.getBytes(StandardCharsets.UTF_8));
            new RecordingJevTransport(new LiveJevTransport(upstream.baseUrl(), Duration.ofSeconds(10)),
                    new FileCassetteRepository(cassettes), "0.1.0-test", recorded::add)
                    .send(new JevRequest("POST", "/v1/systemone",
                            Map.of("Authorization", List.of("Bearer " + API_KEY)),
                            requestBody.getBytes(StandardCharsets.UTF_8)));
        }
        assertThat(recorded).hasSize(1);
    }

    private HttpResponse<byte[]> post(URI target, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(target)
                        .method("POST", HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + API_KEY)
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
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
