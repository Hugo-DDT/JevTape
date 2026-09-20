package io.jevtape.shared;

/** cassette 存储无法读取或写入。 */
public final class StorageFailure extends JevTapeException {

    public StorageFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
