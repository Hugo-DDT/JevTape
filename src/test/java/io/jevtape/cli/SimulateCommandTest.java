package io.jevtape.cli;

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
 * {@code jevtape simulate} 的端到端闭环（charter §11, §20）：先对着 FakeJevServer 录，把上游关掉，再让
 * simulate 用磁带应答并注入故障 —— 于是"离线"是物理事实（上游进程已经不存在），注入出来的 429 与低
 * confidence 只可能来自磁带。
 *
 * <p>断言按 body 逐字节比较，因此每一条都同时在说两件事：想注入的那个字段确实变了，而其余部分一个字节都
 * 没动 —— 注入不该顺手把一份录制改成另一份东西。
 */
class SimulateCommandTest {

    private static final String RULE = "─".repeat(29);

    private static final String REQUEST_BODY = """
            {"model":"jev-latest","state":{"ticket":{"id":"SUP-4821"}},\
            "questions":{"route":{"type":"Choice","instructions":"Choose the team.",\
            "options":[{"value":"billing","criteria":"Invoices."},{"value":"technical","criteria":"Bugs."}]},\
            "escalate":{"type":"Choice","instructions":"Escalate?","options":[{"value":"yes","criteria":"Now."},\
            {"value":"no","criteria":"Later."}]},\
            "severity":{"type":"Score","instructions":"How severe?","levels":[{"score":0,"criteria":"Cosmetic."}]},\
            "urgent":{"type":"Noul","instructions":"Is this urgent?","criteria":"Customer is blocked."}}}""";

    /** 换了 state 里的一个字段，于是内容寻址命名给出第二盘磁带。 */
    private static final String OTHER_REQUEST_BODY = REQUEST_BODY.replace("SUP-4821", "SUP-9999");

    /** 四个 question 各带一种作答形状：概率分布、confidence、score、Noul 的 probability。 */
    private static final String RESPONSE_BODY = """
            {"model":"jev-1.13.0","answers":{"route":{"choice":"billing",\
            "probabilities":{"billing":0.81,"technical":0.19},"confidence":0.74},\
            "escalate":{"choice":"no","confidence":0.88},\
            "severity":{"score":2.31},"urgent":{"probability":0.91}}}""";

    /** 只有 score 的应答：它没有 confidence 可以被压低。 */
    private static final String SCORE_ONLY_BODY =
            "{\"model\":\"jev-1.13.0\",\"answers\":{\"severity\":{\"score\":2.31}}}";

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void injectsAnHttpStatusIntoEveryCassetteAndStaysOffline() throws Exception {
        record(REQUEST_BODY);
        record(OTHER_REQUEST_BODY);

        try (Running simulate = start(freePort(), "--http-status", "429")) {
            String nl = System.lineSeparator();
            assertThat(simulate.out().toString())
                    .contains("JevTape SIMULATE")
                    .contains("2 cassettes loaded")
                    .contains("Listening:" + nl + "http://127.0.0.1:" + simulate.port())
                    .contains("Network:" + nl + "OFF")
                    .contains("Injected" + nl + RULE + nl + "http-status  429");

            // 不给 <name> 就是服务全部磁带：一整个应用跑起来的每一次 Jev 调用都要撞上这个场景。
            for (String requestBody : List.of(REQUEST_BODY, OTHER_REQUEST_BODY)) {
                HttpResponse<byte[]> response = post(simulate.target(), requestBody);
                await(simulate.out(), "HIT  systemone-");
                assertThat(response.statusCode()).isEqualTo(429);
                // 注入的是状态码，body 仍是录制的原件 —— JevTape 不编造上游的错误响应长什么样。
                assertThat(response.body()).isEqualTo(bytes(RESPONSE_BODY));
            }
            assertThat(simulate.err().toString()).isEmpty();
        }
    }

