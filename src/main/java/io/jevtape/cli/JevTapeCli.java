package io.jevtape.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "jevtape",
        mixinStandardHelpOptions = true,
        description = "Record / Replay tool for Jev decisions.")
public final class JevTapeCli implements Runnable {

    public static void main(String[] args) {
        System.exit(new CommandLine(new JevTapeCli()).execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
