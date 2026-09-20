package io.jevtape.transport;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.CassetteRepository;
import io.jevtape.cassette.FileCassetteRepository;
import io.jevtape.matching.MatchResult;
import io.jevtape.matching.MissDiagnosis;
import io.jevtape.shared.InvalidJevRequest;
import io.jevtape.shared.ReplayMiss;
import io.jevtape.testing.FakeJevServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * replay 闭环（charter §61）：录下来的那一次调用，在**上游已经消失**之后仍然得到同一份响应；语义变了一点
 * 就是 MISS，而且整个过程一个字节都不出网。
 *
 * <p>每个测试都先用真实的 record 路径把 cassette 落到临时目录里，然后关掉 FakeJevServer —— 于是"replay
 * 期间零上游请求"不是被 mock 出来的，而是物理上没有上游可去。
 */
class ReplayJevTransportTest {

    private static final String REQUEST_BODY = """
            {"model":"jev-latest","state":{"ticket":{"id":"SUP-4821","subject":"Cannot export invoice PDF"}},\
            "questions":{"route":{"type":"Choice","instructions":"Choose the team.",\
            "options":[{"value":"billing","criteria":"Invoices."},{"value":"technical","criteria":"Bugs."}]}}}""";

    /** 与 {@link #REQUEST_BODY} 语义完全相同，只是 object key 的顺序不一样（array 顺序保持不变）。 */
    private static final String SAME_BODY_OTHER_KEY_ORDER = """
            {"questions":{"route":{"options":[{"criteria":"Invoices.","value":"billing"},\
            {"criteria":"Bugs.","value":"technical"}],"type":"Choice","instructions":"Choose the team."}},\
            "state":{"ticket":{"subject":"Cannot export invoice PDF","id":"SUP-4821"}},"model":"jev-latest"}""";

    private static final String RESPONSE_BODY =
            "{\"model\":\"jev-1.13.0\",\"answers\":{\"route\":{\"choice\":\"billing\",\"confidence\":0.74}}}";

    @TempDir
    Path cassetteDir;

    private final List<MatchResult> decisions = new CopyOnWriteArrayList<>();

    @Test
    void replaysTheRecordedResponseAfterTheUpstreamIsGone() {
        record(200, Map.of("Content-Type", "application/json"), RESPONSE_BODY);

        JevResponse replayed = replaying().send(request(REQUEST_BODY));

        assertThat(replayed.status()).isEqualTo(200);
        assertThat(replayed.body()).isEqualTo(bytes(RESPONSE_BODY));
        // header 名字是磁带里存的那一份：java.net.http 在录制时就已把它们小写，而 HTTP 名字本就大小写不敏感。
        assertThat(replayed.headers()).containsEntry("content-type", List.of("application/json"));
        assertThat(decisions).singleElement().isInstanceOf(MatchResult.Hit.class);
    }

    @Test
    void aChangedStateOrAChangedQuestionMisses() {
        record(200, Map.of("Content-Type", "application/json"), RESPONSE_BODY);
        ReplayJevTransport replay = replaying();

        assertThatThrownBy(() -> replay.send(request(REQUEST_BODY.replace("SUP-4821", "SUP-9999"))))
                .isInstanceOf(ReplayMiss.class)
                .hasMessageContaining("No cassette matches request fingerprint sha256:")
                .hasMessageContaining("state CHANGED, contract MATCH, model MATCH");
        assertThatThrownBy(() -> replay.send(request(REQUEST_BODY.replace("Choose the team.", "Pick the team."))))
                .isInstanceOf(ReplayMiss.class)
                .hasMessageContaining("state MATCH, contract CHANGED, model MATCH");

        assertThat(decisions).hasSize(2)
                .allSatisfy(decision -> assertThat(decision).isInstanceOf(MatchResult.Miss.class));
    }

    @Test
    void aMissCarriesTheDiagnosisOfTheClosestCassette() {
        // 任务 10 的验收：contract 变化的请求产生 MISS，诊断指名 Contract: CHANGED。
        record(200, Map.of("Content-Type", "application/json"), RESPONSE_BODY);

        assertThatThrownBy(() -> replaying().send(request(REQUEST_BODY.replace("Choose the team.", "Pick the team."))))
                .isInstanceOf(ReplayMiss.class);

        assertThat(decisions).singleElement().isInstanceOfSatisfying(MatchResult.Miss.class,
                miss -> {
                    MissDiagnosis diagnosis = miss.diagnosis();
                    assertThat(diagnosis.requestFingerprint()).isEqualTo(miss.requestFingerprint());
                    assertThat(diagnosis.closestCassette()).startsWith("systemone-");
                    assertThat(diagnosis.state()).isEqualTo(MissDiagnosis.Verdict.MATCH);
                    assertThat(diagnosis.contract()).isEqualTo(MissDiagnosis.Verdict.CHANGED);
                    assertThat(diagnosis.model()).isEqualTo(MissDiagnosis.Verdict.MATCH);
                });
    }

