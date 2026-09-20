package io.jevtape.cli;

import picocli.CommandLine.Command;

@Command(name = "record",
        mixinStandardHelpOptions = true,
        description = "Record Jev decisions into a cassette.")
final class RecordCommand extends PlannedCommand {

    RecordCommand() {
        super("v0.1.0");
    }
}
