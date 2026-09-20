package io.jevtape.config;

/** 优先级合并之后解析完成的 JevTape 配置。 */
public record JevTapeConfig(String listen, int port, String cassetteDir, String match, MissPolicy onMiss) {

    public static final JevTapeConfig DEFAULTS =
            new JevTapeConfig("127.0.0.1", 8787, ".jevtape/cassettes", "strict", MissPolicy.ERROR);
}
