package io.jevtape.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.config.ConfigLoader;
import io.jevtape.config.JevTapeConfig;
import io.jevtape.contract.JevProtocolAdapter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code jevtape inspect <name>}：不打开 JSON 也能知道一盘磁带里录了什么（charter §17）—— Model、状态、
 * 延迟、Questions、Answers 与 Tokens。
 *
 * <p>这一层只做参数解析与渲染：装载交给 {@link FileCassetteRepository}，Jev 字段的取值交给
 * {@link JevProtocolAdapter}，因此这里不出现任何 System One 的字段名。渲染按节进行，一节里没有可打印的
 * 行时整节省略，于是 4xx/5xx 的磁带不会摆出空的 Answers 与 Tokens。
 */
@Command(name = "inspect",
        mixinStandardHelpOptions = true,
        description = "Show what a cassette recorded without opening the JSON.")
final class InspectCommand implements Runnable {

    /** 分节横线（charter §17）。 */
    private static final String RULE = "─".repeat(29);

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", paramLabel = "<name>",
            description = "Cassette name, without the directory or the .json suffix.")
    private String name;

    @Option(names = {"-d", "--cassette-dir"}, description = "Directory the cassette is read from.")
    private String cassetteDir;

    @Option(names = "--config", description = "Path of config.json (default: .jevtape/config.json).")
    private Path configFile;

    @Override
    public void run() {
        JevTapeConfig config = new ConfigLoader().load(cliOverrides(), System.getenv(),
                configFile == null ? ConfigLoader.DEFAULT_FILE : configFile);
        Cassette cassette = new FileCassetteRepository(Path.of(config.cassetteDir())).read(name);
        JsonNode body = cassette.response().body();

        List<String> output = new ArrayList<>();
        section(output, "Cassette", header(cassette));
        section(output, "Questions", questions(cassette));
        section(output, "Answers", tree(JevProtocolAdapter.answers(body)));
        section(output, "Tokens", tree(JevProtocolAdapter.tokens(body)));

        PrintWriter out = spec.commandLine().getOut();
        out.println(String.join(System.lineSeparator(), output));
        out.flush();
    }

    private static List<Line> header(Cassette cassette) {
        String resolved = cassette.request().resolvedModel();
        String requested = cassette.request().requestedModel();
        return List.of(
                new Line("Name", cassette.name()),
                new Line("Recorded", Objects.toString(cassette.metadata().recordedAt(), "-")),
                new Line("Model", Objects.toString(resolved != null ? resolved : requested, "-")),
                new Line("Status", String.valueOf(cassette.response().status())),
                new Line("Latency", cassette.metadata().durationMs() + " ms"));
    }

    private static List<Line> questions(Cassette cassette) {
        return JevProtocolAdapter.questions(cassette.request().questions()).stream()
                .map(question -> new Line(question.name(), question.type()))
                .toList();
    }

    /**
     * 把一段 JSON 铺成缩进的键值行：嵌套 object 单独成组（组名一行、成员缩进两格、组后空一行），于是
     * Choice 的概率分布读起来就是 charter §17 的样子。这里不认识任何 Jev 字段，只认识 object 与标量。
     */
    private static List<Line> tree(JsonNode node) {
        List<Line> lines = new ArrayList<>();
        flatten(node, 0, lines);
        return lines;
    }

    private static void flatten(JsonNode node, int depth, List<Line> sink) {
        if (node == null || !node.isObject()) {
            return;
        }
        node.properties().forEach(entry -> {
            String label = "  ".repeat(depth) + entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isObject()) {
                sink.add(new Line(label, null));
                flatten(value, depth + 1, sink);
                sink.add(Line.BLANK);
            } else {
                sink.add(new Line(label, value.isValueNode() ? value.asText() : value.toString()));
            }
        });
    }

    /** 一节内容：标题、横线、按最长标签对齐的行。没有行的节整节不出现，节与节之间空一行。 */
    private static void section(List<String> output, String title, List<Line> lines) {
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

    /** CLI 参数只把用户真的写了的那些交给配置合并，其余层级照常生效。 */
    private Map<String, String> cliOverrides() {
        Map<String, String> cli = new HashMap<>();
        if (cassetteDir != null) {
            cli.put("cassetteDir", cassetteDir);
        }
        return cli;
    }

    /**
     * 一行键值输出。{@code label} 为 null 是空行；{@code value} 为 null 是只有名字的分组标题
     * （Answers 一节里的 question 名）。
     */
    private record Line(String label, String value) {

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
