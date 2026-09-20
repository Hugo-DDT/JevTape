package io.jevtape.cli;

import picocli.CommandLine.Command;

@Command(name = "verify",
        mixinStandardHelpOptions = true,
        description = "Compare the current request contract against the recorded one.")
final class VerifyCommand extends PlannedCommand {

    VerifyCommand() {
        super("v0.3.0");
    }
}
