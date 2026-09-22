package io.jevtape.cli;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.contract.JevProtocolAdapter;
import io.jevtape.fingerprint.FingerprintEngine;
import io.jevtape.testing.FakeJevServer;
import io.jevtape.testing.JevProtocolFixtures;
import io.jevtape.transport.JevRequest;
import io.jevtape.transport.LiveJevTransport;
import io.jevtape.transport.RecordingJevTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jevtape verify} 的报告与闸门语义（charter §18）：磁带走真实的 record 路径产出，因此"录完立刻
 * verify 同一份请求"必须 PASS —— 这条闭环本身就是断言。改动既有内容 FAIL 且退出码 1（CI 拦得住），
 * 纯新增 WARN 且退出码 0（旧的录制仍然回答了它当时被问到的问题）。
 *
 * <p>指纹不写死在 golden 里：录制那一份取自磁带本身，当前那一份由 {@link FingerprintEngine} 现算 ——
 * 它们的值归 FingerprintEngineTest 管，这里管的是报告长什么样、退出码是几。
 */
class VerifyCommandTest {

    /** 横线是装饰，宽度不参与断言；内容与对齐才参与。 */
    private static final String RULE = "─".repeat(29);

    private static final String RESPONSE_BODY =
            "{\"model\":\"jev-1.13.0\",\"answers\":{\"route\":{\"choice\":\"billing\"}}}";

    private static final String REQUEST = """
            {"model":"jev-latest","state":{"ticket":{"id":"SUP-4821","subject":"Cannot export invoice PDF"}},\
            "questions":{"route":{"type":"Choice","instructions":"Choose the team.",\
            "options":[{"value":"billing","criteria":"Invoices."},{"value":"technical","criteria":"Bugs."}]},\
            "urgent":{"type":"Noul","instructions":"Is this urgent?","criteria":"Customer is blocked."}}}""";

    /** 改了一条既有 criteria 的文案：契约变了，旧录制回答的已经不是同一个问题。 */
    private static final String REWORDED_OPTION = REQUEST.replace(
            "\"criteria\":\"Invoices.\"", "\"criteria\":\"Refunds and payments.\"");

    /** 官方协议样例里三类 question 同处一份请求（{@code fixtures/jev-protocol/all-types.json}）。 */
    private static final String OFFICIAL_REQUEST =
            new String(JevProtocolFixtures.request("all-types"), StandardCharsets.UTF_8);

    /** 只多了一个选项，既有内容一字未改。 */
    private static final String EXTRA_OPTION = REQUEST.replace(
            "{\"value\":\"technical\",\"criteria\":\"Bugs.\"}]",
            "{\"value\":\"technical\",\"criteria\":\"Bugs.\"},{\"value\":\"other\",\"criteria\":\"Anything else.\"}]");

    /** v1 的 Noul 只有一条 criteria，没有标签可打，因此诊断里就只剩这个概念本身。 */
    private static final String REWORDED_NOUL = REQUEST.replace(
            "Customer is blocked.", "Customer is blocked and losing money.");

    private static final String PASS = """
            Verify
            <rule>
            Verdict   PASS
            Cassette  <name>
            Request   <request>

            Contract
            <rule>
            Recorded  <recorded>
            Current   <recorded>
            """;

    private static final String REWORDED_REPORT = """
            Verify
            <rule>
            Verdict   FAIL
            Cassette  <name>
            Request   <request>

            Changes
            <rule>
            route
              ~ option billing

            Contract
            <rule>
            Recorded  <recorded>
            Current   <current>
            """;

    private static final String EXTRA_OPTION_REPORT = """
            Verify
            <rule>
            Verdict   WARN
            Cassette  <name>
            Request   <request>

            Changes
            <rule>
            route
              + option other

            Contract
            <rule>
            Recorded  <recorded>
            Current   <current>
            """;

    @TempDir
    Path dir;

    @Test
    void aContractThatHasNotChangedPasses() throws IOException {
        Cassette cassette = record(REQUEST);
        Path request = writeRequest(REQUEST);

        Result result = verify(cassette, request);

        assertThat(result.exitCode()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).isEqualTo(fill(PASS, cassette, request, REQUEST));
    }

