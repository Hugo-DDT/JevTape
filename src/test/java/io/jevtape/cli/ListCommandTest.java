package io.jevtape.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jevtape list} 的渲染契约：一行一盘磁带，按名称排序 —— 也就是 replay 的优先级顺序，因此这份列表
 * 同时回答了"哪个 cassette 会先被匹配到"。
 */
class ListCommandTest {

    private static final Path FIXTURES = Path.of("fixtures", "cassette-v1");

    private static final String LISTING = """
            Cassette directory:
            <dir>

            NAME           MODEL       STATUS  LATENCY  RECORDED
            issue-routing  jev-1.13.0  200     132 ms   2026-09-20T10:20:30Z
            rate-limited   jev-latest  429     41 ms    2026-09-20T10:41:07Z

            2 cassettes
            """.replace("<dir>", FIXTURES.toString());

    @TempDir
    Path dir;

    @Test
    void listsEveryCassetteInTheDirectory() {
        Result result = execute(FIXTURES, "list");

        assertThat(result.exitCode()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).isEqualTo(LISTING);
    }

    /** 空目录不是错误：列出来是零盘，退出码 0；目录不存在才是错误。 */
    @Test
    void anEmptyDirectoryListsNothingAndStillSucceeds() throws IOException {
        Path empty = Files.createDirectories(dir.resolve("cassettes"));

        Result result = execute(empty, "list");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out())
                .contains(empty.toString())
                .contains("No cassettes.")
                .doesNotContain("NAME");
    }

    @Test
    void aMissingDirectoryFailsWithOneLine() {
        Result result = execute(dir.resolve("absent"), "list");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEmpty();
        assertThat(result.err())
                .contains("jevtape: No cassette directory " + dir.resolve("absent"))
                .doesNotContain("\tat ");
    }

    private record Result(int exitCode, String out, String err) {
    }

    /** 走真正的入口，于是断言的是用户实际看到的那一行，而不是 picocli 的默认渲染。 */
    private Result execute(Path cassetteDir, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        String[] full = Stream.concat(Stream.of(args), Stream.of(
                        "-d", cassetteDir.toString(),
                        "--config", dir.resolve("absent-config.json").toString()))
                .toArray(String[]::new);

        int exitCode = JevTapeCli.commandLine()
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err))
                .execute(full);
        return new Result(exitCode, out.toString().replace(System.lineSeparator(), "\n"), err.toString());
    }
}
