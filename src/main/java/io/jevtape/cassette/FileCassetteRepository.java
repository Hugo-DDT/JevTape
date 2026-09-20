package io.jevtape.cassette;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.jevtape.shared.CassetteCorrupted;
import io.jevtape.shared.CassetteNotFound;
import io.jevtape.shared.CassetteVersionUnsupported;
import io.jevtape.shared.ConfigurationError;
import io.jevtape.shared.StorageFailure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 以 {@code <cassetteDir>/<name>.json} 形式存放 cassette（charter §24）。
 *
 * <p>输出采用两空格缩进、LF 行尾并带结尾换行，因此 cassette 读起来就像它本来的 JSON，而重新记录一个
 * 产生的是实际改动内容的 Git diff，而非整文件重写。
 *
 * <p>版本关卡在绑定之前、针对解析出的树运行：缺失的 {@code schemaVersion} 与不受支持的
 * {@code schemaVersion} 都会抛 {@link CassetteVersionUnsupported}，而能解析却不是 v1 文档的文件会抛
 * {@link CassetteCorrupted}（charter §26, §63）。
 */
public final class FileCassetteRepository implements CassetteRepository {

    /** 字母、数字、{@code . _ -}，且不以点开头的：名称永远无法逃出该目录。 */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** 目录里算作 cassette 的文件名：一个安全名称加上 {@code .json}。其余文件（编辑器备份、README）一律跳过。 */
    private static final Pattern CASSETTE_FILE = Pattern.compile("(" + SAFE_NAME.pattern() + ")\\.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectWriter WRITER = MAPPER.writer(new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n")));

    private final Path cassetteDir;

    public FileCassetteRepository(Path cassetteDir) {
        this.cassetteDir = Objects.requireNonNull(cassetteDir, "cassetteDir");
    }

    @Override
    public Cassette read(String name) {
        Path file = resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new CassetteNotFound("No cassette '" + name + "' under " + cassetteDir);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(file.toFile());
        } catch (JsonProcessingException e) {
            throw new CassetteCorrupted("Cassette " + file + " is not readable JSON", e);
        } catch (IOException e) {
            throw new StorageFailure("Cannot read cassette " + file, e);
        }
        if (root == null || !root.isObject()) {
            throw new CassetteCorrupted("Cassette " + file + " must contain a JSON object");
        }
        requireSupportedSchemaVersion(file, root);

        try {
            return MAPPER.treeToValue(root, Cassette.class);
        } catch (JsonProcessingException e) {
            throw new CassetteCorrupted("Cassette " + file + " does not match Cassette Format v"
                    + Cassette.SCHEMA_VERSION, e);
        }
    }

    @Override
    public void write(Cassette cassette) {
        Objects.requireNonNull(cassette, "cassette");
        Path file = resolve(cassette.name());
        try {
            Files.createDirectories(cassetteDir);
            Files.writeString(file, WRITER.writeValueAsString(cassette) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageFailure("Cannot write cassette " + file, e);
        }
    }

    @Override
    public List<Cassette> loadAll() {
        if (!Files.isDirectory(cassetteDir)) {
            throw new CassetteNotFound("No cassette directory " + cassetteDir);
        }
        try (Stream<Path> files = Files.list(cassetteDir)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> CASSETTE_FILE.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(matcher -> matcher.group(1))
                    .sorted()
                    .map(this::read)
                    .toList();
        } catch (IOException e) {
            throw new StorageFailure("Cannot list cassettes under " + cassetteDir, e);
        }
    }

    private Path resolve(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new ConfigurationError("Invalid cassette name '" + name
                    + "'. Use letters, digits, '.', '_' or '-', without path separators.");
        }
        return cassetteDir.resolve(name + ".json");
    }

    private static void requireSupportedSchemaVersion(Path file, JsonNode root) {
        JsonNode declared = root.get("schemaVersion");
        if (declared == null || declared.isNull()) {
            throw new CassetteVersionUnsupported("Cassette " + file
                    + " declares no schemaVersion. Every cassette must declare one; this build reads v"
                    + Cassette.SCHEMA_VERSION + " only.");
        }
        if (!declared.canConvertToInt() || declared.intValue() != Cassette.SCHEMA_VERSION) {
            throw new CassetteVersionUnsupported("Cassette " + file + " declares schemaVersion "
                    + declared.asText() + ", but this build reads v" + Cassette.SCHEMA_VERSION + " only.");
        }
    }
}
