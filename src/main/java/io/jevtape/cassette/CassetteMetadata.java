package io.jevtape.cassette;

/**
 * 一个 cassette 是在何时、由什么记录下来的（charter §25, §54）。
 *
 * <p>{@code recordedAt} 是精确到秒的 ISO-8601 UTC 时刻，例如 {@code 2026-09-20T10:20:30Z} ——
 * 这是格式所承诺的文本形式，保留为文本以便文件保持可读并按字典序排序。
 */
public record CassetteMetadata(String recordedAt, String jevtapeVersion, long durationMs) {
}
