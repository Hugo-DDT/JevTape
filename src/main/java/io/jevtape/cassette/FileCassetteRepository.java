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
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Cassettes as {@code <cassetteDir>/<name>.json} (charter §24).
 *
 * <p>Output is two-space indented with LF endings and a trailing newline, so a cassette reads like
 * the JSON it is and re-recording one produces a Git diff of what actually changed rather than a
 * whole-file rewrite.
 *
 * <p>The version gate runs on the parsed tree, before binding: a missing {@code schemaVersion} and
 * an unsupported one are both {@link CassetteVersionUnsupported}, and a file that parses but is not
 * a v1 document is {@link CassetteCorrupted} (charter §26, §63).
 */
public final class FileCassetteRepository implements CassetteRepository {

    /** Letters, digits, {@code . _ -} and no leading dot: a name can never escape the directory. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

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
