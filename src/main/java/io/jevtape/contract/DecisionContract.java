package io.jevtape.contract;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 一次决策的 Decision Contract（charter §18、§44）：这次请求究竟在问什么 —— 有哪些 question、各自是什么
 * 类型、instructions 怎么写、有哪些带 criteria 的可选项。它就是 contract fingerprint 覆盖的那些内容的领域
 * 模型，于是 verify 能说出"哪一条 criteria 变了"，而不只是"指纹不一样"。
 *
 * <p>三种 question 刻意归一成同一种形状："一个标签（可能没有名字）+ 一段 criteria 文本"，因此一次比较只需要
 * 一条路径而不是三条。官方协议里三类的可选项都叫 {@code criteria}（Choice 是 map、Score 是有序 array、
 * Noul 是 {@code true} / {@code false} object），v1 磁带则是 {@code options[]} / {@code levels[]} 与一条
 * 字符串；两种形状到这一层的映射全部由 {@link JevProtocolAdapter} 完成，本类型不认识任何 Jev 的字段名。
 */
public record DecisionContract(List<Question> questions) {

    public DecisionContract {
        questions = questions == null ? List.of() : List.copyOf(questions);
    }

    /**
     * 一个 question，顺序就是请求里的顺序。
     *
     * <p>{@code criteriaNoun} 是这一类 question 的可选项在诊断里该叫什么（Choice → option、Score → level、
     * Noul → criteria）。这属于 Jev 的知识，因此由解析方给出，而不是让渲染的一方去猜。
     *
     * <p>{@code instructions} 保留原始 JSON：官方允许它是 string、object、array 或 null，只留字符串会把
     * 结构化的说明整段丢掉，于是两份不同的 instructions 在契约里看起来一样。没写时是 JSON null，不是 Java
     * null —— 比较因此永远是一次相等判断。
     */
    public record Question(String name, String type, String criteriaNoun, JsonNode instructions,
                           List<Criterion> criteria) {

        public Question {
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
        }
    }

    /**
     * 一条带 criteria 的可选项。{@code label} 是它的名字：Choice 的选项值、Score 的等级下标、官方 Noul 的
     * {@code true} / {@code false}；空串表示它没有名字 —— v1 的 Noul 只有一条 criteria，没有可选项。
     * {@code text} 是 null 表示请求里根本没写 criteria，与写了空字符串是两件事。
     */
    public record Criterion(String label, String text) {
    }
}
