package io.jevtape.config;

/** Resolved JevTape configuration after precedence merging. */
public record JevTapeConfig(String listen, int port, String cassetteDir, String match, String onMiss) {

    public static final JevTapeConfig DEFAULTS =
            new JevTapeConfig("127.0.0.1", 8787, ".jevtape/cassettes", "strict", "error");
}
