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
 * 指纹引擎与 canonical JSON 的固定语义，覆盖 docs/matching.md 的完整断言清单（charter §60）：key 序不敏感、
 * array 序敏感、question 文本 / Choice options / Score levels / Noul criteria / Question type 的变化都改
 * contract hash、state 变化改 state hash，且这两者互不牵连（charter §29）。
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

    /**
     * Choice / Score / Noul 三类问题齐全的请求，形状与 {@code fixtures/cassette-v1/issue-routing.json} 一致。
     * contract 指纹必须覆盖 charter §28 列出的全部字段，所以每一类都要单独证明它喂进了 hash。
     */
    private static final String ALL_QUESTION_TYPES = """
            {"model":"jev-latest",\
            "state":{"ticket":{"id":"SUP-4821"}},\
            "questions":{\
            "route":{"type":"Choice","instructions":"Choose the team.",\
            "options":[{"value":"billing","criteria":"Invoices."},{"value":"technical","criteria":"Bugs."}]},\
            "severity":{"type":"Score","instructions":"Rate the severity.",\
            "levels":[{"score":0,"criteria":"Cosmetic."},{"score":5,"criteria":"Fully blocked."}]},\
            "urgent":{"type":"Noul","instructions":"Is this urgent?","criteria":"Customer is blocked."}}}""";

    private static final String REWORDED_OPTION = ALL_QUESTION_TYPES.replace(
            "\"criteria\":\"Invoices.\"", "\"criteria\":\"Invoices, payments and refunds.\"");

    private static final String EXTRA_OPTION = ALL_QUESTION_TYPES.replace(
            "{\"value\":\"technical\",\"criteria\":\"Bugs.\"}]",
            "{\"value\":\"technical\",\"criteria\":\"Bugs.\"},{\"value\":\"other\",\"criteria\":\"Anything else.\"}]");

    private static final String REWORDED_SCORE_LEVEL = ALL_QUESTION_TYPES.replace(
            "\"criteria\":\"Fully blocked.\"", "\"criteria\":\"The customer cannot work at all.\"");

    private static final String REWORDED_NOUL_CRITERIA = ALL_QUESTION_TYPES.replace(
            "\"criteria\":\"Customer is blocked.\"", "\"criteria\":\"Customer is blocked and losing money.\"");

    private static final String SWAPPED_QUESTION_TYPE = ALL_QUESTION_TYPES.replace(
            "\"type\":\"Noul\"", "\"type\":\"Choice\"");

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

    /** charter §60 的 "choice option change"：改选项文案与新增选项都算改契约。 */
    @Test
    void choiceOptionChangesTheContractFingerprint() {
        Fingerprints baseline = of(ALL_QUESTION_TYPES);

        assertThat(of(REWORDED_OPTION).contract()).isNotEqualTo(baseline.contract());
        assertThat(of(EXTRA_OPTION).contract()).isNotEqualTo(baseline.contract());
        assertThat(of(REWORDED_OPTION).request()).isNotEqualTo(baseline.request());
        assertThat(of(REWORDED_OPTION).state()).isEqualTo(baseline.state());
    }

    /** Score 的 levels 属于契约（charter §28）：等级文案一改，旧录制结果就不该被无条件复用。 */
    @Test
    void scoreLevelChangesTheContractFingerprint() {
        Fingerprints baseline = of(ALL_QUESTION_TYPES);
        Fingerprints reworded = of(REWORDED_SCORE_LEVEL);

        assertThat(reworded.contract()).isNotEqualTo(baseline.contract());
        assertThat(reworded.request()).isNotEqualTo(baseline.request());
        assertThat(reworded.state()).isEqualTo(baseline.state());
    }

    /** Noul criteria 同样属于契约，且与 Choice / Score 各自独立生效。 */
    @Test
    void noulCriteriaChangesTheContractFingerprint() {
        Fingerprints baseline = of(ALL_QUESTION_TYPES);
        Fingerprints reworded = of(REWORDED_NOUL_CRITERIA);

        assertThat(reworded.contract()).isNotEqualTo(baseline.contract());
        assertThat(reworded.request()).isNotEqualTo(baseline.request());
        assertThat(reworded.state()).isEqualTo(baseline.state());
    }

    /** 同一个 key 从 Noul 改成 Choice 就是契约变化，哪怕 instructions 一字未动。 */
    @Test
    void questionTypeChangesTheContractFingerprint() {
        Fingerprints baseline = of(ALL_QUESTION_TYPES);
        Fingerprints retyped = of(SWAPPED_QUESTION_TYPE);

        assertThat(retyped.contract()).isNotEqualTo(baseline.contract());
        assertThat(retyped.request()).isNotEqualTo(baseline.request());
        assertThat(retyped.state()).isEqualTo(baseline.state());
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
