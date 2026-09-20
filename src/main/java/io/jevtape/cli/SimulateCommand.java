package io.jevtape.cli;

import picocli.CommandLine.Command;

@Command(name = "simulate",
        mixinStandardHelpOptions = true,
        description = "Replay with injected latency, errors, or low-confidence responses.")
final class SimulateCommand extends PlannedCommand {

    SimulateCommand() {
        super("v0.5.0");
    }
}
