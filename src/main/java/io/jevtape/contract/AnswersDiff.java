package io.jevtape.contract;

import io.jevtape.contract.DecisionAnswers.Answer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 两盘磁带的作答比较（charter §19）：按 question 分组的逐项 {@code old → new}。
 *
 * <p>只有真的有差异的 question 才出现在结果里；出现的那一个则把它的全部字段都列出来，包括没变的那些 ——
 * 看到 {@code choice billing → billing} 才知道"选项没换，只是概率挪了"，只打变化的那几行反而读不出这件事。
 *
 * <p>diff 是"数据差异"，不是"哪个结果更好"的评测系统（charter §19），因此这里没有 PASS / WARN / FAIL，
 * 也没有任何阈值：全程只有相等比较，没有相似度、没有"这两个概率差不多"（不变量 5）。
 */
public record AnswersDiff(List<Question> questions) {

    /** 一个 question 在哪一边存在。只在一边有，本身就是最值得说出来的那种差异。 */
    public enum Presence { BOTH, ONLY_OLD, ONLY_NEW }

    public AnswersDiff {
        questions = questions == null ? List.of() : List.copyOf(questions);
    }

    public static AnswersDiff between(DecisionAnswers old, DecisionAnswers now) {
        Objects.requireNonNull(old, "old");
        Objects.requireNonNull(now, "now");
        Map<String, Answer> before = byName(old);
        Map<String, Answer> after = byName(now);

        // 顺序是"旧磁带优先、只在新磁带里的排在后面"，于是同一对磁带比两次得到同一份报告。
        Set<String> names = new LinkedHashSet<>(before.keySet());
        names.addAll(after.keySet());

        List<Question> questions = new ArrayList<>();
        for (String name : names) {
            Answer left = before.get(name);
            Answer right = after.get(name);
            Presence presence = left == null ? Presence.ONLY_NEW : right == null ? Presence.ONLY_OLD : Presence.BOTH;
            List<Field> fields = fields(left, right);
            if (presence != Presence.BOTH || fields.stream().anyMatch(Field::changed)) {
                questions.add(new Question(name, presence, fields));
            }
        }
        return new AnswersDiff(questions);
    }

    /** 两盘的作答一模一样（或两边都没有作答）时为空 —— 调用方据此说出"没有差异"。 */
    public boolean empty() {
        return questions.isEmpty();
    }

    /**
     * 概率分布先、其余字段后（charter §19 的顺序：先看完整个分布，再看它选了什么、有多确信）。
     * 两边都按标签查表，因此"新增一个选项"是查出来的，不是按位置猜出来的。
     */
    private static List<Field> fields(Answer left, Answer right) {
        List<Field> fields = new ArrayList<>();
        rows(probabilities(left), probabilities(right), fields);
        rows(scalars(left), scalars(right), fields);
        return fields;
    }

    private static void rows(Map<String, String> before, Map<String, String> after, List<Field> sink) {
        Set<String> labels = new LinkedHashSet<>(before.keySet());
        labels.addAll(after.keySet());
        labels.forEach(label -> sink.add(new Field(label, before.get(label), after.get(label))));
    }

    private static Map<String, String> probabilities(Answer answer) {
        return answer == null ? Map.of() : answer.probabilities();
    }

    private static Map<String, String> scalars(Answer answer) {
        return answer == null ? Map.of() : answer.scalars();
    }

    private static Map<String, Answer> byName(DecisionAnswers answers) {
        Map<String, Answer> result = new LinkedHashMap<>();
        answers.answers().forEach(answer -> result.put(answer.name(), answer));
        return result;
    }

    /** 一个 question 的差异。{@code fields} 是它的全部字段，不只是变了的那些（见类注释）。 */
    public record Question(String name, Presence presence, List<Field> fields) {

        public Question {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(presence, "presence");
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /** 一项字段的两边取值；某一边为 null 表示那一边根本没有这个字段（或整个 question 不存在）。 */
    public record Field(String label, String before, String after) {

        public Field {
            Objects.requireNonNull(label, "label");
        }

        public boolean changed() {
            return !Objects.equals(before, after);
        }
    }
}
