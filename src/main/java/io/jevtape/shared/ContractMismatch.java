package io.jevtape.shared;

/** 当前 Decision Contract 与 cassette 中记录的不一致。 */
public final class ContractMismatch extends JevTapeException {

    public ContractMismatch(String message) {
        super(message);
    }
}
