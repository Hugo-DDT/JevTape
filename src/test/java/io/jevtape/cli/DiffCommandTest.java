package io.jevtape.cli;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.testing.FakeJevServer;
import io.jevtape.transport.JevRequest;
import io.jevtape.transport.LiveJevTransport;
import io.jevtape.transport.RecordingJevTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jevtape diff} 的报告（charter §19）：两盘磁带走真实的 record 路径产出，因此比较的是 replay 真会
 * 读到的那两份应答。断言的重点是"差异被说出来"而不是"哪个结果更好" —— diff 没有裁决，退出码恒为 0。
 *
 * <p>其中一条断言专门守着"不做字段投影"：应答里一个 JevTape 不认识的字段（{@code reason}）变了也必须
 * 出现在报告里。漏掉它会让"这两盘答得一样"变成一个错误的结论，那比报不出差异更危险。
 */
class DiffCommandTest {

    /** 横线是装饰，宽度不参与断言；内容与对齐才参与。 */
    private static final String RULE = "─".repeat(29);

    private static final String REQUEST = """
            {"model":"jev-latest","state":{"ticket":{"id":"SUP-4821"}},\
            "questions":{"route":{"type":"Choice","instructions":"Choose the team.",\
            "options":[{"value":"billing","criteria":"Invoices."},{"value":"technical","criteria":"Bugs."}]},\
            "severity":{"type":"Score","instructions":"How severe?","levels":[{"score":0,"criteria":"Cosmetic."}]},\
            "urgent":{"type":"Noul","instructions":"Is this urgent?","criteria":"Customer is blocked."}}}""";

    /** 换了 state 里的一个字段，于是内容寻址命名给出第二个名字 —— 同一个请求录不出两盘磁带。 */
    private static final String OTHER_REQUEST = REQUEST.replace("SUP-4821", "SUP-9999");

    private static final String OLD_ANSWERS = """
            {"model":"jev-1.13.0","answers":{"route":{"choice":"billing",\
            "probabilities":{"billing":0.81,"technical":0.12},"confidence":0.74},\
            "severity":{"score":2.31},"urgent":{"probability":0.91}}}""";

    /** 同一份请求、新一点的模型：选项没换，概率挪了，confidence 掉到阈值之下。 */
    private static final String NEW_ANSWERS = """
            {"model":"jev-1.14.0","answers":{"route":{"choice":"technical",\
            "probabilities":{"billing":0.63,"technical":0.29},"confidence":0.52},\
            "severity":{"score":4.02},"urgent":{"probability":0.33}}}""";

    private static final String DRIFT = """
            Diff
            <rule>
            Old          <old>
            New          <new>
            Model        jev-1.13.0 → jev-1.14.0
            Status       200
            Differences  3 questions

            Question: route
            <rule>
            billing     0.81 → 0.63
            technical   0.12 → 0.29
            choice      billing → technical
            confidence  0.74 → 0.52

            Question: severity
            <rule>
            score  2.31 → 4.02

            Question: urgent
            <rule>
            probability  0.91 → 0.33
            """;

    /** 作答一模一样时不摆出三节 `x → x`，只说没有差异。 */
    private static final String IDENTICAL = """
            Diff
            <rule>
            Old          <old>
            New          <new>
            Model        jev-1.13.0
            Status       200
            Differences  none
            """;

    private static final String ONLY_IN_NEW = """
            Diff
            <rule>
            Old          <old>
            New          <new>
            Model        jev-1.13.0
            Status       200
            Differences  1 question

            Question: urgent (only in new)
            <rule>
            probability  - → 0.91
            """;

    /** {@code reason} 不是 JevTape 认识的字段，它变了同样要报出来。 */
    private static final String UNMODELLED_FIELD = """
            Diff
            <rule>
            Old          <old>
            New          <new>
            Model        jev-1.13.0
            Status       200
            Differences  1 question

            Question: route
            <rule>
            choice  billing → billing
            reason  invoice export → refund
            """;

    @TempDir
    Path dir;

    @Test
    void everyAnswerOfAChangedQuestionIsListedOldToNew() {
        Cassette old = record(REQUEST, OLD_ANSWERS);
        Cassette now = record(OTHER_REQUEST, NEW_ANSWERS);

        Result result = diff(old, now);

        assertThat(result.exitCode()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).isEqualTo(fill(DRIFT, old, now));
    }

