package io.jevtape.cli;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.testing.FakeJevServer;
import io.jevtape.transport.JevRequest;
import io.jevtape.transport.JevResponse;
import io.jevtape.transport.LiveJevTransport;
import io.jevtape.transport.RecordingJevTransport;
import io.jevtape.transport.ReplayJevTransport;
import io.jevtape.shared.UpstreamTimeout;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * v0.1.0 验收闸门（roadmap 任务 11）。被验收的闭环是总纲 §64 那一行：
 *
 * <pre>
 * 真实（Fake）Jev 请求 → Record → Cassette → 断网 → 删除 API Key → Replay → 相同 Response
 * </pre>
 *
 * <p>这里的"断网"是物理事实：FakeJevServer 已经 close，回放期间上游进程根本不存在，任何一次出网尝试
 * 都会立刻失败而不是被悄悄重试；"删除 API Key"同样由回放请求不带任何凭证头体现。走的是真实 CLI 入口
 * （{@link RecordCommand} / {@link ReplayCommand}），于是被验收的是用户真正会跑的那条装配链。
 *
 * <p>状态矩阵（200/400/401/429/500/529/非法响应/timeout）由 FakeJevServer 的 {@code stub()} 与
 * {@code delay()} 两个旋钮驱动：错误状态码与上游的 HTML 错误页同属磁带，录得下也放得出。
 */
class MvpAcceptanceTest {

    private static final String API_KEY = "sk-live-9f2c1d4b7a6e5840";

    private static final String REQUEST_BODY = """
            {"model":"jev-latest","state":{"ticket":{"id":"SUP-4821","subject":"Cannot export invoice PDF",\
            "tags":["billing","pdf"]}},"questions":{"route":{"type":"Choice","instructions":"Choose the team.",\
            "options":[{"value":"billing","criteria":"Invoices."},{"value":"technical","criteria":"Bugs."}]},\
            "urgent":{"type":"Noul","instructions":"Is this ticket urgent?","criteria":"Blocked customer."}}}""";

    /** 与 REQUEST_BODY 语义完全相同，只是每一层 object 的 key 都倒序排列（array 保序）。 */
    private static final String REORDERED_BODY = """
            {"questions":{"urgent":{"criteria":"Blocked customer.","instructions":"Is this ticket urgent?",\
            "type":"Noul"},"route":{"options":[{"criteria":"Invoices.","value":"billing"},\
            {"criteria":"Bugs.","value":"technical"}],"instructions":"Choose the team.","type":"Choice"}},\
            "state":{"ticket":{"subject":"Cannot export invoice PDF","tags":["billing","pdf"],"id":"SUP-4821"}},\
            "model":"jev-latest"}""";

    private static final String RESPONSE_BODY =
            "{\"model\":\"jev-1.13.0\",\"answers\":{\"route\":{\"choice\":\"billing\"},\"urgent\":{\"probability\":0.91}}}";

    /** 改了 state 里的一个字段：contract 与 model 都没动。 */
    private static final String CHANGED_STATE_BODY = REQUEST_BODY.replace("Cannot export invoice PDF", "Cannot upload");

    /** 改了 question 的 instructions：state 与 model 都没动。 */
    private static final String CHANGED_QUESTION_BODY = REQUEST_BODY.replace("Choose the team.", "Pick the team.");

    private static final List<Integer> STATUSES = List.of(200, 400, 401, 429, 500, 529);

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void replaysIdenticallyAfterGoingOfflineWithoutAnyCredential() throws Exception {
        Path cassettes = dir.resolve("cassettes");

        // —— Record：应用把 base URL 指向本地代理，请求带着 API Key 进来 ——
        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.stub(200, Map.of("Content-Type", "application/json", "Set-Cookie", "session=abc123"),
                    bytes(RESPONSE_BODY));
            int port = freePort();
            StringBuffer out = new StringBuffer();
            AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
            CommandLine commandLine = new CommandLine(new RecordCommand(upstream.baseUrl()))
                    .setOut(printer(out))
                    .setErr(printer(new StringBuffer()));
            Thread record = Thread.ofVirtual().start(() -> exitCode.set(commandLine.execute(
                    "--listen", "127.0.0.1",
                    "--port", String.valueOf(port),
                    "--cassette-dir", cassettes.toString(),
                    "--config", dir.resolve("absent-config.json").toString())));
            try {
                await(out, "Waiting for Jev requests...");
                HttpResponse<byte[]> live = post(
                        URI.create("http://127.0.0.1:" + port + "/v1/systemone"), REQUEST_BODY, true);
                await(out, "saved:");

                // 应用侧行为不变：拿到上游原件，上游拿到原始凭证。
                assertThat(live.statusCode()).isEqualTo(200);
                assertThat(live.body()).isEqualTo(bytes(RESPONSE_BODY));
                assertThat(upstream.received().get(0).header("Authorization")).isEqualTo("Bearer " + API_KEY);
                assertThat(out.toString()).contains("REC  systemone-");
            } finally {
                record.interrupt();
                record.join(Duration.ofSeconds(10).toMillis());
            }
            assertThat(record.isAlive()).isFalse();
            assertThat(exitCode).hasValue(0);
        } // ← upstream.close()：断网

