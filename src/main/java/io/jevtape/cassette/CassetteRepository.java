package io.jevtape.cassette;

/**
 * cassette 存放的位置。只负责存储：不做匹配、不做 fingerprint 计算、不联网（charter §43）。
 */
public interface CassetteRepository {

    /**
     * @param name 不含目录或 {@code .json} 后缀的 cassette 名称
     * @throws io.jevtape.shared.CassetteNotFound 当不存在这样的 cassette 时
     * @throws io.jevtape.shared.CassetteCorrupted 当该文件不是可读的 v1 文档时
     * @throws io.jevtape.shared.CassetteVersionUnsupported 当它声明了本构建无法读取的版本，
     *         或根本没有声明任何版本时
     * @throws io.jevtape.shared.ConfigurationError 当该名称不是安全的 cassette 名称时
     */
    Cassette read(String name);

    /**
     * 以 {@code cassette} 自身的名称写入，替换该位置已存储的任何 cassette。
     *
     * @throws io.jevtape.shared.StorageFailure 当文件无法写入时
     * @throws io.jevtape.shared.ConfigurationError 当该名称不是安全的 cassette 名称时
     */
    void write(Cassette cassette);
}
