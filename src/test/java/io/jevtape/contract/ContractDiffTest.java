package io.jevtape.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jevtape.fingerprint.FingerprintEngine;
import io.jevtape.shared.InvalidJevRequest;
import io.jevtape.testing.JevProtocolFixtures;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decision Contract 的比较语义（charter §18、§44）：三种 question 都被归一成"标签 + criteria"，于是新增、
 * 删除、改文案、换类型、重排各自都能被指出来；状态由 contract fingerprint 与变化的形状共同决定 ——
 * 指纹相等是 PASS，只有新增是 WARN，既有内容被改动 / 删除 / 重排是 FAIL。
 *
 * <p>指纹在这里是**真的算出来的**，不是随手写的常量，因此这些断言同时守着"结构比较与指纹不会各说各话"。
 */
class ContractDiffTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Choice / Score / Noul 各一个，形状与 {@code fixtures/cassette-v1/issue-routing.json} 一致。 */
    private static final String QUESTIONS = """
            {"route":{"type":"Choice","instructions":"Choose the team.",\
            "options":[{"value":"billing","criteria":"Invoices."},{"value":"technical","criteria":"Bugs."}]},\
            "severity":{"type":"Score","instructions":"Rate the severity.",\
            "levels":[{"score":0,"criteria":"Cosmetic."},{"score":5,"criteria":"Fully blocked."}]},\
            "urgent":{"type":"Noul","instructions":"Is this urgent?","criteria":"Customer is blocked."}}""";

    /** 同样的内容，object key 序与空白都换了，数组顺序不变：canonical JSON 之下这是同一份契约。 */
    private static final String REORDERED_KEYS = """
            {
              "urgent" : { "criteria" : "Customer is blocked.", "type" : "Noul",
                           "instructions" : "Is this urgent?" },
              "route" : { "type" : "Choice", "instructions" : "Choose the team.",
                          "options" : [ { "criteria" : "Invoices.", "value" : "billing" },
                                        { "criteria" : "Bugs.", "value" : "technical" } ] },
              "severity" : { "levels" : [ { "criteria" : "Cosmetic.", "score" : 0 },
                                          { "criteria" : "Fully blocked.", "score" : 5 } ],
                             "instructions" : "Rate the severity.", "type" : "Score" }
            }
            """;

    /** 与 QUESTIONS 的差别只在 options 的**数组**顺序：内容一字未改，指纹却变了。 */
    private static final String SWAPPED_ARRAY_ORDER = """
            {"route":{"type":"Choice","instructions":"Choose the team.",\
            "options":[{"value":"technical","criteria":"Bugs."},{"value":"billing","criteria":"Invoices."}]},\
            "severity":{"type":"Score","instructions":"Rate the severity.",\
            "levels":[{"score":0,"criteria":"Cosmetic."},{"score":5,"criteria":"Fully blocked."}]},\
            "urgent":{"type":"Noul","instructions":"Is this urgent?","criteria":"Customer is blocked."}}""";

    private static final String EXTRA_OPTION = QUESTIONS.replace(
            "{\"value\":\"technical\",\"criteria\":\"Bugs.\"}]",
            "{\"value\":\"technical\",\"criteria\":\"Bugs.\"},{\"value\":\"other\",\"criteria\":\"Anything else.\"}]");

    private static final String FEWER_OPTIONS = QUESTIONS.replace(
            ",{\"value\":\"technical\",\"criteria\":\"Bugs.\"}", "");

    private static final String REWORDED_OPTION = QUESTIONS.replace(
            "\"criteria\":\"Invoices.\"", "\"criteria\":\"Invoices, payments and refunds.\"");

    private static final String REWORDED_INSTRUCTIONS = QUESTIONS.replace(
            "Choose the team.", "Pick the team that should handle this.");

    private static final String REWORDED_SCORE_LEVEL = QUESTIONS.replace(
            "\"criteria\":\"Fully blocked.\"", "\"criteria\":\"The customer cannot work at all.\"");

    private static final String REWORDED_NOUL_CRITERIA = QUESTIONS.replace(
            "\"criteria\":\"Customer is blocked.\"", "\"criteria\":\"Customer is blocked and losing money.\"");

    private static final String RETYPED_QUESTION = QUESTIONS.replace(
            "\"urgent\":{\"type\":\"Noul\"", "\"urgent\":{\"type\":\"Choice\"");

    private static final String EXTRA_QUESTION = QUESTIONS.replace(
            "\"urgent\":{", "\"spam\":{\"type\":\"Noul\",\"instructions\":\"Is this spam?\",\"criteria\":\"Unsolicited.\"},\"urgent\":{");

    private static final String DROPPED_QUESTION = QUESTIONS.replace(
            "\"severity\":{\"type\":\"Score\",\"instructions\":\"Rate the severity.\",\"levels\":"
                    + "[{\"score\":0,\"criteria\":\"Cosmetic.\"},{\"score\":5,\"criteria\":\"Fully blocked.\"}]},", "");

    /** 多出来一个 JevTape 不认识的字段：结构比较看不出来，指纹却不一样。 */
    private static final String UNMODELLED_FIELD = QUESTIONS.replace(
            "\"route\":{\"type\":\"Choice\"", "\"route\":{\"type\":\"Choice\",\"weight\":2");

    /**
     * F02 的原始复现输入：既有 question 上一个**不建模**的字段被改了值，同时新增一个 question。
     * 与 {@link #UNMODELLED_FIELD} 的差别在于这里是改值而不是加字段 —— 两者对结构比较同样不可见。
     */
    private static final String WITH_UNMODELLED_FIELD =
            "{\"q\":{\"type\":\"Noul\",\"criteria\":\"x\",\"extension\":\"old\"}}";

    private static final String UNMODELLED_FIELD_CHANGED_PLUS_NEW_QUESTION =
            "{\"q\":{\"type\":\"Noul\",\"criteria\":\"x\",\"extension\":\"new\"},"
                    + "\"added\":{\"type\":\"Noul\",\"criteria\":\"y\"}}";

    @Test
    void anUnchangedContractPasses() {
        ContractDiff diff = diff(QUESTIONS, QUESTIONS);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.PASS);
        assertThat(diff.changes()).isEmpty();
    }

    /** key 序与空白不参与契约（docs/matching.md），因此重排过的同一份内容仍然是 PASS。 */
    @Test
    void objectKeyOrderChangesNothing() {
        ContractDiff diff = diff(QUESTIONS, REORDERED_KEYS);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.PASS);
        assertThat(diff.changes()).isEmpty();
        assertThat(diff.recordedContract()).isEqualTo(diff.currentContract());
    }

    /** 数组保序参与指纹，所以换个顺序就是契约变化 —— 即使没有任何一条文案被改过。 */
    @Test
    void reorderedOptionsFailAndSayWhichOrderChanged() {
        ContractDiff diff = diff(QUESTIONS, SWAPPED_ARRAY_ORDER);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(rendered(diff)).containsExactly("ORDER_CHANGED route order billing, technical → technical, billing");
    }

    /** 纯新增只是 WARN：旧的录制仍然回答了它当时被问到的那些问题，但已经不覆盖今天的选项空间。 */
    @Test
    void anAddedOptionWarns() {
        ContractDiff diff = diff(QUESTIONS, EXTRA_OPTION);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.WARN);
        assertThat(rendered(diff)).containsExactly("CRITERION_ADDED route option other");
    }

    @Test
    void anAddedQuestionWarns() {
        ContractDiff diff = diff(QUESTIONS, EXTRA_QUESTION);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.WARN);
        assertThat(rendered(diff)).containsExactly("QUESTION_ADDED spam question");
    }

    /** 既有内容被改动就是 FAIL：录制下来的那个决策回答的已经不是同一个问题了。 */
    @Test
    void rewordedCriteriaFail() {
        ContractDiff diff = diff(QUESTIONS, REWORDED_OPTION);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(rendered(diff)).containsExactly("CRITERION_CHANGED route option billing");
    }

    @Test
    void rewordedInstructionsFail() {
        ContractDiff diff = diff(QUESTIONS, REWORDED_INSTRUCTIONS);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(rendered(diff)).containsExactly("INSTRUCTIONS_CHANGED route instructions");
    }

    @Test
    void aRemovedOptionFails() {
        ContractDiff diff = diff(QUESTIONS, FEWER_OPTIONS);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(rendered(diff)).containsExactly("CRITERION_REMOVED route option technical");
    }

    @Test
    void aRemovedQuestionFails() {
        ContractDiff diff = diff(QUESTIONS, DROPPED_QUESTION);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(rendered(diff)).containsExactly("QUESTION_REMOVED severity question");
    }

    /** 换类型会把整条 criteria 结构一起换掉，因此诊断里既有类型也有随之而来的一增一删。 */
    @Test
    void aChangedTypeFailsAndNamesBothSides() {
        ContractDiff diff = diff(QUESTIONS, RETYPED_QUESTION);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(rendered(diff)).containsExactly(
                "TYPE_CHANGED urgent type Noul → Choice",
                "CRITERION_REMOVED urgent criteria");
    }

    /** Score 的可选项在诊断里叫 level，标签是分数本身。 */
    @Test
    void scoreLevelsAreNamedAfterTheJevConcept() {
        ContractDiff diff = diff(QUESTIONS, REWORDED_SCORE_LEVEL);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(rendered(diff)).containsExactly("CRITERION_CHANGED severity level 5");
    }

    /** Noul 只有一条 criteria，没有名字可打，因此诊断里就只剩这个概念本身。 */
    @Test
    void aNoulCriterionHasNoLabel() {
        ContractDiff diff = diff(QUESTIONS, REWORDED_NOUL_CRITERIA);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(rendered(diff)).containsExactly("CRITERION_CHANGED urgent criteria");
    }

    /** 新增与改动同时出现时按更严的那一个判：WARN 只留给"纯粹多了一点东西"。 */
    @Test
    void anAdditionDoesNotRescueAChange() {
        ContractDiff diff = diff(QUESTIONS, EXTRA_OPTION.replace("\"criteria\":\"Invoices.\"", "\"criteria\":\"Refunds.\""));

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(rendered(diff)).containsExactlyInAnyOrder(
                "CRITERION_ADDED route option other",
                "CRITERION_CHANGED route option billing");
    }

    /**
     * 指纹不等而结构比较一无所获时判 FAIL：变的是这个模型不认识的字段。宁可说"变了，但我说不清哪里"，
     * 也不能在 replay 必定 MISS 的时候亮绿灯。
     */
    @Test
    void aChangeOutsideTheModelStillFails() {
        ContractDiff diff = diff(QUESTIONS, UNMODELLED_FIELD);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(diff.changes()).isEmpty();
        assertThat(diff.recordedContract()).isNotEqualTo(diff.currentContract());
    }

    /**
     * F02：同一个盲区被一个**合法**新增掩盖了。结构比较只看得见 {@code QUESTION_ADDED}，于是
     * {@code allMatch(addition)} 判 WARN、退出码 0 —— 而既有 question 里那个不建模的字段已经变了，replay
     * 必定 MISS。{@link #aChangeOutsideTheModelStillFails} 证明没有新增时这里会判 FAIL，因此漏判正是
     * "新增把变化救了回来"。
     */
    @Test
    @Disabled("S03：既有内容被改 + 一个合法新增，目前判 WARN 而不是 FAIL")
    void anUnmodelledChangeIsNotRescuedByALegitimateAddition() {
        ContractDiff diff = diff(WITH_UNMODELLED_FIELD, UNMODELLED_FIELD_CHANGED_PLUS_NEW_QUESTION);

        assertThat(diff.recordedContract()).isNotEqualTo(diff.currentContract());
        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
    }

    /**
     * F02 的用户可见形态：官方 Choice 的 criteria map 少了一个键（等于删掉一个选项），同时新增一个
     * question。今天两边都解析成 0 条 criteria，删除根本看不见，于是同样停在 WARN。
     */
    @Test
    @Disabled("S02 + S03：官方 Choice 少了一个选项 + 新增 question，目前判 WARN")
    void aRemovedOfficialOptionIsNotRescuedByANewQuestion() {
        ObjectNode recorded = JevProtocolFixtures.questions("choice").deepCopy();
        ObjectNode current = recorded.deepCopy();
        ((ObjectNode) current.path("department").path("criteria")).remove("sales");
        current.set("is_urgent", JevProtocolFixtures.questions("noul").path("is_urgent"));

        ContractDiff diff = diff(recorded, current);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(diff.changes()).extracting(ContractDiff.Change::kind)
                .contains(ContractDiff.Change.Kind.CRITERION_REMOVED);
    }

    /** 手改过的 cassette 可能缺 contract 指纹，这时只剩结构比较可依据 —— 它说没变就是 PASS。 */
    @Test
    void aMissingRecordedFingerprintLeavesTheStructuralComparisonInCharge() {
        assertThat(diff(null, QUESTIONS, QUESTIONS).status()).isEqualTo(ContractDiff.Status.PASS);
        assertThat(diff(null, QUESTIONS, EXTRA_OPTION).status()).isEqualTo(ContractDiff.Status.WARN);
        assertThat(diff(null, QUESTIONS, REWORDED_OPTION).status()).isEqualTo(ContractDiff.Status.FAIL);
    }

    @Test
    void aRequestWithoutQuestionsHasNoContract() {
        assertThatThrownBy(() -> JevProtocolAdapter.decisionContract(json("{\"route\":{}}").get("questions")))
                .isInstanceOf(InvalidJevRequest.class)
                .hasMessageContaining("'questions' object");
    }

    private static ContractDiff diff(String recordedQuestions, String currentQuestions) {
        return diff(FingerprintEngine.contract(json(recordedQuestions)), recordedQuestions, currentQuestions);
    }

    private static ContractDiff diff(String recordedFingerprint, String recordedQuestions,
                                     String currentQuestions) {
        return ContractDiff.between(recordedFingerprint, FingerprintEngine.contract(json(currentQuestions)),
                JevProtocolAdapter.decisionContract(json(recordedQuestions)),
                JevProtocolAdapter.decisionContract(json(currentQuestions)));
    }

    /** 已经是一棵树的两侧（例如从 {@code fixtures/jev-protocol} 读出来再改过一份）走这条。 */
    private static ContractDiff diff(JsonNode recordedQuestions, JsonNode currentQuestions) {
        return ContractDiff.between(FingerprintEngine.contract(recordedQuestions),
                FingerprintEngine.contract(currentQuestions),
                JevProtocolAdapter.decisionContract(recordedQuestions),
                JevProtocolAdapter.decisionContract(currentQuestions));
    }

    /** 把一条变化压成一行，于是断言读起来就是用户在 Changes 一节里看到的那些短语。 */
    private static List<String> rendered(ContractDiff diff) {
        return diff.changes().stream()
                .map(change -> change.kind() + " " + change.question() + " " + change.subject()
                        + (change.before() == null ? "" : " " + change.before() + " → " + change.after()))
                .toList();
    }

    private static JsonNode json(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new AssertionError("Test JSON is malformed", e);
        }
    }
}
