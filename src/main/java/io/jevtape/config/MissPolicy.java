package io.jevtape.config;

import io.jevtape.shared.ConfigurationError;

/**
 * 磁带没有答案时怎么办（charter §33）。默认是 {@link #ERROR}：停下并报错，绝不"找不到就去请求线上" ——
 * 否则 CI 里会出现危险的非确定行为。{@link #LIVE} 与 {@link #RECORD} 会让 replay 联网，因此必须由用户
 * 显式启用。
 */
public enum MissPolicy {

    /** 停止并返回 {@code JEVTAPE_REPLAY_MISS}。 */
    ERROR,

    /** 转发真实 Jev，但不保存。 */
    LIVE,

    /** 转发真实 Jev，并把这一次调用录成新 cassette。 */
    RECORD;

    /** 配置里写的是小写名字；认不出来就是配置错误，而不是悄悄退回默认值。 */
    public static MissPolicy of(String value) {
        return switch (value) {
            case "error" -> ERROR;
            case "live" -> LIVE;
            case "record" -> RECORD;
            default -> throw new ConfigurationError(
                    "onMiss must be one of [error, live, record], got: " + value);
        };
    }
}