    @Test
    void identicalAnswersAreReportedAsNoneInsteadOfThreeUnchangedSections() {
        Cassette old = record(REQUEST, OLD_ANSWERS);
        Cassette now = record(OTHER_REQUEST, OLD_ANSWERS);

        Result result = diff(old, now);

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo(fill(IDENTICAL, old, now));
    }

    @Test
    void anAnswerOnlyOneCassetteHasSaysWhichSideItIsMissingFrom() {
        Cassette old = record(REQUEST, OLD_ANSWERS.replace(",\"urgent\":{\"probability\":0.91}", ""));
        Cassette now = record(OTHER_REQUEST, OLD_ANSWERS);

        Result result = diff(old, now);

        assertThat(result.out()).isEqualTo(fill(ONLY_IN_NEW, old, now));
    }

    /** 不做字段投影的守卫：应答里多出来的字段同样参与比较，不会因为"不认识"而被悄悄丢掉。 */
    @Test
    void aFieldJevTapeDoesNotModelStillShowsUpAsADifference() {
        Cassette old = record(REQUEST, "{\"model\":\"jev-1.13.0\",\"answers\":{\"route\":"
                + "{\"choice\":\"billing\",\"reason\":\"invoice export\"}}}");
        Cassette now = record(OTHER_REQUEST, "{\"model\":\"jev-1.13.0\",\"answers\":{\"route\":"
                + "{\"choice\":\"billing\",\"reason\":\"refund\"}}}");

        Result result = diff(old, now);

        assertThat(result.out()).isEqualTo(fill(UNMODELLED_FIELD, old, now));
    }

    /** 录了错误状态码的磁带没有作答可比，diff 照旧出报告，只是把状态码的变化说出来。 */
    @Test
    void anErrorCassetteHasNoAnswersToCompareButStillReportsItsStatus() {
        Cassette old = recordFailure(REQUEST);
        Cassette now = record(OTHER_REQUEST, OLD_ANSWERS);

        Result result = diff(old, now);

        assertThat(result.exitCode()).isZero();
        assertThat(result.out())
                .contains("Status       429 → 200")
                .contains("Differences  3 questions")
                .contains("Question: route (only in new)")
                .contains("billing     - → 0.81");
    }

    @Test
    void aCassetteThatIsNotThereFailsWithOneLine() {
        Cassette old = record(REQUEST, OLD_ANSWERS);

        Result result = execute("diff", old.name(), "nope");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEmpty();
        assertThat(result.err())
                .contains("jevtape: No cassette 'nope' under " + cassettes())
                .doesNotContain("\tat ");
    }

    @Test
    void diffNeedsTwoCassettes() {
        Result result = execute("diff", "issue-routing");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("Missing required parameter: '<new>'");
    }

    /** 走真实的 record 路径产出磁带：diff 读到的就是 replay 会读到的那一份。 */
    private Cassette record(String requestBody, String responseBody) {
        return record(requestBody, 200, responseBody);
    }

    private Cassette recordFailure(String requestBody) {
        return record(requestBody, 429, "{\"error\":\"rate limited\"}");
    }

    private Cassette record(String requestBody, int status, String responseBody) {
        List<Cassette> recorded = new ArrayList<>();
        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.stub(status, Map.of("Content-Type", "application/json"), bytes(responseBody));
            new RecordingJevTransport(new LiveJevTransport(upstream.baseUrl(), Duration.ofSeconds(10)),
                    new FileCassetteRepository(cassettes()), "0.4.0-test", recorded::add)
                    .send(new JevRequest("POST", "/v1/systemone", Map.of(), bytes(requestBody)));
        }
        assertThat(recorded).hasSize(1);
        return recorded.getFirst();
    }

    private Path cassettes() {
        return dir.resolve("cassettes");
    }

    private Result diff(Cassette old, Cassette now) {
        return execute("diff", old.name(), now.name());
    }

    private static String fill(String template, Cassette old, Cassette now) {
        return template.replace("<rule>", RULE)
                .replace("<old>", old.name())
                .replace("<new>", now.name());
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private record Result(int exitCode, String out, String err) {
    }

    /** 走真正的入口，于是断言的是用户实际看到的那一份报告，而不是 picocli 的默认渲染。 */
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
