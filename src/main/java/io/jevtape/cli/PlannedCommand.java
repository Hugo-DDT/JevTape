package io.jevtape.cli;

import picocli.CommandLine.IExitCodeGenerator;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** 所有实现排在后续路线图任务中的命令的共享行为。 */
abstract class PlannedCommand implements Runnable, IExitCodeGenerator {

    /** 与 picocli 的 USAGE（2）和 SOFTWARE（1）取值不同，脚本可据此区分三者。 */
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
