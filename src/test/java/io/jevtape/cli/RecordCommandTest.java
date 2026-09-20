package io.jevtape.cli;

import io.jevtape.testing.FakeJevServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * record 命令的端到端闭环：应用把 base URL 指向 JevTape 之后行为不变，cassette 落盘，
 * 而凭证既不进文件也不进 CLI 输出。
 */
class RecordCommandTest {

    private static final String API_KEY = "sk-live-9f2c1d4b7a6e5840";

    private static final String REQUEST_BODY = """
            {"model":"jev-latest","state":{"ticket":{"id":"SUP-4821","subject":"Cannot export invoice PDF"}},\
            "questions":{"route":{"type":"Choice","instructions":"Choose the team.",\
            "options":[{"value":"billing","criteria":"Invoices."}]},\
            "urgent":{"type":"Noul","instructions":"Is this ticket urgent?","criteria":"Blocked customer."}}}""";

    private static final String RESPONSE_BODY =
            "{\"model\":\"jev-1.13.0\",\"answers\":{\"route\":{\"choice\":\"billing\"},\"urgent\":{\"probability\":0.91}}}";

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void recordsDecisionsThroughTheProxyAndPrintsTheRecordUx() throws Exception {
        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.stub(200, Map.of("Content-Type", "application/json", "Set-Cookie", "session=abc123"),
                    RESPONSE_BODY.getBytes(StandardCharsets.UTF_8));
            Path cassettes = dir.resolve("cassettes");
            int port = freePort();
            URI target = URI.create("http://127.0.0.1:" + port + "/v1/systemone");

            StringBuffer out = new StringBuffer();
            AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
            CommandLine commandLine = new CommandLine(new RecordCommand(upstream.baseUrl()))
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
                        .contains("JevTape RECORD")
                        .contains("Listening:" + nl + "http://127.0.0.1:" + port)
                        .contains("Upstream:" + nl + upstream.baseUrl())
                        .contains("Cassette directory:" + nl + cassettes);

                HttpResponse<byte[]> response = post(target, REQUEST_BODY);
                await(out, "saved:");

                // 应用侧行为不变：拿到的是上游那份原件，上游拿到的是原始凭证。
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo(RESPONSE_BODY.getBytes(StandardCharsets.UTF_8));
                assertThat(upstream.received().get(0).header("Authorization")).isEqualTo("Bearer " + API_KEY);

                assertThat(out.toString())
                        .contains("REC  systemone-")
                        .contains("     Choice route")
                        .contains("     Noul urgent")
                        .contains("     jev-1.13.0")
                        .containsPattern("     \\d+ ms")
                        .containsPattern("systemone-[0-9a-f]{8}\\.json");

                // 同一个会话继续服务：换一个决策，得到第二个 cassette。
                post(target, REQUEST_BODY.replace("SUP-4821", "SUP-9999"));
                await(out, "REC  systemone-", 2);
                assertThat(cassetteFiles(cassettes)).hasSize(2);

                String printed = out.toString();
                assertThat(printed).doesNotContainIgnoringCase(API_KEY, "bearer", "authorization", "cookie",
                        "session=abc123", "[redacted]");
                for (Path cassette : cassetteFiles(cassettes)) {
                    assertThat(Files.readString(cassette, StandardCharsets.UTF_8))
                            .doesNotContainIgnoringCase(API_KEY, "bearer", "authorization", "cookie",
                                    "session=abc123", "set-cookie")
                            .contains("\"schemaVersion\" : 1")
                            .contains("\"requestedModel\" : \"jev-latest\"")
                            .contains("\"resolvedModel\" : \"jev-1.13.0\"");
                }
            } finally {
                cli.interrupt();
                cli.join(Duration.ofSeconds(10).toMillis());
            }

            assertThat(cli.isAlive()).isFalse();
            assertThat(exitCode.get()).isZero();
        }
    }

    private static List<Path> cassetteFiles(Path cassettes) throws IOException {
        try (var files = Files.list(cassettes)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private HttpResponse<byte[]> post(URI target, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(target)
                        .method("POST", HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + API_KEY)
                        .header("Cookie", "session=abc123")
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
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
        fail("jevtape record never printed '%s' %d time(s). Output so far:%n%s", expected, occurrences, out);
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
