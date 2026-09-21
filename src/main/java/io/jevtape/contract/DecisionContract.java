package io.jevtape.contract;

import java.util.List;

/**
 * 一次决策的 Decision Contract（charter §18、§44）：这次请求究竟在问什么 —— 有哪些 question、各自是什么
 * 类型、instructions 怎么写、有哪些带 criteria 的可选项。它就是 contract fingerprint 覆盖的那些内容的领域
 * 模型，于是 verify 能说出"哪一条 criteria 变了"，而不只是"指纹不一样"。
 *
 * <p>三种 question 刻意归一成同一种形状：Choice 的 options、Score 的 levels 与 Noul 的单条 criteria 都是
 * "一个标签（可能没有名字）+ 一段 criteria 文本"，因此一次比较只需要一条路径而不是三条。JSON 字段名到这一
 * 层的映射全部由 {@link JevProtocolAdapter} 完成，本类型不认识任何 Jev 的字段名。
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
     */
    public record Question(String name, String type, String criteriaNoun, String instructions,
                           List<Criterion> criteria) {

        public Question {
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
        }
    }

    /**
     * 一条带 criteria 的可选项。{@code label} 是空串表示它没有名字 —— Noul 只有一条 criteria，没有可选项；
     * {@code text} 是 null 表示请求里根本没写 criteria，与写了空字符串是两件事。
     */
    public record Criterion(String label, String text) {
    }
}
