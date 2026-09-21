package io.jevtape.contract;

import io.jevtape.contract.DecisionContract.Question;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 两个 Decision Contract 的比较结果（charter §18）：逐项的结构变化，加上 PASS / WARN / FAIL。
 *
 * <p>状态由指纹与变化的形状各管一半。contract fingerprint 相等就是 PASS —— 指纹是 replay 唯一认的东西，
 * 诊断不能比它更宽松。指纹不等时再看变化长什么样：只有新增（多了 question、多了可选项）是 WARN，既有内容
 * 被改动、删除或重排是 FAIL。全程只有相等比较，没有相似度、没有语义归一化（不变量 5）。
 *
 * <p>指纹不等而结构比较一无所获，说明变的是这个模型不认识的字段（例如某个 question 上多出来的属性）——
 * 此时判 FAIL 而不是 PASS：宁可说"变了，但我说不清哪里"，也不能在 replay 必定 MISS 的时候亮绿灯。
 */
public record ContractDiff(String recordedContract, String currentContract,
                           List<Change> changes, Status status) {

    public enum Status { PASS, WARN, FAIL }

    public ContractDiff {
        changes = changes == null ? List.of() : List.copyOf(changes);
        Objects.requireNonNull(status, "status");
    }

    /** 变化按"录制里的顺序优先、新增的排在后面"输出，因此同一对契约比两次得到同一份诊断。 */
    public static ContractDiff between(String recordedContract, String currentContract,
                                       DecisionContract recorded, DecisionContract current) {
        Objects.requireNonNull(recorded, "recorded");
        Objects.requireNonNull(current, "current");
        Map<String, Question> before = byName(recorded);
        Map<String, Question> after = byName(current);

        List<Change> changes = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) {
            Question old = before.get(name);
            Question now = after.get(name);
            if (old == null) {
                changes.add(new Change(Change.Kind.QUESTION_ADDED, name, "question", null, null));
            } else if (now == null) {
                changes.add(new Change(Change.Kind.QUESTION_REMOVED, name, "question", null, null));
            } else {
                compareQuestion(name, old, now, changes);
            }
        }
        return new ContractDiff(recordedContract, currentContract, changes,
                status(recordedContract, currentContract, changes));
    }

    /** 指纹相等就是 PASS，哪怕结构比较还有话要说 —— 指纹才是 replay 认的那一个。 */
    private static Status status(String recorded, String current, List<Change> changes) {
        if (recorded != null && recorded.equals(current)) {
            return Status.PASS;
        }
        if (changes.isEmpty()) {
            // 某一边的指纹缺失（人可以手改 cassette）时只剩结构比较可依据，它说没变就是没变。
            return recorded == null || current == null ? Status.PASS : Status.FAIL;
        }
        return changes.stream().allMatch(Change::addition) ? Status.WARN : Status.FAIL;
    }

    private static void compareQuestion(String name, Question old, Question now, List<Change> changes) {
        if (!Objects.equals(old.type(), now.type())) {
            changes.add(new Change(Change.Kind.TYPE_CHANGED, name, "type", old.type(), now.type()));
        }
        if (!Objects.equals(old.instructions(), now.instructions())) {
            changes.add(new Change(Change.Kind.INSTRUCTIONS_CHANGED, name, "instructions", null, null));
        }

        Map<String, String> before = criteria(old);
        Map<String, String> after = criteria(now);
        // 新增与改动按今天的类型称呼，删除按录制时的类型称呼 —— 类型本身变了的时候两边不是同一个词。
        after.forEach((label, text) -> {
            if (!before.containsKey(label)) {
                changes.add(new Change(Change.Kind.CRITERION_ADDED, name,
                        subject(now.criteriaNoun(), label), null, null));
            } else if (!Objects.equals(before.get(label), text)) {
                changes.add(new Change(Change.Kind.CRITERION_CHANGED, name,
                        subject(now.criteriaNoun(), label), null, null));
            }
        });
        before.keySet().stream()
                .filter(label -> !after.containsKey(label))
                .forEach(label -> changes.add(new Change(Change.Kind.CRITERION_REMOVED, name,
                        subject(old.criteriaNoun(), label), null, null)));

        // array 保序参与指纹（docs/matching.md），因此可选项换个顺序也是契约变化，即使内容一字未改。
        List<String> keptInRecordedOrder = before.keySet().stream().filter(after::containsKey).toList();
        List<String> keptInCurrentOrder = after.keySet().stream().filter(before::containsKey).toList();
        if (!keptInRecordedOrder.equals(keptInCurrentOrder)) {
            changes.add(new Change(Change.Kind.ORDER_CHANGED, name, "order",
                    String.join(", ", keptInRecordedOrder), String.join(", ", keptInCurrentOrder)));
        }
    }

    /**
     * 可选项按标签索引，于是"新增 / 删除 / 文本变了"是查表查出来的，不是按位置猜出来的。
     *
     * <p>ponytail: 同一个 question 里出现两个同名可选项时后者覆盖前者（JSON object 的 key 天然唯一，
     * array 里的 value 却没有约束）；天花板是"重复标签被当成一项"，届时把值换成 List 即可。
     */
    private static Map<String, String> criteria(Question question) {
        Map<String, String> result = new LinkedHashMap<>();
        question.criteria().forEach(criterion -> result.put(criterion.label(), criterion.text()));
        return result;
    }

    private static Map<String, Question> byName(DecisionContract contract) {
        Map<String, Question> result = new LinkedHashMap<>();
        contract.questions().forEach(question -> result.put(question.name(), question));
        return result;
    }

    private static String subject(String noun, String label) {
        return label == null || label.isEmpty() ? noun : noun + " " + label;
    }

    /**
     * 一条变化。{@code subject} 是"变了什么"的短语（{@code option billing}、{@code instructions}），
     * {@code before} / {@code after} 只在两侧都值得原样打出来时才非 null（类型、顺序）—— criteria 正文可以
     * 很长，打在终端上只会盖住真正要看的东西，因此改动只报"这一条变了"。
     */
    public record Change(Kind kind, String question, String subject, String before, String after) {

        public enum Kind {
            QUESTION_ADDED, QUESTION_REMOVED, TYPE_CHANGED, INSTRUCTIONS_CHANGED,
            CRITERION_ADDED, CRITERION_REMOVED, CRITERION_CHANGED, ORDER_CHANGED
        }

        public Change {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(question, "question");
            Objects.requireNonNull(subject, "subject");
        }

        /** 只有纯新增能让整份契约停在 WARN：旧的录制仍然回答了它当时被问到的那些问题。 */
        public boolean addition() {
            return kind == Kind.QUESTION_ADDED || kind == Kind.CRITERION_ADDED;
        }
    }
}
