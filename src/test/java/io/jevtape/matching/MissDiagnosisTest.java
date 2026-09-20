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

import static io.jevtape.matching.MissDiagnosis.Verdict.CHANGED;
import static io.jevtape.matching.MissDiagnosis.Verdict.MATCH;
import static io.jevtape.matching.MissDiagnosis.Verdict.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MISS 的诊断（charter §32）：三项都是逐字符相等的判断，"最接近"是数出来的 —— MATCH 最多者胜出，并列时取
 * 候选顺序里的第一个。这里没有相似度，诊断不会比匹配本身更聪明。
 */
class MissDiagnosisTest {

    private static final String REQUEST = "sha256:request";
    private static final String STATE = "sha256:state-a";
    private static final String OTHER_STATE = "sha256:state-b";
    private static final String CONTRACT = "sha256:contract-a";
    private static final String OTHER_CONTRACT = "sha256:contract-b";
    private static final String MODEL = "jev-latest";
    private static final String OTHER_MODEL = "jev-2";

    @Test
    void aChangedQuestionShowsUpAsContractChanged() {
        // 路线图任务 10 的验收：question 变了 → MISS，且诊断指名 Contract: CHANGED。
        MissDiagnosis diagnosis = diagnose(STATE, OTHER_CONTRACT, MODEL,
                cassette("issue-routing", STATE, CONTRACT, MODEL));

        assertThat(diagnosis.requestFingerprint()).isEqualTo(REQUEST);
        assertThat(diagnosis.closestCassette()).isEqualTo("issue-routing");
        assertThat(diagnosis.state()).isEqualTo(MATCH);
        assertThat(diagnosis.contract()).isEqualTo(CHANGED);
        assertThat(diagnosis.model()).isEqualTo(MATCH);
    }

    @Test
    void aChangedStateShowsUpAsStateChanged() {
        MissDiagnosis diagnosis = diagnose(OTHER_STATE, CONTRACT, MODEL,
                cassette("issue-routing", STATE, CONTRACT, MODEL));

        assertThat(diagnosis.state()).isEqualTo(CHANGED);
        assertThat(diagnosis.contract()).isEqualTo(MATCH);
        assertThat(diagnosis.model()).isEqualTo(MATCH);
    }

    @Test
    void aChangedModelShowsUpAsModelChanged() {
        MissDiagnosis diagnosis = diagnose(STATE, CONTRACT, OTHER_MODEL,
                cassette("issue-routing", STATE, CONTRACT, MODEL));

        assertThat(diagnosis.state()).isEqualTo(MATCH);
        assertThat(diagnosis.contract()).isEqualTo(MATCH);
        assertThat(diagnosis.model()).isEqualTo(CHANGED);
    }

    @Test
    void aModelAbsentOnBothSidesIsTheSameModel() {
        // 请求里没有 model 字段是一种取值，不是一个未知数：录的时候也没有，就是同一件事。
        assertThat(diagnose(STATE, CONTRACT, null, cassette("issue-routing", STATE, CONTRACT, null)).model())
                .isEqualTo(MATCH);
    }

    @Test
    void theClosestCassetteIsTheOneWithTheMostMatches() {
        MissDiagnosis diagnosis = diagnose(STATE, CONTRACT, MODEL,
                cassette("nothing-in-common", OTHER_STATE, OTHER_CONTRACT, OTHER_MODEL),
                cassette("same-state-and-model", STATE, OTHER_CONTRACT, MODEL));

        assertThat(diagnosis.closestCassette()).isEqualTo("same-state-and-model");
    }

    @Test
    void aTieGoesToTheFirstCandidateInOrder() {
        // 候选顺序由 loadAll 按名称排定，因此并列时的选择也是确定的。
        MissDiagnosis diagnosis = diagnose(STATE, CONTRACT, MODEL,
                cassette("aaa-only-model-matches", OTHER_STATE, OTHER_CONTRACT, MODEL),
                cassette("zzz-only-model-matches", OTHER_STATE, OTHER_CONTRACT, MODEL));

        assertThat(diagnosis.closestCassette()).isEqualTo("aaa-only-model-matches");
    }

    @Test
    void noCandidatesLeavesNothingToCompare() {
        MissDiagnosis diagnosis = diagnose(STATE, CONTRACT, MODEL);

        assertThat(diagnosis.closestCassette()).isNull();
        assertThat(diagnosis.state()).isEqualTo(UNKNOWN);
        assertThat(diagnosis.contract()).isEqualTo(UNKNOWN);
        assertThat(diagnosis.model()).isEqualTo(UNKNOWN);
        assertThat(diagnosis.requestFingerprint()).isEqualTo(REQUEST);
    }

    @Test
    void aCassetteMissingAFingerprintIsUnknownNotChanged() {
        // cassette 是人可以手改的 JSON：缺一项指纹时不能断言它变了，那是编造原因。
        Cassette handEdited = Cassette.of("hand-edited", new Interaction(
                new CassetteMetadata("2026-09-21T10:20:30Z", "0.1.0-test", 132),
                new RecordedRequest("POST", "/v1/systemone", MODEL, null,
                        NullNode.getInstance(), NullNode.getInstance()),
                new RecordedResponse(200, Map.of(), NullNode.getInstance()),
                new Fingerprints("sha256:recorded", null, STATE)));

        MissDiagnosis diagnosis = diagnose(STATE, CONTRACT, MODEL, handEdited);

        assertThat(diagnosis.state()).isEqualTo(MATCH);
        assertThat(diagnosis.contract()).isEqualTo(UNKNOWN);
    }

    @Test
    void theSummaryNamesTheClosestCassetteAndAllThreeVerdicts() {
        assertThat(diagnose(STATE, OTHER_CONTRACT, MODEL, cassette("issue-routing", STATE, CONTRACT, MODEL))
                .summary())
                .isEqualTo("closest issue-routing (state MATCH, contract CHANGED, model MATCH)");

        assertThat(diagnose(STATE, CONTRACT, MODEL).summary()).isEqualTo("no cassette to compare against");
    }

    private static MissDiagnosis diagnose(String state, String contract, String model, Cassette... candidates) {
        return MissDiagnosis.of(new Fingerprints(REQUEST, contract, state), model, List.of(candidates));
    }

    private static Cassette cassette(String name, String state, String contract, String model) {
        return Cassette.of(name, new Interaction(
                new CassetteMetadata("2026-09-21T10:20:30Z", "0.1.0-test", 132),
                new RecordedRequest("POST", "/v1/systemone", model, "jev-1.13.0",
                        NullNode.getInstance(), NullNode.getInstance()),
                new RecordedResponse(200, Map.of(), NullNode.getInstance()),
                new Fingerprints("sha256:recorded-" + name, contract, state)));
    }
}
