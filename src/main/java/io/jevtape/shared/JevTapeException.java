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

    /**
     * 写给客户端的错误标识。默认就是类型名；需要跨进程边界稳定引用的失败（例如 replay miss 的
     * {@code JEVTAPE_REPLAY_MISS}，charter §32）由子类覆写成一个不随重命名变化的码。
     */
    public String code() {
        return getClass().getSimpleName();
    }
}
