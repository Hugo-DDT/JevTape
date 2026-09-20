package io.jevtape.config;

import io.jevtape.shared.ConfigurationError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    @TempDir
    Path dir;

    private final ConfigLoader loader = new ConfigLoader();

    private Path configFile(String json) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, json);
        return file;
    }

    @Test
    void defaultsWhenNothingProvided() {
        JevTapeConfig config = loader.load(Map.of(), Map.of(), dir.resolve("missing.json"));

        assertThat(config).isEqualTo(JevTapeConfig.DEFAULTS);
        assertThat(config.listen()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(8787);
        assertThat(config.cassetteDir()).isEqualTo(".jevtape/cassettes");
        assertThat(config.match()).isEqualTo("strict");
        assertThat(config.onMiss()).isEqualTo("error");
    }

    @Test
    void fileOverridesDefaults() throws IOException {
        Path file = configFile("""
                {"listen": "0.0.0.0", "port": 9000, "cassetteDir": "tapes", "onMiss": "live"}
                """);

        JevTapeConfig config = loader.load(Map.of(), Map.of(), file);

        assertThat(config.listen()).isEqualTo("0.0.0.0");
        assertThat(config.port()).isEqualTo(9000);
        assertThat(config.cassetteDir()).isEqualTo("tapes");
        assertThat(config.match()).isEqualTo("strict");
        assertThat(config.onMiss()).isEqualTo("live");
    }

    @Test
    void envOverridesFile() throws IOException {
        Path file = configFile("""
                {"listen": "0.0.0.0", "port": 9000}
                """);

        JevTapeConfig config = loader.load(Map.of(),
                Map.of("JEVTAPE_PORT", "9100", "JEVTAPE_CASSETTE_DIR", "env-tapes"), file);

        assertThat(config.listen()).isEqualTo("0.0.0.0");
        assertThat(config.port()).isEqualTo(9100);
        assertThat(config.cassetteDir()).isEqualTo("env-tapes");
    }

    @Test
    void cliOverridesEnvAndFile() throws IOException {
        Path file = configFile("""
                {"port": 9000, "onMiss": "error"}
                """);

        JevTapeConfig config = loader.load(
                Map.of("port", "9200", "onMiss", "record"),
                Map.of("JEVTAPE_PORT", "9100", "JEVTAPE_ON_MISS", "live"),
                file);

        assertThat(config.port()).isEqualTo(9200);
        assertThat(config.onMiss()).isEqualTo("record");
    }

    @Test
    void rejectsApiKeyFieldInFile() throws IOException {
        Path file = configFile("""
                {"port": 9000, "apiKey": "sk-secret"}
                """);

        assertThatThrownBy(() -> loader.load(Map.of(), Map.of(), file))
                .isInstanceOf(ConfigurationError.class)
                .hasMessageContaining("apiKey")
                .hasMessageContaining("environment variables");
    }

    @Test
    void rejectsVariousSecretFieldNames() throws IOException {
        Path file = configFile("""
                {"api_key": "x", "TYPESAFE_API_KEY": "y"}
                """);

        assertThatThrownBy(() -> loader.load(Map.of(), Map.of(), file))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void rejectsMalformedFile() throws IOException {
        Path file = configFile("{not json");

        assertThatThrownBy(() -> loader.load(Map.of(), Map.of(), file))
                .isInstanceOf(ConfigurationError.class)
                .hasMessageContaining("Malformed");
    }

    @Test
    void rejectsInvalidValues() throws IOException {
        assertThatThrownBy(() -> loader.load(Map.of("port", "70000"), Map.of(), null))
                .isInstanceOf(ConfigurationError.class);
        assertThatThrownBy(() -> loader.load(Map.of("port", "abc"), Map.of(), null))
                .isInstanceOf(ConfigurationError.class);
        assertThatThrownBy(() -> loader.load(Map.of("match", "semantic"), Map.of(), null))
                .isInstanceOf(ConfigurationError.class)
                .hasMessageContaining("strict");
        assertThatThrownBy(() -> loader.load(Map.of("onMiss", "ignore"), Map.of(), null))
                .isInstanceOf(ConfigurationError.class);

        Path blankListen = configFile("""
                {"listen": ""}
                """);
        assertThatThrownBy(() -> loader.load(Map.of(), Map.of(), blankListen))
                .isInstanceOf(ConfigurationError.class)
                .hasMessageContaining("listen");
    }
}
