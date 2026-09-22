package io.jevtape.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jevtape.contract.DecisionContract.Criterion;
import io.jevtape.contract.DecisionContract.Question;
import io.jevtape.fingerprint.FingerprintEngine;
import io.jevtape.testing.JevProtocolFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 官方 System One 协议样例（{@code fixtures/jev-protocol}）上的回归网。
 *
 * <p>前半部分是审查里复现的 F01：v0.5.0 的 {@link JevProtocolAdapter} 只认大写 {@code Choice} /
 * {@code Score} / {@code Noul} 与 {@code options[]} / {@code levels[]}，因此官方小写形状的 criteria 一律
 * 解析为空 —— 契约看不见任何可选项，{@code --probability} 也落不到官方的 {@code noul} 字段上。S02 之后官方
 * 形状是主路径，大写形状退为历史兼容分支，那一半由 {@link ContractDiffTest} 与 {@code fixtures/cassette-v1}
 * 上的断言守着。
 *
 * <p>后半部分检查这批样例本身，确认 S01 要求的形状（小写 type、Choice 的 criteria map、Score 的 criteria
 * array、Noul 的 criteria object 与 noul 应答、结构化 instructions、四个错误状态码）真的都在，于是前面那些
 * 断言不会因样例写错而空转。
 *
 * <p>{@code docs/matching.md} 的 verify 一节与文末"必须覆盖的测试断言"随 S02 一起改成官方形状：旧文档写着
 * "Noul 只有一条 criteria（没有标签）"、"Score 的等级在 {@code levels[]}"，那是 v1 的语义，与下面的
 * {@link #noulRulesComeFromTheOfficialTrueFalseObject} 直接冲突（官方 Noul 是 {@code true} / {@code false}
 * 两条**有标签**的规则）。AGENTS.md 把那份清单当验收权威，因此实现与文档必须在同一次改动里对齐。
 */
class OfficialProtocolTest {

    /** 官方 Choice 的 criteria 是一个 map：键就是选项值，值就是那一条 rubric。 */
    @Test
    void choiceOptionsComeFromTheOfficialCriteriaMap() {
        Question department = questionOf(contract("choice"), "department");

        assertThat(department.type()).isEqualToIgnoringCase("choice");
        assertThat(department.criteria()).containsExactlyInAnyOrder(
                new Criterion("billing", "Payments, invoicing, refunds"),
                new Criterion("technical", "Bugs, outages, integrations"),
                new Criterion("sales", "Pricing, upgrades, new accounts"));
    }

    /** 官方 Score 的 criteria 是一个**有序** array：下标就是等级，顺序参与指纹，因此这里用 containsExactly。 */
    @Test
    void scoreLevelsKeepTheOfficialCriteriaArrayOrder() {
        Question frustration = questionOf(contract("score"), "frustration");

        assertThat(frustration.type()).isEqualToIgnoringCase("score");
        assertThat(frustration.criteria()).extracting(Criterion::text)
                .containsExactly("Calm", "Frustrated", "Very angry");
    }

    /** 官方 Noul 的 criteria 是带 true / false 两个键的 object，两个键各是一条规则。 */
    @Test
    void noulRulesComeFromTheOfficialTrueFalseObject() {
        Question urgent = questionOf(contract("noul"), "is_urgent");

        assertThat(urgent.type()).isEqualToIgnoringCase("noul");
        assertThat(urgent.criteria()).containsExactlyInAnyOrder(
                new Criterion("true", "Explicitly time-sensitive"),
                new Criterion("false", "No urgency expressed"));
    }

    /** 探针里那条最直接的复现：三类官方 question 解析完，criteria 全都是空的。 */
    @Test
    void everyOfficialQuestionKeepsItsCriteria() {
        assertThat(contract("all-types").questions())
                .hasSize(3)
                .allSatisfy(question -> assertThat(question.criteria())
                        .as("%s criteria", question.name())
                        .isNotEmpty());
    }

    /**
     * 官方规范说 instructions “can be a string, an object, or an array”，而 v0.5.0 用只接受字符串的
     * {@code text()} 读它，于是结构化的一律变成 null。S02 之后领域对象保留原始 {@link JsonNode}：两份**不同**
     * 的结构化说明不再塌成同一个值。
     */
    @Test
    void structuredInstructionsAreNotDropped() {
        DecisionContract contract = contract("structured-instructions");

        assertThat(questionOf(contract, "route").instructions())
                .isNotNull()
                .isNotEqualTo(questionOf(contract, "is_urgent").instructions());
        // criteria 写成 null 是"没写"，不是"写了一段空文案"：不该凭空造出一条规则来。
        assertThat(questionOf(contract, "is_urgent").criteria()).isEmpty();
    }

    /**
     * F01 的用户可见症状：官方 Choice 的一条 rubric 改了字，契约诊断里却什么都看不到（因为两边都解析成
     * 0 条 criteria）。
     */
    @Test
    void aRewordedOfficialRubricIsVisibleInTheContract() {
        JsonNode recorded = JevProtocolFixtures.questions("choice");
        ObjectNode reworded = recorded.deepCopy();
        ((ObjectNode) reworded.path("department").path("criteria"))
                .put("billing", "Payments and refunds only.");

        ContractDiff diff = diff(recorded, reworded);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(diff.changes()).extracting(ContractDiff.Change::kind)
                .contains(ContractDiff.Change.Kind.CRITERION_CHANGED);
    }

    /** criteria 的值本身也可以是 object / array：rubric 里的一个字段改了，诊断同样要指名那一条。 */
    @Test
    void aChangedStructuredRubricIsVisibleInTheContract() {
        JsonNode recorded = JevProtocolFixtures.questions("structured-instructions");
        ObjectNode changed = recorded.deepCopy();
        ((ObjectNode) changed.path("route").path("criteria").path("billing"))
                .put("summary", "Money movement, and nothing else.");

        ContractDiff diff = diff(recorded, changed);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(diff.changes()).extracting(ContractDiff.Change::subject).contains("option billing");
    }

    /**
     * criteria map 的键序不参与 canonical JSON，因此也不参与指纹：同一份 Choice 换个键序既不是 FAIL，也不该在
     * PASS 的报告里留下一行自相矛盾的 {@code ~ order}。只有 array 形状（官方 Score 的等级、v1 的
     * {@code options[]} / {@code levels[]}）才有顺序可谈。
     */
    @Test
    void aReorderedOfficialCriteriaMapIsNotAChange() {
        ObjectNode reordered = JevProtocolFixtures.questions("choice").deepCopy();
        ObjectNode criteria = (ObjectNode) reordered.path("department").path("criteria");
        ObjectNode backwards = JsonNodeFactory.instance.objectNode();
        List.of("sales", "technical", "billing").forEach(label -> backwards.set(label, criteria.get(label)));
        ((ObjectNode) reordered.path("department")).set("criteria", backwards);

        ContractDiff diff = diff(JevProtocolFixtures.questions("choice"), reordered);

        assertThat(diff.status()).isEqualTo(ContractDiff.Status.PASS);
        assertThat(diff.changes()).isEmpty();
    }

    /** 官方 Score 的等级标签就是 array 下标，因此重排是"每个等级的文案变了"，而不是 {@code ~ order}。 */
    @Test
    void aReorderedOfficialScoreArrayShowsUpAsChangedLevels() {
        ObjectNode reordered = JevProtocolFixtures.questions("score").deepCopy();
        ArrayNode levels = (ArrayNode) reordered.path("frustration").path("criteria");
        ArrayNode reversed = JsonNodeFactory.instance.arrayNode();
        for (int level = levels.size() - 1; level >= 0; level--) {
            reversed.add(levels.get(level));
        }
        ((ObjectNode) reordered.path("frustration")).set("criteria", reversed);

        ContractDiff diff = diff(JevProtocolFixtures.questions("score"), reordered);

        // 中间那一级 reversing 之后还是同一句文案：比较是按标签查表的，不是"数组动过就全报一遍"。
        assertThat(diff.status()).isEqualTo(ContractDiff.Status.FAIL);
        assertThat(diff.changes()).extracting(ContractDiff.Change::subject)
                .containsExactly("level 0", "level 2");
    }

    /** F01 的另一半：官方 Noul 的作答字段叫 {@code noul}，simulate 的 {@code --probability} 落不上去。 */
    @Test
    void aProbabilityOverrideReachesTheOfficialNoulField() {
        byte[] body = JevProtocolFixtures.responseBody("noul");
        AnswerOverrides overrides = new AnswerOverrides(null, null, null, null, 0.2);

        assertThat(JevProtocolAdapter.canOverride(JevProtocolAdapter.parseResponse(body).body(), overrides))
                .isTrue();
        assertThat(new String(JevProtocolAdapter.overrideAnswers(body, overrides), StandardCharsets.UTF_8))
                .contains("\"noul\":0.2");
    }

    // ---- 以下是样例自检，现在就跑 ----

    @Test
    void everyFixtureIsARequestResponsePair() {
        assertThat(JevProtocolFixtures.names()).isNotEmpty();

        for (String name : JevProtocolFixtures.names()) {
            JsonNode root = JevProtocolFixtures.root(name);

            assertThat(root.path("request").path("model").asText())
                    .as("%s requests jev-latest", name).isEqualTo("jev-latest");
            assertThat(root.path("request").has("state"))
                    .as("%s carries a state", name).isTrue();
            assertThat(root.path("request").path("questions").isObject())
                    .as("%s carries a questions object", name).isTrue();
            assertThat(JevProtocolFixtures.status(name))
                    .as("%s carries an HTTP status", name).isBetween(100, 599);
            assertThat(root.path("response").path("body").isObject())
                    .as("%s carries a response body object", name).isTrue();
        }
    }

    /** S01 要求这批样例覆盖的形状；少了哪一样，上面的 @Disabled 用例就失去意义。 */
    @Test
    void theFixturesCoverEveryShapeS01Requires() {
        assertThat(types()).contains("choice", "score", "noul");

        assertThat(rawQuestion("choice", "department").path("criteria").isObject()).isTrue();
        assertThat(rawQuestion("score", "frustration").path("criteria").isArray()).isTrue();
        JsonNode noulCriteria = rawQuestion("noul", "is_urgent").path("criteria");
        assertThat(noulCriteria.isObject()).isTrue();
        assertThat(noulCriteria.has("true") && noulCriteria.has("false")).isTrue();

        // 官方 Noul 的作答字段是 noul，不是 probability。
        assertThat(rawAnswer("noul", "is_urgent").path("noul").isNumber()).isTrue();

        // instructions 的三种形态：string / object / array。
        assertThat(rawQuestion("choice", "department").path("instructions").isTextual()).isTrue();
        assertThat(rawQuestion("structured-instructions", "route").path("instructions").isObject()).isTrue();
        assertThat(rawQuestion("structured-instructions", "is_urgent").path("instructions").isArray()).isTrue();
        // state 也允许 object，且 criteria 可以是 null。
        assertThat(JevProtocolFixtures.root("structured-instructions").path("request").path("state").isObject()).isTrue();
        assertThat(rawQuestion("structured-instructions", "is_urgent").path("criteria").isNull()).isTrue();

        // 官方文档化的四个错误状态码各有一份样例，usage 用官方的 snake_case。
        assertThat(statuses()).contains(200, 401, 422, 429, 529);
        assertThat(JevProtocolFixtures.names()).filteredOn(name -> JevProtocolFixtures.status(name) != 200)
                .hasSize(4);
        JsonNode usage = JevProtocolFixtures.root("choice").path("response").path("body").path("usage");
        assertThat(usage.has("input_tokens")).isTrue();
        assertThat(usage.has("output_tokens")).isTrue();
    }

    private static DecisionContract contract(String fixture) {
        return JevProtocolAdapter.decisionContract(JevProtocolFixtures.questions(fixture));
    }

    private static Question questionOf(DecisionContract contract, String name) {
        return contract.questions().stream()
                .filter(question -> question.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Fixture carries no question '" + name + "'"));
    }

    private static JsonNode rawQuestion(String fixture, String name) {
        return JevProtocolFixtures.questions(fixture).path(name);
    }

    private static JsonNode rawAnswer(String fixture, String name) {
        return JevProtocolFixtures.root(fixture).path("response").path("body").path("answers").path(name);
    }

    private static List<String> types() {
        return JevProtocolFixtures.names().stream()
                .flatMap(name -> JevProtocolFixtures.questions(name).properties().stream())
                .map(entry -> entry.getValue().path("type").asText())
                .distinct()
                .toList();
    }

    private static List<Integer> statuses() {
        return JevProtocolFixtures.names().stream().map(JevProtocolFixtures::status).distinct().toList();
    }

    private static ContractDiff diff(JsonNode recorded, JsonNode current) {
        return ContractDiff.between(FingerprintEngine.contract(recorded), FingerprintEngine.contract(current),
                JevProtocolAdapter.decisionContract(recorded), JevProtocolAdapter.decisionContract(current));
    }
}
