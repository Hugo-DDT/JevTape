package io.jevtape.redaction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderRedactorTest {

    private static final String API_KEY = "sk-live-9f2c1d4b7a6e5840";

    private static final Map<String, List<String>> CREDENTIAL_HEADERS = headers(
            "Authorization", "Bearer " + API_KEY,
            "Proxy-Authorization", "Basic c2stbGl2ZQ==",
            "Cookie", "session=abc123; theme=dark",
            "Set-Cookie", "session=abc123; Path=/; HttpOnly",
            "X-Api-Key", API_KEY,
            "Content-Type", "application/json",
            "Retry-After", "30");

    @Test
    void stripsEveryHeaderTheCharterNames() {
        Map<String, List<String>> kept = HeaderRedactor.strip(CREDENTIAL_HEADERS);

        assertThat(kept).containsOnlyKeys("Content-Type", "Retry-After");
        assertThat(kept.values().toString()).doesNotContain(API_KEY).doesNotContain("session=abc123");
    }

    @Test
    void headerNamesAreMatchedCaseInsensitively() {
        for (String name : List.of("authorization", "AUTHORIZATION", "Authorization",
                "cookie", "SET-COOKIE", "x-api-key", "X-API-KEY")) {
            assertThat(HeaderRedactor.isSensitive(name)).as(name).isTrue();
            assertThat(HeaderRedactor.strip(headers(name, "secret"))).isEmpty();
        }
    }

    @Test
    void failsClosedOnNamesThatSmellLikeCredentials() {
        for (String name : List.of("X-Auth-Token", "x-access-token", "X-CSRF-Token", "Api_Key",
                "X-Client-Secret", "X-User-Password", "Proxy-Authorization", "Cookie2")) {
            assertThat(HeaderRedactor.isSensitive(name)).as(name).isTrue();
        }
        for (String name : List.of("Content-Type", "Accept", "User-Agent", "Retry-After",
                "X-Jev-Model", "Date", "Content-Length", "X-Trace")) {
            assertThat(HeaderRedactor.isSensitive(name)).as(name).isFalse();
        }
    }

    @Test
    void keepsUnrelatedHeadersVerbatim() {
        Map<String, List<String>> kept = HeaderRedactor.strip(headers(
                "Content-Type", "application/json",
                "X-Jev-Trace", "first", "X-Jev-Trace", "second"));

        assertThat(kept).containsExactly(
                Map.entry("Content-Type", List.of("application/json")),
                Map.entry("X-Jev-Trace", List.of("first", "second")));
    }

    @Test
    void maskedKeepsEveryNameButReplacesCredentialValues() {
        Map<String, List<String>> view = HeaderRedactor.masked(CREDENTIAL_HEADERS);

        assertThat(view.keySet()).isEqualTo(CREDENTIAL_HEADERS.keySet());
        assertThat(view.get("Authorization")).containsExactly(HeaderRedactor.REDACTED);
        assertThat(view.get("Set-Cookie")).containsExactly("[REDACTED]");
        assertThat(view.get("X-Api-Key")).containsExactly("[REDACTED]");
        assertThat(view.get("Content-Type")).containsExactly("application/json");
        assertThat(view.toString()).doesNotContain(API_KEY).doesNotContain("session=abc123");
    }

    @Test
    void anEmptyHeaderMapStaysEmpty() {
        assertThat(HeaderRedactor.strip(Map.of())).isEmpty();
        assertThat(HeaderRedactor.masked(Map.of())).isEmpty();
    }

    private static Map<String, List<String>> headers(String... namesAndValues) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.computeIfAbsent(namesAndValues[i], name -> new ArrayList<>())
                    .add(namesAndValues[i + 1]);
        }
        return headers;
    }
}
