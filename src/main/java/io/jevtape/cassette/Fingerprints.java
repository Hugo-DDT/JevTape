package io.jevtape.cassette;

/**
 * 一次被记录调用的三个 fingerprint，每个都是规范 JSON 上的 {@code sha256:<hex>}（charter §28）。
 * {@code request} 是回放的键；{@code contract} 与 {@code state} 分开保留，以便未命中时能指出是哪一部分
 * 发生了变化（charter §29, §32）。
 *
 * <p>由 fingerprint 模块计算 —— 本 record 只存储结果。
 */
public record Fingerprints(String request, String contract, String state) {
}
