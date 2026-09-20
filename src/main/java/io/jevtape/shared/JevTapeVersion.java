package io.jevtape.shared;

/**
 * 本构建的版本号，取自 shaded jar 的 manifest（{@code Implementation-Version}）。
 * 从 {@code target/classes} 直接运行时没有 manifest，于是报 {@code dev}。
 */
public final class JevTapeVersion {

    private static final String DEVELOPMENT = "dev";

    private JevTapeVersion() {
    }

    public static String current() {
        String version = JevTapeVersion.class.getPackage().getImplementationVersion();
        return version == null ? DEVELOPMENT : version;
    }
}
