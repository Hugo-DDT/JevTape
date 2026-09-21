package io.jevtape.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读命令（inspect / verify）共用的分节渲染：标题、横线、按最长标签对齐的行（charter §17）。一节里没有可
 * 打印的行就整节省略，于是没有内容可报的命令不会摆出一个只有标题的空壳。
 */
final class Report {

    /** 分节横线（charter §17）。 */
    static final String RULE = "─".repeat(29);

    private Report() {
    }

    /** 一节内容：标题、横线、按最长标签对齐的行。没有行的节整节不出现，节与节之间空一行。 */
    static void section(List<String> output, String title, List<Line> lines) {
        List<Line> rows = new ArrayList<>(lines);
        while (!rows.isEmpty() && rows.getLast().blank()) {
            rows.removeLast();
        }
        if (rows.isEmpty()) {
            return;
        }
        if (!output.isEmpty()) {
            output.add("");
        }
        output.add(title);
        output.add(RULE);
        int width = rows.stream().mapToInt(Line::labelLength).max().orElse(0) + 2;
        rows.forEach(row -> output.add(row.render(width)));
    }

    /**
     * 一行键值输出。{@code label} 为 null 是空行；{@code value} 为 null 是只有名字的分组标题
     * （Answers 一节里的 question 名、Changes 一节里的 question 名）。
     */
    record Line(String label, String value) {

        static final Line BLANK = new Line(null, null);

        boolean blank() {
            return label == null;
        }

        int labelLength() {
            return blank() ? 0 : label.length();
        }

        String render(int width) {
            if (blank()) {
                return "";
            }
            return value == null ? label : String.format("%-" + width + "s%s", label, value);
        }
    }
}
