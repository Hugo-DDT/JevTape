package io.jevtape.cassette;

/**
 * 一次 Jev 调用留下的全部内容：元数据、请求、响应和 fingerprint（charter §23）。
 *
 * <p>Phase 1 中每个 cassette 恰好存储一个 interaction（charter §22），因此这四组字段位于文档根节点，
 * 而这个 record 就是 {@link Cassette} 交给只关心调用本身的调用方的形态。
 */
public record Interaction(CassetteMetadata metadata,
                          RecordedRequest request,
                          RecordedResponse response,
                          Fingerprints fingerprints) {
}
