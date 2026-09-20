package io.jevtape.cli;

import picocli.CommandLine.Command;

@Command(name = "list",
        mixinStandardHelpOptions = true,
        description = "List every cassette in the cassette directory.")
final class ListCommand extends PlannedCommand {

    ListCommand() {
        super("v0.2.0");
    }
}
