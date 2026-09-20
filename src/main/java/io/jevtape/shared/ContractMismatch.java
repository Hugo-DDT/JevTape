package io.jevtape.shared;

/** The current Decision Contract differs from the one recorded in the cassette. */
public final class ContractMismatch extends JevTapeException {

    public ContractMismatch(String message) {
        super(message);
    }
}
