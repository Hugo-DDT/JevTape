package io.jevtape.cli;

import picocli.CommandLine.Command;

@Command(name = "inspect",
        mixinStandardHelpOptions = true,
        description = "Show a cassette summary without opening the JSON.")
final class InspectCommand extends PlannedCommand {

    InspectCommand() {
        super("v0.2.0");
    }
}
