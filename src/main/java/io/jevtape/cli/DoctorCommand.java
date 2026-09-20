package io.jevtape.cli;

import picocli.CommandLine.Command;

@Command(name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Diagnose the local JevTape configuration.")
final class DoctorCommand extends PlannedCommand {

    DoctorCommand() {
        super(null);
    }
}