    /** 改文案是 FAIL，退出码 1 —— 这正是 CI 里那道闸门。 */
    @Test
    void rewordedCriteriaFailTheGate() throws IOException {
        Cassette cassette = record(REQUEST);
        Path request = writeRequest(REWORDED_OPTION);

        Result result = verify(cassette, request);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).isEqualTo(fill(REWORDED_REPORT, cassette, request, REWORDED_OPTION));
    }

    /** 纯新增只到 WARN，退出码仍是 0：录制没失效，只是不再覆盖今天的整个选项空间。 */
    @Test
    void anAddedOptionWarnsWithoutFailingTheGate() throws IOException {
        Cassette cassette = record(REQUEST);
        Path request = writeRequest(EXTRA_OPTION);

        Result result = verify(cassette, request);

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo(fill(EXTRA_OPTION_REPORT, cassette, request, EXTRA_OPTION));
    }

    @Test
    void aNoulCriterionIsReportedWithoutALabel() throws IOException {
        Cassette cassette = record(REQUEST);

        Result result = verify(cassette, writeRequest(REWORDED_NOUL));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).contains("Verdict   FAIL").contains("urgent\n  ~ criteria");
    }

    /**
     * 官方形状（小写 type、Choice 的 criteria map、Score 的 criteria array、Noul 的 true / false object）走的
     * 是同一条 record → verify 路径：三类 question 都读得出可选项，改一条 rubric 就指名那一条。
     */
    @Test
    void anOfficialShapedRequestIsVerifiedDownToTheRubric() throws IOException {
        Cassette cassette = record(OFFICIAL_REQUEST);

        Result unchanged = verify(cassette, writeRequest(OFFICIAL_REQUEST));
        Result reworded = verify(cassette, write("official-reworded.json",
                OFFICIAL_REQUEST.replace("Payments, invoicing, refunds", "Payments and refunds only.")));

        assertThat(unchanged.exitCode()).isZero();
        assertThat(unchanged.out()).contains("Verdict   PASS");
        assertThat(reworded.exitCode()).isEqualTo(1);
        assertThat(reworded.out()).contains("Verdict   FAIL").contains("department\n  ~ option billing");
    }

    /** 报告只谈契约：state 的正文既不进指纹也不进输出，因此改了 state 仍然是 PASS。 */
    @Test
    void stateIsNotPartOfTheContract() throws IOException {
        Cassette cassette = record(REQUEST);

        Result result = verify(cassette, writeRequest(REQUEST.replace("SUP-4821", "SUP-9999")));

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("Verdict   PASS").doesNotContain("SUP-9999");
    }

    @Test
    void aCassetteThatIsNotThereFailsWithOneLine() throws IOException {
        record(REQUEST);

        Result result = execute("verify", "nope", "--request", writeRequest(REQUEST).toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEmpty();
        assertThat(result.err())
                .contains("jevtape: No cassette 'nope' under " + cassettes())
                .doesNotContain("\tat ");
    }

    @Test
    void aRequestFileThatIsNotThereFailsWithOneLine() {
        Cassette cassette = record(REQUEST);

        Result result = execute("verify", cassette.name(),
                "--request", dir.resolve("absent-request.json").toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEmpty();
        assertThat(result.err())
                .contains("jevtape: No request file " + dir.resolve("absent-request.json"))
                .doesNotContain("\tat ");
    }

    /** 认不出来的请求就地报错，不拿一次"契约全变了"的假诊断去糊弄人。 */
    @Test
    void aBodyThatIsNotASystemOneRequestFailsWithOneLine() throws IOException {
        Cassette cassette = record(REQUEST);

        Result notJson = verify(cassette, write("not-json.txt", "oops"));
        Result noQuestions = verify(cassette, write("no-questions.json", "{\"model\":\"jev-latest\"}"));

        assertThat(notJson.exitCode()).isEqualTo(1);
        assertThat(notJson.err()).contains("jevtape: A System One request must carry a JSON object body");
        assertThat(noQuestions.exitCode()).isEqualTo(1);
        assertThat(noQuestions.err()).contains("jevtape: A Decision Contract needs a 'questions' object");
    }

    @Test
    void verifyWithoutARequestIsAUsageError() {
        Result result = execute("verify", "issue-routing");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("Missing required option: '--request=<file>'");
    }

    /** 走真实的 record 路径产出磁带：verify 读到的就是 replay 会读到的那一份。 */
    private Cassette record(String requestBody) {
        Path cassettes = cassettes();
        List<Cassette> recorded = new ArrayList<>();
        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.stub(200, Map.of("Content-Type", "application/json"), bytes(RESPONSE_BODY));
            new RecordingJevTransport(new LiveJevTransport(upstream.baseUrl(), Duration.ofSeconds(10)),
                    new FileCassetteRepository(cassettes), "0.3.0-test", recorded::add)
                    .send(new JevRequest("POST", "/v1/systemone", Map.of(), bytes(requestBody)));
        }
        assertThat(recorded).hasSize(1);
        return recorded.getFirst();
    }

    private Path cassettes() {
        return dir.resolve("cassettes");
    }

    private Result verify(Cassette cassette, Path request) {
        return execute("verify", cassette.name(), "--request", request.toString());
    }

    private Path writeRequest(String requestBody) throws IOException {
        return write("current-request.json", requestBody);
    }

    private Path write(String fileName, String content) throws IOException {
        return Files.writeString(dir.resolve(fileName), content, StandardCharsets.UTF_8);
    }

    /**
     * golden 里只留占位符：录制那一份指纹取自磁带（它是 record 路径算出来的），当前那一份现算 ——
     * PASS 的模板两处都用 {@code <recorded>}，于是"两个指纹相同"本身就是断言。
     */
    private static String fill(String template, Cassette cassette, Path request, String currentBody) {
        return template.replace("<rule>", RULE)
                .replace("<name>", cassette.name())
                .replace("<request>", request.toString())
                .replace("<recorded>", cassette.fingerprints().contract())
                .replace("<current>", FingerprintEngine.contract(
                        JevProtocolAdapter.parseRequest(bytes(currentBody)).questions()));
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private record Result(int exitCode, String out, String err) {
    }

    /** 走真正的入口，于是断言的是用户实际看到的那一行，而不是 picocli 的默认渲染。 */
    private Result execute(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        String[] full = Stream.concat(Stream.of(args), Stream.of(
                        "-d", cassettes().toString(),
                        "--config", dir.resolve("absent-config.json").toString()))
                .toArray(String[]::new);

        int exitCode = JevTapeCli.commandLine()
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err))
                .execute(full);
        return new Result(exitCode, out.toString().replace(System.lineSeparator(), "\n"), err.toString());
    }
}
