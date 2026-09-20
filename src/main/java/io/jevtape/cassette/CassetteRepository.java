package io.jevtape.cassette;

import java.util.List;

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

    /**
     * 读出该存放位置里的全部 cassette，按名称排序，因此装载顺序在任何机器上都一样 —— replay 的
     * "第一个命中者胜出"由此才是确定的。
     *
     * <p>名字不符合 cassette 命名规则的文件会被跳过：它们不可能是 JevTape 写出来的。而看起来是 cassette
     * 却读不动的文件一定抛错 —— 静默跳过只会把损坏变成一个查不出原因的 MISS。
     *
     * @throws io.jevtape.shared.CassetteNotFound 当该存放位置根本不存在时
     * @throws io.jevtape.shared.CassetteCorrupted 当某个文件不是可读的 v1 文档时
     * @throws io.jevtape.shared.CassetteVersionUnsupported 当某个文件声明了本构建无法读取的版本时
     * @throws io.jevtape.shared.StorageFailure 当该位置无法列出或某个文件无法读取时
     */
    List<Cassette> loadAll();
}