        // —— Replay：没有上游、没有 API Key，只有磁带 ——
        try (Running replay = start(new ReplayCommand(), cassettes, freePort())) {
            String nl = System.lineSeparator();
            assertThat(replay.out().toString())
                    .contains("JevTape REPLAY")
                    .contains("1 cassette loaded")
                    .contains("Network:" + nl + "OFF")
                    .doesNotContain("forwarded");

            // 相同请求 → 相同响应；上游进程已不存在，这一次命中是零网络的。
            HttpResponse<byte[]> hit = post(replay.target(), REQUEST_BODY, false);
            await(replay.out(), "HIT  systemone-");
            assertThat(hit.statusCode()).isEqualTo(200);
            assertThat(hit.body()).isEqualTo(bytes(RESPONSE_BODY));

            // key 序变化：Canonical JSON 排序后指纹不变，仍命中同一份磁带。
            HttpResponse<byte[]> reordered = post(replay.target(), REORDERED_BODY, false);
            await(replay.out(), "HIT  systemone-", 2);
            assertThat(reordered.statusCode()).isEqualTo(200);
            assertThat(reordered.body()).isEqualTo(bytes(RESPONSE_BODY));

            // state 变化 → MISS，诊断指名 state。
            HttpResponse<byte[]> stateMiss = post(replay.target(), CHANGED_STATE_BODY, false);
            await(replay.out(), "state     CHANGED");
            assertThat(stateMiss.statusCode()).isEqualTo(502);
            assertThat(new String(stateMiss.body(), StandardCharsets.UTF_8))
                    .contains("JEVTAPE_REPLAY_MISS")
                    .contains("state CHANGED, contract MATCH, model MATCH");

            // question 变化 → MISS，诊断指名 contract。
            HttpResponse<byte[]> contractMiss = post(replay.target(), CHANGED_QUESTION_BODY, false);
            await(replay.out(), "contract  CHANGED");
            assertThat(contractMiss.statusCode()).isEqualTo(502);
            assertThat(new String(contractMiss.body(), StandardCharsets.UTF_8))
                    .contains("state MATCH, contract CHANGED, model MATCH");

            // 安全面：磁带与输出里没有任何凭证，默认策略下也没有任何转发痕迹。
            assertThat(replay.out().toString())
                    .doesNotContainIgnoringCase(API_KEY, "bearer", "authorization", "cookie", "session=");
            for (Path cassette : cassetteFiles(cassettes)) {
                assertThat(Files.readString(cassette, StandardCharsets.UTF_8))
                        .doesNotContainIgnoringCase(API_KEY, "bearer", "authorization", "cookie",
                                "session=abc123", "set-cookie")
                        .contains("\"schemaVersion\" : 1");
            }
        }
    }

    @Test
    void upstreamStatusMatrixSurvivesTheRecordReplayRoundTrip() throws Exception {
        Path cassettes = dir.resolve("cassettes");
        try (FakeJevServer upstream = new FakeJevServer()) {
            for (int status : STATUSES) {
                upstream.stub(status, Map.of("Content-Type", "application/json", "Retry-After", "30"),
                        bytes("{\"error\":\"upstream-" + status + "\"}"));
                record(upstream.baseUrl(), cassettes, "/v1/systemone/case-" + status);
            }
            // 非法响应：上游回了 HTML 错误页，同样录进磁带。
            upstream.stub(200, Map.of("Content-Type", "text/html"), bytes("<html>Bad Gateway</html>"));
            record(upstream.baseUrl(), cassettes, "/v1/systemone/broken");
        } // ← 断网

        // replay 只从磁带取应答：每个状态码与 body 逐字节还原，连 Retry-After 都在。
        List<Cassette> loaded = new FileCassetteRepository(cassettes).loadAll();
        assertThat(loaded).hasSize(STATUSES.size() + 1);
        ReplayJevTransport replay = new ReplayJevTransport(loaded, result -> { });
        for (int status : STATUSES) {
            JevResponse response = replay.send(request("/v1/systemone/case-" + status));
            assertThat(response.status()).isEqualTo(status);
            assertThat(new String(response.body(), StandardCharsets.UTF_8))
                    .isEqualTo("{\"error\":\"upstream-" + status + "\"}");
            if (status == 429) {
                assertThat(header(response, "Retry-After")).isEqualTo("30");
            }
        }
        JevResponse broken = replay.send(request("/v1/systemone/broken"));
        assertThat(broken.status()).isEqualTo(200);
        assertThat(new String(broken.body(), StandardCharsets.UTF_8)).isEqualTo("<html>Bad Gateway</html>");
    }

    @Test
    void anUnresponsiveUpstreamFailsTheRecordWithoutWritingACassette() throws Exception {
        Path cassettes = dir.resolve("cassettes");
        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.delay(Duration.ofSeconds(2));
            RecordingJevTransport recording = new RecordingJevTransport(
                    new LiveJevTransport(upstream.baseUrl(), Duration.ofMillis(150)),
                    new FileCassetteRepository(cassettes), "0.1.0-gate", cassette -> { });

            // 没有响应就没有可录的 interaction：异常原样抛出，磁带一个字节都不落。
            assertThatThrownBy(() -> recording.send(request("/v1/systemone/slow")))
                    .isInstanceOf(UpstreamTimeout.class);
        }
        assertThat(cassetteFiles(cassettes)).isEmpty();
    }

    /** 通过真实 record 装配链录一个请求；每个 case 用不同的 path，避免内容寻址命名互相覆盖。 */
    private void record(URI upstream, Path cassettes, String path) {
        List<Cassette> recorded = new ArrayList<>();
        new RecordingJevTransport(new LiveJevTransport(upstream, Duration.ofSeconds(10)),
                new FileCassetteRepository(cassettes), "0.1.0-gate", recorded::add)
                .send(request(path));
        assertThat(recorded).hasSize(1);
    }

    private JevRequest request(String path) {
        return new JevRequest("POST", path,
                Map.of("Authorization", List.of("Bearer " + API_KEY)),
                bytes(REQUEST_BODY));
    }

    private HttpResponse<byte[]> post(URI target, String body, boolean withCredentials) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .method("POST", HttpRequest.BodyPublishers.ofByteArray(bytes(body)))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10));
        if (withCredentials) {
            builder.header("Authorization", "Bearer " + API_KEY).header("Cookie", "session=abc123");
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String header(JevResponse response, String name) {
        return response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equalsIgnoreCase(name))
                .map(entry -> entry.getValue().get(0))
                .findFirst()
                .orElse(null);
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

    private Running start(ReplayCommand command, Path cassettes, int port) {
        StringBuffer out = new StringBuffer();
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        CommandLine commandLine = new CommandLine(command)
                .setOut(printer(out))
                .setErr(printer(new StringBuffer()));
        Thread cli = Thread.ofVirtual().start(() -> exitCode.set(commandLine.execute(
                "--listen", "127.0.0.1",
                "--port", String.valueOf(port),
                "--cassette-dir", cassettes.toString(),
                "--config", dir.resolve("absent-config.json").toString())));

        Running running = new Running(out, cli, exitCode,
                URI.create("http://127.0.0.1:" + port + "/v1/systemone"), port);
        await(out, "Waiting for Jev requests...");
        return running;
    }

    private static List<Path> cassetteFiles(Path cassettes) throws IOException {
        if (!Files.exists(cassettes)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(cassettes)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /** 轮询代理线程写进来的输出；超时就把已经拿到的部分连同失败一起报出来。 */
    private static void await(StringBuffer out, String expected) {
        await(out, expected, 1);
    }

    private static void await(StringBuffer out, String expected, int occurrences) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (count(out.toString(), expected) >= occurrences) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("jevtape never printed '%s' %d time(s). Output so far:%n%s", expected, occurrences, out);
    }

    private static int count(String text, String expected) {
        int found = 0;
        for (int at = text.indexOf(expected); at >= 0; at = text.indexOf(expected, at + 1)) {
            found++;
        }
        return found;
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
