package io.jevtape.cli;

import picocli.CommandLine.IExitCodeGenerator;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** 还没实现的命令的共享行为：一句提示，加上一个属于自己的退出码。 */
abstract class PlannedCommand implements Runnable, IExitCodeGenerator {

    /** 与 picocli 的 USAGE（2）和 SOFTWARE（1）取值不同，脚本可据此区分三者。 */
    static final int EXIT_NOT_IMPLEMENTED = 3;

    @Spec
    private CommandSpec spec;

    @Override
    public final void run() {
        spec.commandLine().getErr().println("jevtape " + spec.name() + " is not implemented yet.");
    }

    @Override
    public final int getExitCode() {
        return EXIT_NOT_IMPLEMENTED;
    }
}
