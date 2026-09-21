package io.jevtape.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.jevtape.shared.CassetteNotFound;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;
import picocli.CommandLine.Command;

class JevTapeCliTest {

    private static final List<String> COMMANDS =
            List.of("record", "replay", "inspect", "verify", "diff", "simulate", "list", "doctor");

    /** 已经落地的命令不在此列 —— 裸跑 `record` / `replay` 会启动代理并一直阻塞，`inspect` / `list` 另有测试。 */
    private static final List<String> PLANNED =
            List.of("verify", "diff", "simulate", "doctor");

    private static Stream<String> commands() {
        return COMMANDS.stream();
    }

    private static Stream<String> planned() {
        return PLANNED.stream();
    }

    private record Result(int exitCode, String out, String err) {}

    private static Result execute(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode = JevTapeCli.commandLine()
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err))
                .execute(args);
        return new Result(exitCode, out.toString(), err.toString());
    }

    @Test
    void rootHelpNamesTheToolAndListsEveryCommand() {
        Result result = execute("--help");

        assertThat(result.exitCode()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).contains("Usage: jevtape").contains(COMMANDS);
    }

    @Test
    void bareInvocationPrintsUsageInsteadOfFailing() {
        Result result = execute();

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("Usage: jevtape").contains(COMMANDS);
    }

    @ParameterizedTest
    @MethodSource("commands")
    void everySubcommandHasWorkingHelp(String command) {
        Result result = execute(command, "--help");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).startsWith("Usage: jevtape " + command);
    }

    @ParameterizedTest
    @MethodSource("planned")
    void unimplementedSubcommandsPrintAHintAndFailWithTheirOwnExitCode(String command) {
        Result result = execute(command);

        assertThat(result.exitCode()).isEqualTo(PlannedCommand.EXIT_NOT_IMPLEMENTED);
        assertThat(result.err()).contains("jevtape " + command + " is not implemented yet");
        assertThat(result.out()).isEmpty();
    }

    @Test
    void plannedMilestonesComeFromTheRoadmap() {
        assertThat(execute("verify").err()).contains("planned for v0.3.0");
        assertThat(execute("diff").err()).contains("planned for v0.4.0");
        assertThat(execute("simulate").err()).contains("planned for v0.5.0");
        assertThat(execute("doctor").err()).doesNotContain("planned for");
    }

    @Test
    void versionIsReportedForTheRootAndForSubcommands() {
        assertThat(execute("-V").out()).startsWith("jevtape ");
        assertThat(execute("record", "-V").out()).startsWith("jevtape ");
    }

    @Test
    void expectedFailuresRenderAsOneLineWithoutStackTrace() {
        StringWriter err = new StringWriter();
        CommandLine commandLine = JevTapeCli.commandLine().addSubcommand("boom", new Boom());

        int exitCode = commandLine.setErr(new PrintWriter(err)).execute("boom");

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("jevtape: no cassette named demo.json").doesNotContain("\tat ");
    }

    @Command(name = "boom")
    private static final class Boom implements Runnable {

        @Override
        public void run() {
            throw new CassetteNotFound("no cassette named demo.json");
        }
    }
}
