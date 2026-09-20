package io.jevtape.cassette;

import java.util.Objects;

/**
 * 一个 cassette 文档：一次被记录的 Jev interaction，加上在磁盘上标识它的两个字段
 * {@code schemaVersion} 与 {@code name}（charter §22, §25）。
 *
 * <p>分量的顺序就是磁盘上的字段顺序，且属于已发布格式的一部分 —— 重排它们会重写每一个已提交的
 * cassette。
 */
public record Cassette(int schemaVersion,
                       String name,
                       CassetteMetadata metadata,
                       RecordedRequest request,
                       RecordedResponse response,
                       Fingerprints fingerprints) {

    /** 本构建写入的版本，也是它读取的唯一版本（charter §26）。 */
    public static final int SCHEMA_VERSION = 1;

    public Cassette {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(fingerprints, "fingerprints");
    }

    /** 把一个被记录的 interaction 包装成当前 schema 版本的一个 cassette。 */
    public static Cassette of(String name, Interaction interaction) {
        Objects.requireNonNull(interaction, "interaction");
        return new Cassette(SCHEMA_VERSION, name, interaction.metadata(), interaction.request(),
                interaction.response(), interaction.fingerprints());
    }

    public Interaction interaction() {
        return new Interaction(metadata, request, response, fingerprints);
    }
}
