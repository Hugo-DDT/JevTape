package io.jevtape.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jevtape inspect} 的渲染契约：不打开 JSON 也能看到 Model / 状态 / 延迟 / Questions / Answers /
 * Tokens（charter §17）。样例取自已发布的 {@code fixtures/cassette-v1}，因此这些断言同时守着格式兼容性 ——
 * 磁带里的内容没变，摘要就不该变。
 */
class InspectCommandTest {

    private static final Path FIXTURES = Path.of("fixtures", "cassette-v1");

    /** 横线是装饰，宽度不参与断言；内容与对齐才参与。 */
    private static final String RULE = "─".repeat(29);

    private static final String ISSUE_ROUTING = """
            Cassette
            <rule>
            Name      issue-routing
            Recorded  2026-09-20T10:20:30Z
            Model     jev-1.13.0
            Status    200
            Latency   132 ms

            Questions
            <rule>
            route     Choice
            urgent    Noul
            severity  Score

            Answers
            <rule>
            route
              choice         billing
              probabilities
                billing      0.81
                technical    0.12
                other        0.07

              confidence     0.74

            urgent
              probability    0.91

            severity
              score          2.31

            Tokens
            <rule>
            inputTokens   824
            outputTokens  96
            """.replace("<rule>", RULE);

    /** 429 的磁带没有作答也没有用量：空的节整节不出现，而不是摆一个只有标题的空壳。 */
    private static final String RATE_LIMITED = """
            Cassette
            <rule>
            Name      rate-limited
            Recorded  2026-09-20T10:41:07Z
            Model     jev-latest
            Status    429
            Latency   41 ms

            Questions
            <rule>
            refund  Choice
            """.replace("<rule>", RULE);

    @TempDir
    Path dir;

    @Test
    void summarisesARecordedDecisionWithoutOpeningTheJson() {
        Result result = execute("inspect", "issue-routing");

        assertThat(result.exitCode()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).isEqualTo(ISSUE_ROUTING);
    }

    @Test
    void anErrorCassetteOmitsTheSectionsItHasNothingFor() {
        Result result = execute("inspect", "rate-limited");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo(RATE_LIMITED);
    }

    @Test
    void aCassetteThatIsNotThereFailsWithOneLine() {
        Result result = execute("inspect", "nope");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEmpty();
        assertThat(result.err())
                .contains("jevtape: No cassette 'nope' under " + FIXTURES)
                .doesNotContain("\tat ");
    }

    /** 名字是用户输入：能逃出 cassetteDir 的名字必须在读到任何文件之前就被拒掉。 */
    @Test
    void aNameThatWouldEscapeTheCassetteDirectoryIsRejected() {
        Result result = execute("inspect", "../cassette-v1/issue-routing");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEmpty();
        assertThat(result.err())
                .contains("jevtape: Invalid cassette name '../cassette-v1/issue-routing'")
                .doesNotContain("\tat ");
    }

    @Test
    void inspectWithoutANameIsAUsageError() {
        Result result = execute("inspect");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.err()).contains("Missing required parameter");
    }

    private record Result(int exitCode, String out, String err) {
    }

    /** 走真正的入口，于是断言的是用户实际看到的那一行，而不是 picocli 的默认渲染。 */
    private Result execute(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        String[] full = Stream.concat(Stream.of(args), Stream.of(
                        "-d", FIXTURES.toString(),
                        "--config", dir.resolve("absent-config.json").toString()))
                .toArray(String[]::new);

        int exitCode = JevTapeCli.commandLine()
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err))
                .execute(full);
        return new Result(exitCode, normalize(out.toString()), err.toString());
    }

    /** println 用的是平台行尾；归一化之后同一份 golden 在 Windows 与 CI 上都成立。 */
    private static String normalize(String text) {
        return text.replace(System.lineSeparator(), "\n");
    }
}