    /** charter §11 的那条：把 confidence 压到应用的阈值之下，看 {@code human_review()} 会不会被触发。 */
    @Test
    void overridesEveryAnswerFieldTheCassetteActuallyCarries() throws Exception {
        record(REQUEST_BODY);

        try (Running simulate = start(freePort(),
                "--confidence", "0.42", "--score", "4.5", "--probability", "0.2")) {
            assertThat(simulate.out().toString()).contains(
                    "confidence   0.42" + System.lineSeparator()
                            + "score        4.5" + System.lineSeparator()
                            + "probability  0.2");

            HttpResponse<byte[]> response = post(simulate.target(), REQUEST_BODY);
            await(simulate.out(), "HIT  systemone-");
            assertThat(response.statusCode()).isEqualTo(200);
            // 两个 confidence 都被压低，score 与 probability 也换了；概率分布一个字节没动 ——
            // --probability 覆盖的是 Noul 的 probability，不是 Choice 的 probabilities。
            assertThat(response.body()).isEqualTo(bytes(RESPONSE_BODY
                    .replace("0.74", "0.42").replace("0.88", "0.42")
                    .replace("2.31", "4.5").replace("0.91", "0.2")));
        }
    }

    @Test
    void anOverrideCanBeScopedToOneQuestion() throws Exception {
        record(REQUEST_BODY);

        try (Running simulate = start(freePort(),
                "--question", "route", "--choice", "technical", "--confidence", "0.42")) {
            assertThat(simulate.out().toString()).contains("question    route");

            HttpResponse<byte[]> response = post(simulate.target(), REQUEST_BODY);
            await(simulate.out(), "HIT  systemone-");
            // escalate 的 confidence 还是录制的 0.88：--question 之外的答案一个都没碰。
            assertThat(response.body()).isEqualTo(bytes(RESPONSE_BODY
                    .replace("\"choice\":\"billing\"", "\"choice\":\"technical\"")
                    .replace("0.74", "0.42")));
        }
    }

    /** latency 是真的睡过去，于是"模拟超时"由被测应用自己的 timeout 触发，不是 JevTape 编一个错误码。 */
    @Test
    void holdsEveryResponseBackByTheRequestedLatency() throws Exception {
        record(REQUEST_BODY);

        try (Running simulate = start(freePort(), "--latency", "400")) {
            assertThat(simulate.out().toString()).contains("latency  400 ms");

            long started = System.nanoTime();
            HttpResponse<byte[]> response = post(simulate.target(), REQUEST_BODY);
            long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();

            await(simulate.out(), "HIT  systemone-");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(bytes(RESPONSE_BODY));
            assertThat(elapsed).isGreaterThanOrEqualTo(400);
        }
    }

    /** 磁带里没有答案是一个本地事实，不能把它打扮成注入的那个故障 —— 两者在客户端必须仍然可分。 */
    @Test
    void aMissIsNotDressedUpAsTheInjectedFault() throws Exception {
        record(REQUEST_BODY);
        String served = new FileCassetteRepository(cassettes()).loadAll().getFirst().name();

        try (Running simulate = start(freePort(), served, "--http-status", "500")) {
            assertThat(simulate.out().toString()).contains("1 cassette loaded");

            HttpResponse<byte[]> hit = post(simulate.target(), REQUEST_BODY);
            await(simulate.out(), "HIT  " + served);
            assertThat(hit.statusCode()).isEqualTo(500);

            HttpResponse<byte[]> miss = post(simulate.target(), OTHER_REQUEST_BODY);
            await(simulate.out(), "MISS");
            assertThat(miss.statusCode()).isEqualTo(502);
            assertThat(new String(miss.body(), StandardCharsets.UTF_8))
                    .startsWith("jevtape: JEVTAPE_REPLAY_MISS");
            assertThat(simulate.out().toString()).contains("state     CHANGED").doesNotContain("forwarded");
        }
    }

