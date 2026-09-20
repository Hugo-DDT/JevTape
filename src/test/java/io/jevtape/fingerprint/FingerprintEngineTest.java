package io.jevtape.fingerprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jevtape.cassette.Fingerprints;
import io.jevtape.contract.JevProtocolAdapter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指纹引擎与 canonical JSON 的固定语义。这里覆盖 docs/matching.md 断言清单中已经落地的部分，
 * 其余（Score levels / Noul criteria 之类的 contract 细节）随后续任务补齐。
 */
class FingerprintEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String REQUEST = """
            {"model":"jev-latest",\
            "state":{"ticket":{"id":"SUP-4821","subject":"Cannot export invoice PDF"}},\
            "questions":{"route":{"type":"Choice",\
            "instructions":"Choose the team that should handle this ticket.",\
            "options":[{"value":"billing","criteria":"Invoices."},{"value":"technical","criteria":"Bugs."}]}}}""";

    /** 同样的内容，object key 全部换个顺序，空白也重新排过。 */
    private static final String SAME_CONTENT_REORDERED = """
            {
              "questions" : {
                "route" : {
                  "options" : [ { "criteria" : "Invoices.", "value" : "billing" },
                                { "criteria" : "Bugs.", "value" : "technical" } ],
                  "type" : "Choice",
                  "instructions" : "Choose the team that should handle this ticket."
                }
              },
              "model" : "jev-latest",
              "state" : { "ticket" : { "subject" : "Cannot export invoice PDF", "id" : "SUP-4821" } }
            }
            """;

    private static final String SWAPPED_OPTIONS = REQUEST.replace(
            "[{\"value\":\"billing\",\"criteria\":\"Invoices.\"},{\"value\":\"technical\",\"criteria\":\"Bugs.\"}]",
            "[{\"value\":\"technical\",\"criteria\":\"Bugs.\"},{\"value\":\"billing\",\"criteria\":\"Invoices.\"}]");

    private static final String REWORDED_INSTRUCTIONS = REQUEST.replace(
            "Choose the team that should handle this ticket.",
            "Is this unsolicited commercial advertising?");

    private static final String DIFFERENT_STATE = REQUEST.replace("Cannot export invoice PDF", "Cannot export CSV");

    private static final String PADDED_STATE = REQUEST.replace("Cannot export invoice PDF", " cannot export invoice pdf ");

    private static final String PINNED_MODEL = REQUEST.replace("jev-latest", "jev-1.13.0");

    private static final String NO_MODEL = REQUEST.replace("\"model\":\"jev-latest\",", "");

    @Test
    void everyFingerprintIsAVersionedSha256HexDigest() {
        Fingerprints fingerprints = of(REQUEST);

        for (String fingerprint : new String[]{fingerprints.request(), fingerprints.contract(),
                fingerprints.state()}) {
            assertThat(fingerprint).matches("sha256:[0-9a-f]{64}");
        }
        assertThat(fingerprints.request())
                .isNotEqualTo(fingerprints.contract())
                .isNotEqualTo(fingerprints.state());
    }

    @Test
    void isDeterministicForTheSameRequest() {
        assertThat(of(REQUEST)).isEqualTo(of(REQUEST));
    }

    @Test
    void objectKeyOrderChangesNothing() {
        assertThat(of(SAME_CONTENT_REORDERED)).isEqualTo(of(REQUEST));
    }

    @Test
    void arrayOrderIsSignificant() {
        Fingerprints swapped = of(SWAPPED_OPTIONS);

        assertThat(swapped.contract()).isNotEqualTo(of(REQUEST).contract());
        assertThat(swapped.request()).isNotEqualTo(of(REQUEST).request());
        assertThat(swapped.state()).isEqualTo(of(REQUEST).state());
    }

    @Test
    void questionTextChangesTheContractFingerprint() {
        Fingerprints reworded = of(REWORDED_INSTRUCTIONS);

        assertThat(reworded.contract()).isNotEqualTo(of(REQUEST).contract());
        assertThat(reworded.request()).isNotEqualTo(of(REQUEST).request());
        assertThat(reworded.state()).isEqualTo(of(REQUEST).state());
    }

    @Test
    void stateChangesTheStateFingerprint() {
        Fingerprints changed = of(DIFFERENT_STATE);

        assertThat(changed.state()).isNotEqualTo(of(REQUEST).state());
        assertThat(changed.request()).isNotEqualTo(of(REQUEST).request());
        assertThat(changed.contract()).isEqualTo(of(REQUEST).contract());
    }

    /** 若 canonicalization 做了 trim + lowercase，这两个 state 就会撞上；它们必须不同。 */
    @Test
    void stringsAreNeverTrimmedOrCaseFolded() {
        assertThat(of(PADDED_STATE).state()).isNotEqualTo(of(REQUEST).state());
    }

    @Test
    void theRequestedModelFeedsTheRequestFingerprintOnly() {
        Fingerprints pinned = of(PINNED_MODEL);

        assertThat(pinned.request()).isNotEqualTo(of(REQUEST).request());
        assertThat(pinned.state()).isEqualTo(of(REQUEST).state());
        assertThat(pinned.contract()).isEqualTo(of(REQUEST).contract());
    }

    @Test
    void aRequestWithoutAModelStillGetsAStableFingerprint() {
        assertThat(of(NO_MODEL).request()).matches("sha256:[0-9a-f]{64}").isNotEqualTo(of(REQUEST).request());
        assertThat(of(NO_MODEL)).isEqualTo(of(NO_MODEL));
    }

    @Test
    void methodAndPathFeedTheRequestFingerprint() {
        Fingerprints elsewhere = FingerprintEngine.of("POST", "/v2/systemone", decision(REQUEST));

        assertThat(elsewhere.request()).isNotEqualTo(of(REQUEST).request());
        assertThat(elsewhere.state()).isEqualTo(of(REQUEST).state());
    }

    /** query 里通常只有 trace id 之类每次都变的东西，把它算进指纹会让 replay 永远 MISS。 */
    @Test
    void queryStringIsNotPartOfTheRequestFingerprint() {
        Fingerprints traced = FingerprintEngine.of("POST", "/v1/systemone?trace=1&attempt=2", decision(REQUEST));

        assertThat(traced).isEqualTo(of(REQUEST));
    }

    @Test
    void canonicalJsonSortsKeysAndKeepsArrayOrder() {
        assertThat(CanonicalJson.of(json("""
                {"b": 2, "a": [1, 0], "c": {"z": null, "y": true}}
                """))).isEqualTo("{\"a\":[1,0],\"b\":2,\"c\":{\"y\":true,\"z\":null}}");
    }

    /** JSON 里 {@code 1} 与 {@code 1.0} 是两个不同的数；引擎只统一表达形式，不做类型归一。 */
    @Test
    void canonicalJsonWritesNumbersStably() {
        assertThat(CanonicalJson.of(json("0.810"))).isEqualTo("0.81");
        assertThat(CanonicalJson.of(json("1"))).isEqualTo("1");
        assertThat(CanonicalJson.of(json("1.0"))).isEqualTo("1.0");
    }

    @Test
    void canonicalJsonTreatsAMissingNodeAsNull() {
        assertThat(CanonicalJson.of(null)).isEqualTo("null");
        assertThat(CanonicalJson.of(json("null"))).isEqualTo("null");
    }

    private static Fingerprints of(String requestBody) {
        return FingerprintEngine.of("POST", "/v1/systemone", decision(requestBody));
    }

    private static JevProtocolAdapter.Decision decision(String requestBody) {
        return JevProtocolAdapter.parseRequest(requestBody.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode json(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new AssertionError("Test JSON is malformed", e);
        }
    }
}
