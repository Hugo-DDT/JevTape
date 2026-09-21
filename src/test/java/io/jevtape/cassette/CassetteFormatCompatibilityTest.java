package io.jevtape.cassette;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jevtape.contract.JevProtocolAdapter;
import io.jevtape.fingerprint.FingerprintEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code fixtures/cassette-v1} 是已发布的 Cassette Format v1:每次构建都必须仍能读取它,
 * 且不得改写它(charter §63、docs/cassette-format.md)。这些样例是该协议的回归防护网 ——
 * 此处的任何变更都是格式变更,需要配套迁移方案。
 */
class CassetteFormatCompatibilityTest {

    private static final Path FIXTURES = Path.of("fixtures", "cassette-v1");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    void everyCommittedV1CassetteIsStillReadable() throws IOException {
        List<Path> fixtures = fixtures();
        assertThat(fixtures).isNotEmpty();

        FileCassetteRepository repo = new FileCassetteRepository(FIXTURES);
        for (Path fixture : fixtures) {
            Cassette cassette = repo.read(name(fixture));

            assertThat(cassette.schemaVersion()).isEqualTo(Cassette.SCHEMA_VERSION);
            assertThat(cassette.name()).isEqualTo(name(fixture));
            assertThat(cassette.metadata().recordedAt())
                    .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
            assertThat(cassette.metadata().jevtapeVersion()).isNotBlank();
            assertThat(cassette.request().requestedModel()).isNotBlank();
            assertThat(cassette.request().state().isObject()).isTrue();
            assertThat(cassette.request().questions().isObject()).isTrue();
            assertThat(cassette.response().status()).isBetween(100, 599);
            assertThat(cassette.response().body().isObject()).isTrue();
            assertThat(cassette.fingerprints().request()).startsWith("sha256:");
            assertThat(cassette.fingerprints().contract()).startsWith("sha256:");
            assertThat(cassette.fingerprints().state()).startsWith("sha256:");
        }
    }

    /** 这些样例与本构建写出的内容完全一致,因此加载它们不会导致工作区出现改动。 */
    @Test
    void rewritingACommittedCassetteChangesNothing() throws IOException {
        FileCassetteRepository committed = new FileCassetteRepository(FIXTURES);
        FileCassetteRepository rewritten = new FileCassetteRepository(dir);

        for (Path fixture : fixtures()) {
            rewritten.write(committed.read(name(fixture)));

            assertThat(Files.readAllBytes(dir.resolve(fixture.getFileName())))
                    .as("%s stays byte-identical", fixture.getFileName())
                    .isEqualTo(Files.readAllBytes(fixture));
        }
    }

    /** 指纹必须是它自己内容的真实哈希:手写的假值会让 {@code jevtape verify} 对同一份请求也判 FAIL。 */
    @Test
    void everyCommittedFingerprintIsTheOneThisBuildComputes() throws IOException {
        for (Path fixture : fixtures()) {
            Cassette cassette = new FileCassetteRepository(FIXTURES).read(name(fixture));
            RecordedRequest request = cassette.request();

            assertThat(cassette.fingerprints())
                    .as("%s carries its own real fingerprints", fixture.getFileName())
                    .isEqualTo(FingerprintEngine.of(request.method(), request.path(),
                            new JevProtocolAdapter.Decision(request.requestedModel(), request.state(),
                                    request.questions())));
        }
    }

    /** HTTP 状态码是录制内容的一部分(charter §54):错误响应也是一条合法的 cassette。 */
    @Test
    void errorStatusesAreRecordedLikeAnyOtherResponse() {
        Cassette rateLimited = new FileCassetteRepository(FIXTURES).read("rate-limited");

        assertThat(rateLimited.response().status()).isEqualTo(429);
        assertThat(rateLimited.response().headers()).containsEntry("Retry-After", List.of("30"));
        assertThat(rateLimited.response().body().path("error").path("type").asText())
                .isEqualTo("rate_limit_exceeded");
        // 当调用从未到达模型时,jev-latest 可能完全解析不出任何结果。
        assertThat(rateLimited.request().requestedModel()).isEqualTo("jev-latest");
        assertThat(rateLimited.request().resolvedModel()).isNull();
    }

    @Test
    void theDocumentKeepsThePublishedShape() throws IOException {
        JsonNode root = MAPPER.readTree(FIXTURES.resolve("issue-routing.json").toFile());

        assertThat(fieldNames(root)).containsExactly(
                "schemaVersion", "name", "metadata", "request", "response", "fingerprints");
        assertThat(fieldNames(root.get("metadata")))
                .containsExactly("recordedAt", "jevtapeVersion", "durationMs");
        assertThat(fieldNames(root.get("request")))
                .containsExactly("method", "path", "requestedModel", "resolvedModel", "state", "questions");
        assertThat(fieldNames(root.get("response")))
                .containsExactly("status", "headers", "body");
        assertThat(fieldNames(root.get("fingerprints")))
                .containsExactly("request", "contract", "state");
    }

    @Test
    void noCommittedCassetteCarriesCredentials() throws IOException {
        for (Path fixture : fixtures()) {
            String content = Files.readString(fixture, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

            assertThat(content)
                    .as("%s holds no credential", fixture.getFileName())
                    .doesNotContain("authorization", "cookie", "api-key", "api_key", "apikey");
        }
    }

    private static List<Path> fixtures() throws IOException {
        try (Stream<Path> files = Files.list(FIXTURES)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private static String name(Path fixture) {
        String fileName = fixture.getFileName().toString();
        return fileName.substring(0, fileName.length() - ".json".length());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
