package io.jevtape.transport;

/**
 * Sends one Jev request and returns the response.
 *
 * <p>Live forwarding, cassette replay and recording all implement this interface, so the core
 * never depends on a concrete HTTP client or server framework. {@link io.jevtape.shared.JevTapeException}
 * subclasses signal failures; every HTTP status the upstream answers with is a normal response.
 */
public interface JevTransport {

    JevResponse send(JevRequest request);
}
