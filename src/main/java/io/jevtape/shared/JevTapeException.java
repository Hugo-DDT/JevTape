package io.jevtape.shared;

/**
 * JevTape 显式错误分类体系的根类。声明为抽象类，确保任何失败都必须以明确的类型抛出；
 * CLI 会将这些错误渲染为单行输出而非堆栈跟踪。
 */
public abstract class JevTapeException extends RuntimeException {

    protected JevTapeException(String message) {
        super(message);
    }

    protected JevTapeException(String message, Throwable cause) {
        super(message, cause);
    }
}