    /** 注入落不到任何答案上、或状态码根本不是状态码，都在开始监听之前就被拒掉。 */
    @Test
    void anInjectionItCannotHonourIsRejectedBeforeAnythingStartsListening() {
        record(REQUEST_BODY, SCORE_ONLY_BODY);

        Result noConfidence = execute("--confidence", "0.42");
        Result badStatus = execute("--http-status", "99");
        Result negativeLatency = execute("--latency", "-1");

        assertThat(noConfidence.exitCode()).isEqualTo(1);
        assertThat(noConfidence.err())
                .contains("jevtape: confidence 0.42 matches no answer in cassette 'systemone-")
                .contains("only a field an answer already carries can be overridden")
                .doesNotContain("\tat ");
        assertThat(noConfidence.out()).doesNotContain("Listening:");

        assertThat(badStatus.exitCode()).isEqualTo(1);
        assertThat(badStatus.err()).contains("jevtape: --http-status must be between 100 and 599, got 99");

        assertThat(negativeLatency.exitCode()).isEqualTo(1);
        assertThat(negativeLatency.err()).contains("jevtape: --latency must not be negative, got -1 ms");
    }

    /** 走真实的 record 路径产出磁带，然后把上游关掉 —— simulate 期间物理上没有上游可去。 */
    private void record(String requestBody) {
        record(requestBody, RESPONSE_BODY);
    }

    private void record(String requestBody, String responseBody) {
        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.stub(200, Map.of("Content-Type", "application/json"), bytes(responseBody));
            new RecordingJevTransport(new LiveJevTransport(upstream.baseUrl(), Duration.ofSeconds(10)),
                    new FileCassetteRepository(cassettes()), "0.5.0-test", cassette -> { })
                    .send(new JevRequest("POST", "/v1/systemone", Map.of(), bytes(requestBody)));
        }
    }

    private Path cassettes() {
        return dir.resolve("cassettes");
    }

    /** 一次跑起来的 {@code jevtape simulate}；关掉它就是中断那根一直阻塞着的 CLI 线程。 */
    private record Running(StringBuffer out, StringBuffer err, Thread cli, AtomicInteger exitCode,
                           URI target, int port) implements AutoCloseable {

        @Override
        public void close() throws InterruptedException {
            cli.interrupt();
            cli.join(Duration.ofSeconds(10).toMillis());
            assertThat(cli.isAlive()).isFalse();
            assertThat(exitCode).hasValue(0);
        }
    }

    private Running start(int port, String... args) {
        List<String> full = new ArrayList<>(List.of("simulate",
                "--listen", "127.0.0.1",
                "--port", String.valueOf(port),
                "--cassette-dir", cassettes().toString(),
                "--config", dir.resolve("absent-config.json").toString()));
        full.addAll(List.of(args));

        StringBuffer out = new StringBuffer();
        StringBuffer err = new StringBuffer();
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        CommandLine commandLine = JevTapeCli.commandLine()
                .setOut(printer(out))
                .setErr(printer(err));
        Thread cli = Thread.ofVirtual()
                .start(() -> exitCode.set(commandLine.execute(full.toArray(String[]::new))));

        Running running = new Running(out, err, cli, exitCode,
                URI.create("http://127.0.0.1:" + port + "/v1/systemone"), port);
        await(out, "Waiting for Jev requests...");
        return running;
    }

    /** 走真正的入口，于是断言的是用户实际看到的那一行，而不是 picocli 的默认渲染。 */
    private Result execute(String... args) {
        List<String> full = new ArrayList<>(List.of("simulate"));
        full.addAll(List.of(args));
        full.addAll(List.of("--cassette-dir", cassettes().toString(),
                "--config", dir.resolve("absent-config.json").toString()));

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode = JevTapeCli.commandLine()
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err))
                .execute(full.toArray(String[]::new));
        return new Result(exitCode, out.toString(), err.toString());
    }

    private HttpResponse<byte[]> post(URI target, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(target)
                        .method("POST", HttpRequest.BodyPublishers.ofByteArray(bytes(body)))
                        .header("Content-Type", "application/json")
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
        fail("jevtape simulate never printed '%s'. Output so far:%n%s", expected, out);
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

    private record Result(int exitCode, String out, String err) {
    }
}
