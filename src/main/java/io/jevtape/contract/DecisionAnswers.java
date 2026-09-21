package io.jevtape.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次应答里 Jev 给出的作答（charter §17, §19）：每个 question 的答案，按应答里的原有顺序。
 *
 * <p>刻意不做字段投影：除了 Choice 的概率分布单独成表，其余字段一律原样留在 {@code scalars} 里
 * （JSON 原有顺序，值取文本形式），因此上游多回一个 JevTape 不认识的字段时，diff 看得见它变了，而不是
 * 把它悄悄丢掉 —— 漏掉一个字段会让"这两盘磁带答得一样"变成一个错误的结论，那比报不出差异更危险。
 *
 * <p>JSON 字段名到这一层的映射全部由 {@link JevProtocolAdapter} 完成，本类型不认识任何 Jev 的字段名。
 */
public record DecisionAnswers(List<Answer> answers) {

    /** 没有作答可谈（4xx/5xx 的应答通常没有 {@code answers}）时就是它，于是 diff 能说出"两边都没有答案"。 */
    public static final DecisionAnswers NONE = new DecisionAnswers(List.of());

    public DecisionAnswers {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    /**
     * 一个 question 的答案。{@code probabilities} 是 Choice 的概率分布（选项名 → 概率），单独成表是为了
     * 让 diff 能按选项逐项对齐；{@code scalars} 是其余全部字段（choice / score / confidence /
     * probability 以及任何未知字段）。
     *
     * <p>值都是文本而不是 {@code Double}，因此 {@code 0.81} 不会被格式化改写成 {@code 0.8100000000000001}，
     * 打出来的就是磁带里写着的那一个数。两个表都保序，于是同一对磁带比两次得到同一份诊断。
     */
    public record Answer(String name, Map<String, String> probabilities, Map<String, String> scalars) {

        public Answer {
            probabilities = ordered(probabilities);
            scalars = ordered(scalars);
        }

        private static Map<String, String> ordered(Map<String, String> values) {
            return values == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }
}
