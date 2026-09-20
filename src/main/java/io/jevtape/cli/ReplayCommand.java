package io.jevtape.cli;

import picocli.CommandLine.Command;

@Command(name = "replay",
        mixinStandardHelpOptions = true,
        description = "Replay recorded Jev decisions from cassettes, fully offline.")
final class ReplayCommand extends PlannedCommand {

    ReplayCommand() {
        super("v0.1.0");
    }
}
