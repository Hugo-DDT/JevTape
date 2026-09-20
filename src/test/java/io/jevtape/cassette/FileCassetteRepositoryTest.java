package io.jevtape.cassette;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jevtape.shared.CassetteCorrupted;
import io.jevtape.shared.CassetteNotFound;
import io.jevtape.shared.CassetteVersionUnsupported;
import io.jevtape.shared.ConfigurationError;
import io.jevtape.shared.StorageFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileCassetteRepositoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private FileCassetteRepository repo() {
        return new FileCassetteRepository(dir.resolve("cassettes"));
    }

    private static Cassette sample(String name) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of("application/json"));
        return Cassette.of(name, new Interaction(
                new CassetteMetadata("2026-09-20T10:20:30Z", "0.1.0", 132),
                new RecordedRequest("POST", "/v1/systemone", "jev-latest", "jev-1.13.0",
                        json("""
                                {"ticket": {"subject": "Cannot export invoice PDF", "channel": "email"}}
                                """),
                        json("""
                                {"route": {"type": "Choice", "options": ["billing", "technical", "other"]}}
                                """)),
                new RecordedResponse(200, headers,
                        json("""
                                {"answers": {"route": {"choice": "billing", "confidence": 0.74}}}
                                """)),
                new Fingerprints("sha256:aa11", "sha256:bb22", "sha256:cc33")));
    }

    private static JsonNode json(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new AssertionError("Test JSON is malformed", e);
        }
    }

    private void writeRaw(String name, String content) throws IOException {
        Path cassettes = dir.resolve("cassettes");
        Files.createDirectories(cassettes);
        Files.writeString(cassettes.resolve(name + ".json"), content, StandardCharsets.UTF_8);
    }

    @Test
    void roundTripKeepsEverything() {
        Cassette written = sample("issue-routing");

        repo().write(written);
        Cassette read = repo().read("issue-routing");

        assertThat(read).isEqualTo(written);
        assertThat(Files.isRegularFile(dir.resolve("cassettes/issue-routing.json"))).isTrue();
    }

    @Test
    void keepsRequestedAndResolvedModelApart() {
        repo().write(sample("issue-routing"));

        RecordedRequest request = repo().read("issue-routing").request();

        assertThat(request.requestedModel()).isEqualTo("jev-latest");
        assertThat(request.resolvedModel()).isEqualTo("jev-1.13.0");
    }

    @Test
    void anInteractionIsTheCassetteWithoutItsIdentity() {
        Cassette cassette = sample("issue-routing");

        Interaction interaction = cassette.interaction();

        assertThat(interaction.request()).isEqualTo(cassette.request());
        assertThat(interaction.response()).isEqualTo(cassette.response());
        assertThat(interaction.metadata()).isEqualTo(cassette.metadata());
        assertThat(interaction.fingerprints()).isEqualTo(cassette.fingerprints());
        assertThat(Cassette.of("issue-routing", interaction)).isEqualTo(cassette);
    }

    @Test
    void writesDiffableJsonAndRewritingChangesNothing() throws IOException {
        FileCassetteRepository repo = repo();
        repo.write(sample("issue-routing"));
        Path file = dir.resolve("cassettes/issue-routing.json");

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).startsWith("{\n  \"schemaVersion\" : 1,\n");
        assertThat(content).doesNotContain("\r");
        assertThat(content).endsWith("\n");

        byte[] first = Files.readAllBytes(file);
        repo.write(sample("issue-routing"));
        assertThat(Files.readAllBytes(file)).isEqualTo(first);
    }

    @Test
    void writeReplacesAnExistingCassette() throws IOException {
        FileCassetteRepository repo = repo();
        Cassette first = sample("issue-routing");
        Cassette reRecorded = new Cassette(first.schemaVersion(), first.name(),
                new CassetteMetadata("2026-09-21T08:00:00Z", "0.1.0", 10),
                first.request(), first.response(), first.fingerprints());

        repo.write(first);
        repo.write(reRecorded);

        assertThat(repo.read("issue-routing")).isEqualTo(reRecorded);
        try (var files = Files.list(dir.resolve("cassettes"))) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void nullPayloadBecomesJsonNullAndSurvivesTheRoundTrip() throws IOException {
        Cassette sparse = new Cassette(Cassette.SCHEMA_VERSION, "sparse",
                new CassetteMetadata("2026-09-20T10:20:30Z", "0.1.0", 0),
                new RecordedRequest("POST", "/v1/systemone", "jev-latest", null, null, null),
                new RecordedResponse(529, null, null),
                new Fingerprints(null, null, null));

        repo().write(sparse);

        assertThat(repo().read("sparse")).isEqualTo(sparse);
        assertThat(Files.readString(dir.resolve("cassettes/sparse.json")))
                .contains("\"state\" : null")
                .contains("\"body\" : null");
    }

    @Test
    void readWithoutACassetteIsNotFound() {
        assertThatThrownBy(() -> repo().read("missing"))
                .isInstanceOf(CassetteNotFound.class)
                .hasMessageContaining("missing");
    }

    @Test
    void loadAllReturnsEveryCassetteSortedByName() {
        FileCassetteRepository repo = repo();
        repo.write(sample("issue-routing"));
        repo.write(sample("zzz-noul"));
        repo.write(sample("aaa-urgency"));

        // 顺序即 replay 的优先级，因此它必须是名称序，而不是文件系统的偶然顺序。
        assertThat(repo.loadAll())
                .containsExactly(sample("aaa-urgency"), sample("issue-routing"), sample("zzz-noul"));
    }

    @Test
    void loadAllSkipsFilesThatAreNotCassettes() throws IOException {
        Path cassettes = dir.resolve("cassettes");
        Files.createDirectories(cassettes);
        Files.writeString(cassettes.resolve("notes.txt"), "not a cassette");
        Files.writeString(cassettes.resolve(".issue-routing.json.swp"), "editor swap file");

        assertThat(repo().loadAll()).isEmpty();
    }

    @Test
    void loadAllWithoutADirectoryIsNotFound() {
        assertThatThrownBy(() -> repo().loadAll())
                .isInstanceOf(CassetteNotFound.class)
                .hasMessageContaining("cassettes");
    }

    @Test
    void loadAllRefusesToSilentlySkipACassetteItCannotRead() throws IOException {
        repo().write(sample("issue-routing"));
        writeRaw("aaa-broken", "{ this is not json");

        // 静默跳过只会把损坏变成一个查不出原因的 MISS。
        assertThatThrownBy(() -> repo().loadAll()).isInstanceOf(CassetteCorrupted.class);
    }

    @Test
    void missingSchemaVersionIsUnsupported() throws IOException {
        writeRaw("no-version", """
                {"name": "no-version", "metadata": {}, "request": {}, "response": {}, "fingerprints": {}}
                """);

        assertThatThrownBy(() -> repo().read("no-version"))
                .isInstanceOf(CassetteVersionUnsupported.class)
                .hasMessageContaining("no schemaVersion");
    }

    @Test
    void unsupportedSchemaVersionIsUnsupported() throws IOException {
        writeRaw("from-the-future", """
                {"schemaVersion": 2, "name": "from-the-future"}
                """);
        writeRaw("version-as-text", """
                {"schemaVersion": "1", "name": "version-as-text"}
                """);

        assertThatThrownBy(() -> repo().read("from-the-future"))
                .isInstanceOf(CassetteVersionUnsupported.class)
                .hasMessageContaining("schemaVersion 2");
        assertThatThrownBy(() -> repo().read("version-as-text"))
                .isInstanceOf(CassetteVersionUnsupported.class);
    }

    @Test
    void unparsableAndIncompleteFilesAreCorrupted() throws IOException {
        writeRaw("broken", "{not json");
        writeRaw("array", "[]");
        writeRaw("empty", "");
        writeRaw("partial", """
                {"schemaVersion": 1, "name": "partial"}
                """);

        assertThatThrownBy(() -> repo().read("broken")).isInstanceOf(CassetteCorrupted.class);
        assertThatThrownBy(() -> repo().read("array")).isInstanceOf(CassetteCorrupted.class);
        assertThatThrownBy(() -> repo().read("empty")).isInstanceOf(CassetteCorrupted.class);
        assertThatThrownBy(() -> repo().read("partial"))
                .isInstanceOf(CassetteCorrupted.class)
                .hasMessageContaining("Cassette Format v1");
    }

    @Test
    void aTargetThatCannotBeWrittenIsAStorageFailure() throws IOException {
        Files.createDirectories(dir.resolve("cassettes/blocked.json"));

        assertThatThrownBy(() -> repo().write(sample("blocked")))
                .isInstanceOf(StorageFailure.class);
    }

    @Test
    void namesThatCouldEscapeTheDirectoryAreRejected() throws IOException {
        FileCassetteRepository repo = repo();

        for (String name : List.of("../escape", "nested/name", "..", ".hidden", "", " with space")) {
            assertThatThrownBy(() -> repo.read(name)).isInstanceOf(ConfigurationError.class);
            assertThatThrownBy(() -> repo.write(sample(name))).isInstanceOf(ConfigurationError.class);
        }
        assertThat(Files.exists(dir.resolve("escape.json"))).isFalse();
        try (var files = Files.walk(dir)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }
}
