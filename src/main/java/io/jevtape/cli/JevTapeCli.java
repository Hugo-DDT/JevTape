package io.jevtape.cli;

import io.jevtape.shared.JevTapeException;
import io.jevtape.shared.JevTapeVersion;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "jevtape",
        mixinStandardHelpOptions = true,
        description = "Record / Replay tool for Jev decisions.",
        subcommands = {
                RecordCommand.class,
                ReplayCommand.class,
                InspectCommand.class,
                VerifyCommand.class,
                DiffCommand.class,
                SimulateCommand.class,
                ListCommand.class,
                DoctorCommand.class
        })
public final class JevTapeCli implements Runnable {

    @Spec
    private CommandSpec spec;

    static CommandLine commandLine() {
        CommandLine commandLine = new CommandLine(new JevTapeCli())
                // ponytail: 纯文本输出让 help 与诊断信息对测试和 diff 保持逐字节稳定；
                // 如果将来需要颜色，把工厂换成 Ansi.AUTO 即可。
                .setHelpFactory((spec, ansi) -> new CommandLine.Help(spec, CommandLine.Help.Ansi.OFF))
                .setExecutionExceptionHandler(JevTapeCli::renderError);

        String version = "jevtape " + JevTapeVersion.current();
        commandLine.getCommandSpec().version(version);
        commandLine.getSubcommands().values().forEach(sub -> sub.getCommandSpec().version(version));
        return commandLine;
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /** 预期内的失败只渲染为一行；其他异常保留完整堆栈跟踪。 */
    private static int renderError(Exception ex, CommandLine cmd, CommandLine.ParseResult parseResult) throws Exception {
        if (ex instanceof JevTapeException) {
            cmd.getErr().println("jevtape: " + ex.getMessage());
            return 1;
        }
        throw ex;
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
