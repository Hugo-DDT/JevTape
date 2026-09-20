package io.jevtape.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jevtape.shared.ConfigurationError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 以固定优先级合并配置：CLI 参数 &gt; 环境变量 &gt; config.json &gt; 默认值。
 * API 密钥绝不能出现在 config.json 中 —— 一旦出现即为 {@link ConfigurationError}。
 */
public final class ConfigLoader {

    private static final Pattern SECRET_FIELD =
            Pattern.compile("(?i).*(api[_-]?key|token|secret|password|authorization|cookie).*");
    private static final Set<String> MATCH_POLICIES = Set.of("strict");

    private final ObjectMapper mapper = new ObjectMapper();

    public JevTapeConfig load(Map<String, String> cli, Map<String, String> env, Path configFile) {
        JsonNode file = readConfigFile(configFile);
        JevTapeConfig d = JevTapeConfig.DEFAULTS;
        return new JevTapeConfig(
                resolveString("listen", "JEVTAPE_LISTEN", cli, env, file, d.listen()),
                resolveInt("port", "JEVTAPE_PORT", cli, env, file, d.port()),
                resolveString("cassetteDir", "JEVTAPE_CASSETTE_DIR", cli, env, file, d.cassetteDir()),
                resolveString("match", "JEVTAPE_MATCH", cli, env, file, d.match()),
                resolveMissPolicy(cli, env, file, d.onMiss()));
    }

    /** miss 策略单独解析：它的取值是一个枚举，而不是一段自由的文本。 */
    private MissPolicy resolveMissPolicy(Map<String, String> cli, Map<String, String> env, JsonNode file,
                                         MissPolicy fallback) {
        String raw = raw("onMiss", "JEVTAPE_ON_MISS", cli, env, file);
        return raw == null ? fallback : MissPolicy.of(raw);
    }

    private JsonNode readConfigFile(Path configFile) {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            return null;
        }
        JsonNode root;
        try {
            root = mapper.readTree(configFile.toFile());
        } catch (IOException e) {
            throw new ConfigurationError("Malformed config file: " + configFile, e);
        }
        if (root == null || root.isNull()) {
            return null;
        }
        if (!root.isObject()) {
            throw new ConfigurationError("Config file must contain a JSON object: " + configFile);
        }
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (SECRET_FIELD.matcher(name).matches()) {
                throw new ConfigurationError("Config file contains credential field '" + name
                        + "'. API keys must come from environment variables only; remove it from " + configFile);
            }
        }
        return root;
    }

    private String resolveString(String key, String envName, Map<String, String> cli,
                                 Map<String, String> env, JsonNode file, String fallback) {
        String raw = raw(key, envName, cli, env, file);
        String value = raw != null ? raw : fallback;
        return switch (key) {
            case "listen" -> {
                if (value.isBlank()) {
                    throw new ConfigurationError("listen must not be blank");
                }
                yield value;
            }
            case "match" -> requireIn(key, value, MATCH_POLICIES);
            default -> value;
        };
    }

    private int resolveInt(String key, String envName, Map<String, String> cli,
                           Map<String, String> env, JsonNode file, int fallback) {
        String raw = raw(key, envName, cli, env, file);
        int value;
        if (raw == null) {
            value = fallback;
        } else {
            try {
                value = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new ConfigurationError(key + " must be an integer, got: " + raw);
            }
        }
        if (value < 1 || value > 65535) {
            throw new ConfigurationError(key + " must be between 1 and 65535, got: " + value);
        }
        return value;
    }

    private String raw(String key, String envName, Map<String, String> cli,
                       Map<String, String> env, JsonNode file) {
        String value = cli != null ? cli.get(key) : null;
        if (isSet(value)) {
            return value;
        }
        value = env != null ? env.get(envName) : null;
        if (isSet(value)) {
            return value;
        }
        if (file != null && file.hasNonNull(key)) {
            JsonNode node = file.get(key);
            return node.isTextual() ? node.asText() : node.toString();
        }
        return null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireIn(String key, String value, Set<String> allowed) {
        if (!allowed.contains(value)) {
            throw new ConfigurationError(key + " must be one of " + allowed + ", got: " + value);
        }
        return value;
    }
}
