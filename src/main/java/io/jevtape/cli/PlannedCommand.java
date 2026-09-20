package io.jevtape.cli;

import picocli.CommandLine.IExitCodeGenerator;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Shared behaviour of every command whose implementation lands in a later roadmap task. */
abstract class PlannedCommand implements Runnable, IExitCodeGenerator {

    /** Distinct from picocli's USAGE (2) and SOFTWARE (1) so scripts can tell the three apart. */
    static final int EXIT_NOT_IMPLEMENTED = 3;

    @Spec
    private CommandSpec spec;

    private final String milestone;

    protected PlannedCommand(String milestone) {
        this.milestone = milestone;
    }

    @Override
    public final void run() {
        String suffix = milestone == null ? "" : " (planned for " + milestone + ")";
        spec.commandLine().getErr().println("jevtape " + spec.name() + " is not implemented yet" + suffix + ".");
    }

    @Override
    public final int getExitCode() {
        return EXIT_NOT_IMPLEMENTED;
    }
}
