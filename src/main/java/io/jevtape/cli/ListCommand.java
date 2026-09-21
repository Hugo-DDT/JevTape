package io.jevtape.cli;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.config.ConfigLoader;
import io.jevtape.config.JevTapeConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code jevtape list}：列出 cassetteDir 下的全部磁带，一行一盘，按名称排序（即 replay 的优先级顺序）。
 *
 * <p>这一层只做参数解析与渲染：装载交给 {@link FileCassetteRepository}，因此目录不存在、或某个看起来是
 * cassette 的文件读不动，都会照仓库的契约抛错并渲染成一行 —— 列一半就悄悄跳过只会把损坏藏起来。
 */
@Command(name = "list",
        mixinStandardHelpOptions = true,
        description = "List every cassette in the cassette directory.")
final class ListCommand implements Runnable {

    private static final List<String> COLUMNS = List.of("NAME", "MODEL", "STATUS", "LATENCY", "RECORDED");

    @Spec
    private CommandSpec spec;

    @Option(names = {"-d", "--cassette-dir"}, description = "Directory cassettes are listed from.")
    private String cassetteDir;

    @Option(names = "--config", description = "Path of config.json (default: .jevtape/config.json).")
    private Path configFile;

    @Override
    public void run() {
        JevTapeConfig config = new ConfigLoader().load(cliOverrides(), System.getenv(),
                configFile == null ? ConfigLoader.DEFAULT_FILE : configFile);
        Path cassettes = Path.of(config.cassetteDir());
        List<Cassette> loaded = new FileCassetteRepository(cassettes).loadAll();

        PrintWriter out = spec.commandLine().getOut();
        out.println("Cassette directory:");
        out.println(cassettes);
        out.println();
        if (loaded.isEmpty()) {
            out.println("No cassettes.");
        } else {
            List<List<String>> rows = new ArrayList<>();
            rows.add(COLUMNS);
            loaded.forEach(cassette -> rows.add(row(cassette)));
            table(out, rows);
            out.println();
            out.println(loaded.size() + (loaded.size() == 1 ? " cassette" : " cassettes"));
        }
        out.flush();
    }

    /** 表格里显示的 model 与 REC / inspect 用的是同一个规则：上游解析出来的优先，没有就用请求的那个。 */
    private static List<String> row(Cassette cassette) {
        String resolved = cassette.request().resolvedModel();
        String requested = cassette.request().requestedModel();
        return List.of(
                cassette.name(),
                Objects.toString(resolved != null ? resolved : requested, "-"),
                String.valueOf(cassette.response().status()),
                cassette.metadata().durationMs() + " ms",
                Objects.toString(cassette.metadata().recordedAt(), "-"));
    }

    /** 列宽按内容算，因此名字再长也不会把后面的列挤歪；最后一列不补尾随空格。 */
    private static void table(PrintWriter out, List<List<String>> rows) {
        int[] widths = new int[COLUMNS.size()];
        for (List<String> row : rows) {
            for (int column = 0; column < row.size(); column++) {
                widths[column] = Math.max(widths[column], row.get(column).length());
            }
        }
        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder();
            for (int column = 0; column < row.size(); column++) {
                String cell = row.get(column);
                line.append(cell);
                if (column < row.size() - 1) {
                    line.append(" ".repeat(widths[column] - cell.length() + 2));
                }
            }
            out.println(line);
        }
    }

    /** CLI 参数只把用户真的写了的那些交给配置合并，其余层级照常生效。 */
    private Map<String, String> cliOverrides() {
        Map<String, String> cli = new HashMap<>();
        if (cassetteDir != null) {
            cli.put("cassetteDir", cassetteDir);
        }
        return cli;
    }
}
