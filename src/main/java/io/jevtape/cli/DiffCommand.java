package io.jevtape.cli;

import picocli.CommandLine.Command;

@Command(name = "diff",
        mixinStandardHelpOptions = true,
        description = "Compare two cassettes: choices, scores, probabilities, confidence.")
final class DiffCommand extends PlannedCommand {

    DiffCommand() {
        super("v0.4.0");
    }
}
