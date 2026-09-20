package io.jevtape.matching;

import com.fasterxml.jackson.databind.node.NullNode;
import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.CassetteMetadata;
import io.jevtape.cassette.Fingerprints;
import io.jevtape.cassette.Interaction;
import io.jevtape.cassette.RecordedRequest;
import io.jevtape.cassette.RecordedResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STRICT 是 V1 唯一的匹配策略：request fingerprint 逐字符相等才命中，差一点就是 MISS，没有降级也没有猜测。
 * MISS 顺带解释自己 —— 那部分断言在 {@link MissDiagnosisTest}。
 */
class StrictMatcherTest {

    private static final String REQUEST = "sha256:31bd4c0e9a7f5c3d2b8e1f60a4c7d9e2b5f81a3c6d9e0f2b4a7c1d3e5f80a9b2";
    private static final String OTHER = "sha256:9f2c1d4b7a6e5840c3d9e0f2b4a7c1d3e5f80a9b231bd4c0e9a7f5c3d2b8e1f6";
    private static final String MODEL = "jev-latest";

    @Test
    void anIdenticalRequestFingerprintHits() {
        MatchResult result = StrictMatcher.match(fingerprints(REQUEST), MODEL,
                List.of(cassette("issue-routing", REQUEST)));

        assertThat(result).isInstanceOfSatisfying(MatchResult.Hit.class,
                hit -> assertThat(hit.cassette().name()).isEqualTo("issue-routing"));
    }

    @Test
    void aDifferentRequestFingerprintMissesAndCarriesTheReplayKey() {
        MatchResult result = StrictMatcher.match(fingerprints(OTHER), MODEL,
                List.of(cassette("issue-routing", REQUEST)));

        assertThat(result).isInstanceOfSatisfying(MatchResult.Miss.class, miss -> {
            assertThat(miss.requestFingerprint()).isEqualTo(OTHER);
            assertThat(miss.diagnosis().closestCassette()).isEqualTo("issue-routing");
        });
    }

    @Test
    void neitherStateNorContractAloneCanRescueAMiss() {
        // state 与 contract 都对得上，只有 request fingerprint 不同（例如 model 变了）：STRICT 照样 MISS。
        Cassette recorded = cassette("issue-routing", REQUEST);
        Cassette sameContractOtherRequest = Cassette.of(recorded.name(), new Interaction(
                recorded.metadata(), recorded.request(), recorded.response(),
                new Fingerprints(OTHER, recorded.fingerprints().contract(), recorded.fingerprints().state())));

        assertThat(StrictMatcher.match(fingerprints(REQUEST), MODEL, List.of(sameContractOtherRequest)))
                .isInstanceOf(MatchResult.Miss.class);
    }

    @Test
    void noCandidatesIsAMiss() {
        assertThat(StrictMatcher.match(fingerprints(REQUEST), MODEL, List.of()))
                .isInstanceOfSatisfying(MatchResult.Miss.class,
                        miss -> assertThat(miss.diagnosis().closestCassette()).isNull());
    }

    @Test
    void theFirstCandidateInOrderWins() {
        // 装载顺序（按 cassette 名称排序）就是优先级，于是"第一个命中者胜出"是确定的。
        MatchResult result = StrictMatcher.match(fingerprints(REQUEST), MODEL,
                List.of(cassette("aaa-first", REQUEST), cassette("zzz-second", REQUEST)));

        assertThat(result).isInstanceOfSatisfying(MatchResult.Hit.class,
                hit -> assertThat(hit.cassette().name()).isEqualTo("aaa-first"));
    }

    @Test
    void aCassetteWithoutARequestFingerprintIsSkippedInsteadOfBlowingUp() {
        // cassette 是人可以手改的 JSON：缺指纹的那一个只是不命中。
        MatchResult result = StrictMatcher.match(fingerprints(REQUEST), MODEL,
                List.of(cassette("hand-edited", null), cassette("issue-routing", REQUEST)));

        assertThat(result).isInstanceOfSatisfying(MatchResult.Hit.class,
                hit -> assertThat(hit.cassette().name()).isEqualTo("issue-routing"));
    }

    private static Fingerprints fingerprints(String request) {
        return new Fingerprints(request, "sha256:contract", "sha256:state");
    }

    private static Cassette cassette(String name, String requestFingerprint) {
        return Cassette.of(name, new Interaction(
                new CassetteMetadata("2026-09-21T10:20:30Z", "0.1.0-test", 132),
                new RecordedRequest("POST", "/v1/systemone", MODEL, "jev-1.13.0",
                        NullNode.getInstance(), NullNode.getInstance()),
                new RecordedResponse(200, Map.of("Content-Type", List.of("application/json")),
                        NullNode.getInstance()),
                new Fingerprints(requestFingerprint, "sha256:contract", "sha256:state")));
    }
}
