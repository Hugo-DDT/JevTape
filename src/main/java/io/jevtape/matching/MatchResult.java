package io.jevtape.matching;

import io.jevtape.cassette.Cassette;

import java.util.Objects;

/**
 * 一次匹配决策的结果：命中某一个 cassette，或者一个都没命中（charter §46）。
 *
 * <p>matching 只产出决策，不消费它 —— 怎么应答是 transport 的事，怎么渲染是 CLI 的事，
 * miss 之后走哪条策略是 miss policy 的事。
 */
public sealed interface MatchResult {

    /** STRICT 命中的那一个 cassette。 */
    record Hit(Cassette cassette) implements MatchResult {

        public Hit {
            Objects.requireNonNull(cassette, "cassette");
        }
    }

    /**
     * 没有任何 cassette 命中。{@code requestFingerprint} 是这次请求算出来的 replay key —— miss 诊断
     * 以它为起点（charter §32）。
     */
    record Miss(String requestFingerprint) implements MatchResult {

        public Miss {
            Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        }
    }
}
