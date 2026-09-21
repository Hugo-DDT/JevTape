package io.jevtape.contract;

import java.util.ArrayList;
import java.util.List;

/**
 * simulate 要往应答里写的作答覆盖（charter §11, §20）：把 confidence 压到应用的阈值之下、换一个 choice、
 * 改一个 score、改 Noul 的 probability。
 *
 * <p>{@code question} 为 null 表示"每一个已经有该字段的答案"。覆盖只落在**已经存在**的字段上，绝不凭空
 * 造一个上游从来不会回的字段（例如给 Score 的答案加一个 confidence）—— 那样测出来的就不是应用的边界，
 * 而是它对一个假响应的解析。字段名到 JSON 的映射由 {@link JevProtocolAdapter} 完成。
 */
public record AnswerOverrides(String question, Double confidence, String choice, Double score,
                              Double probability) {

    /** 什么都不覆盖：此时应答逐字节就是磁带里录的那一份。 */
    public static final AnswerOverrides NONE = new AnswerOverrides(null, null, null, null, null);

    public boolean empty() {
        return fields().isEmpty();
    }

    /**
     * 每一项覆盖的名字与值文本，顺序固定。banner 与"这些覆盖落不到任何答案上"的错误消息共用这一份，
     * 于是用户在两处看到的是同一个说法。
     */
    public List<Field> fields() {
        List<Field> fields = new ArrayList<>();
        if (confidence != null) {
            fields.add(new Field("confidence", String.valueOf(confidence)));
        }
        if (choice != null) {
            fields.add(new Field("choice", choice));
        }
        if (score != null) {
            fields.add(new Field("score", String.valueOf(score)));
        }
        if (probability != null) {
            fields.add(new Field("probability", String.valueOf(probability)));
        }
        return List.copyOf(fields);
    }

    /** 一项覆盖在输出里怎么称呼自己。 */
    public record Field(String name, String value) {

        @Override
        public String toString() {
            return name + " " + value;
        }
    }
}
