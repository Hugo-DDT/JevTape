package io.jevtape.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class JevTapeCliTest {

    @Test
    void usageHelpNamesTheTool() {
        assertThat(new CommandLine(new JevTapeCli()).getUsageMessage(CommandLine.Help.Ansi.OFF)).contains("jevtape");
    }
}
