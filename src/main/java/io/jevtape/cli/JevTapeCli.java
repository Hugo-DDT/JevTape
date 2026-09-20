package io.jevtape.cli;

import io.jevtape.shared.JevTapeException;
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
                // ponytail: plain output keeps help and diagnostics byte-stable for tests and diffs;
                // switch the factory to Ansi.AUTO if color is ever wanted.
                .setHelpFactory((spec, ansi) -> new CommandLine.Help(spec, CommandLine.Help.Ansi.OFF))
                .setExecutionExceptionHandler(JevTapeCli::renderError);

        String version = "jevtape " + implementationVersion();
        commandLine.getCommandSpec().version(version);
        commandLine.getSubcommands().values().forEach(sub -> sub.getCommandSpec().version(version));
        return commandLine;
    }

    /** Reads Implementation-Version from the shaded jar; "dev" when running from target/classes. */
    private static String implementationVersion() {
        String version = JevTapeCli.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /** Expected failures render as one line; anything else keeps its stack trace. */
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