    @Test
    void anEmptyTapeHasNothingToCompareAgainst() {
        assertThatThrownBy(() -> new ReplayJevTransport(List.of(), decisions::add).send(request(REQUEST_BODY)))
                .isInstanceOf(ReplayMiss.class)
                .hasMessageContaining("no cassette to compare against");

        assertThat(decisions).singleElement().isInstanceOfSatisfying(MatchResult.Miss.class,
                miss -> assertThat(miss.diagnosis().closestCassette()).isNull());
    }

    @Test
    void jsonKeyOrderDoesNotChangeTheOutcome() {
        record(200, Map.of("Content-Type", "application/json"), RESPONSE_BODY);

        JevResponse replayed = replaying().send(request(SAME_BODY_OTHER_KEY_ORDER));

        assertThat(replayed.body()).isEqualTo(bytes(RESPONSE_BODY));
        assertThat(decisions).singleElement().isInstanceOf(MatchResult.Hit.class);
    }

    @Test
    void replaysRecordedErrorStatusesWithTheirHeaders() {
        record(429, Map.of("Content-Type", "application/json", "Retry-After", "30"),
                "{\"error\":{\"type\":\"rate_limit_exceeded\"}}");

        JevResponse replayed = replaying().send(request(REQUEST_BODY));

        assertThat(replayed.status()).isEqualTo(429);
        assertThat(replayed.headers()).containsEntry("retry-after", List.of("30"));
        assertThat(replayed.body()).isEqualTo(bytes("{\"error\":{\"type\":\"rate_limit_exceeded\"}}"));
    }

    @Test
    void replaysANonJsonBodyAsTheOriginalBytes() {
        record(502, Map.of("Content-Type", "text/html"), "<html>bad gateway</html>");

        assertThat(replaying().send(request(REQUEST_BODY)).body())
                .isEqualTo(bytes("<html>bad gateway</html>"));
    }

    @Test
    void replaysAnEmptyBodyAsEmpty() {
        record(204, Map.of(), "");

        JevResponse replayed = replaying().send(request(REQUEST_BODY));

        assertThat(replayed.status()).isEqualTo(204);
        assertThat(replayed.body()).isEmpty();
    }

    @Test
    void replaysWithoutEverEchoingTheCallerCredentials() {
        record(200, Map.of("Content-Type", "application/json"), RESPONSE_BODY);

        JevResponse replayed = replaying().send(new JevRequest("POST", "/v1/systemone",
                Map.of("Authorization", List.of("Bearer sk-live-9f2c1d4b7a6e5840"),
                        "Cookie", List.of("session=abc123")),
                bytes(REQUEST_BODY)));

        assertThat(replayed.headers().keySet()).containsExactly("content-type");
    }

    @Test
    void rejectsANonJevRequestWithoutConsultingTheTape() {
        record(200, Map.of("Content-Type", "application/json"), RESPONSE_BODY);

        assertThatThrownBy(() -> replaying().send(request("<html>definitely not jev</html>")))
                .isInstanceOf(InvalidJevRequest.class)
                .hasMessageContaining("JSON object");

        assertThat(decisions).isEmpty();
    }

    @Test
    void holdsNoUpstreamClientAtAll() {
        // 不变量 2：离线是架构事实而不是分支行为 —— 这个类既不能持有、也不能被塞进任何指向上游的东西。
        Predicate<Class<?>> reachesTheNetwork = type -> JevTransport.class.isAssignableFrom(type)
                || HttpClient.class.isAssignableFrom(type)
                || URI.class.isAssignableFrom(type)
                || CassetteRepository.class.isAssignableFrom(type);

        assertThat(Arrays.stream(ReplayJevTransport.class.getDeclaredFields()).map(Field::getType))
                .noneMatch(reachesTheNetwork);
        assertThat(Arrays.stream(ReplayJevTransport.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
                .noneMatch(reachesTheNetwork);
    }

    /** 用真实的 record 路径产出一个 cassette，然后把上游关掉 —— 之后的 replay 无处可去。 */
    private void record(int status, Map<String, String> headers, String responseBody) {
        List<Cassette> recorded = new ArrayList<>();
        try (FakeJevServer upstream = new FakeJevServer()) {
            upstream.stub(status, headers, bytes(responseBody));
            new RecordingJevTransport(new LiveJevTransport(upstream.baseUrl(), Duration.ofSeconds(10)),
                    new FileCassetteRepository(cassetteDir), "0.1.0-test", recorded::add)
                    .send(request(REQUEST_BODY));
        }
        assertThat(recorded).hasSize(1);
    }

    /** 每次都从磁盘重新装载，因此 replay 看到的就是 record 真的写下来的那一份。 */
    private ReplayJevTransport replaying() {
        return new ReplayJevTransport(new FileCassetteRepository(cassetteDir).loadAll(), decisions::add);
    }

    private static JevRequest request(String body) {
        return new JevRequest("POST", "/v1/systemone?trace=1",
                Map.of("Content-Type", List.of("application/json")), bytes(body));
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
