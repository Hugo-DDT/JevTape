package io.jevtape.transport;

import com.fasterxml.jackson.databind.node.TextNode;
import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.CassetteMetadata;
import io.jevtape.cassette.Fingerprints;
import io.jevtape.cassette.Interaction;
import io.jevtape.cassette.RecordedRequest;
import io.jevtape.cassette.RecordedResponse;
import io.jevtape.contract.JevProtocolAdapter;
import io.jevtape.fingerprint.FingerprintEngine;
import io.jevtape.shared.InvalidJevRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * miss 策略 live / record 的公共那一半：命中时上游一个字节都收不到，MISS 时才转发。
 *
 * <p>"默认策略下没有任何上游"不在这个类里被测 —— 那是装配的事实：{@code jevtape replay} 在默认策略下
 * 根本不构造本类（见 ReplayCommandTest）。
 */
class MissFallbackJevTransportTest {

    private static final String RECORDED_BODY = """
            {"model":"jev-latest","state":{"ticket":{"id":"SUP-4821"}},\
            "questions":{"route":{"type":"Choice","instructions":"Choose the team.",\
            "options":[{"value":"billing","criteria":"Invoices."}]}}}""";

    private final AtomicInteger upstreamCalls = new AtomicInteger();
    private final JevTransport upstream = request -> {
        upstreamCalls.incrementAndGet();
        return new JevResponse(200, Map.of(), bytes("from-upstream"));
    };

    @Test
    void aHitIsAnsweredByTheTapeAndNeverReachesTheUpstream() {
        MissFallbackJevTransport transport = new MissFallbackJevTransport(replay(), upstream);

        JevResponse response = transport.send(request(RECORDED_BODY));

        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("from-tape");
        assertThat(upstreamCalls).hasValue(0);
    }

    @Test
    void aMissIsForwardedToTheUpstream() {
        MissFallbackJevTransport transport = new MissFallbackJevTransport(replay(), upstream);

        JevResponse response = transport.send(request(RECORDED_BODY.replace("Choose the team.", "Pick the team.")));

        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("from-upstream");
        assertThat(upstreamCalls).hasValue(1);
    }

    @Test
    void aFailureThatIsNotAMissIsNeverForwarded() {
        // 认不出来的 Jev 请求不是"磁带里没有答案"，拿它去请求线上只会把本地问题伪装成一次真实调用。
        MissFallbackJevTransport transport = new MissFallbackJevTransport(replay(), upstream);

        assertThatThrownBy(() -> transport.send(request("<html>definitely not jev</html>")))
                .isInstanceOf(InvalidJevRequest.class);
        assertThat(upstreamCalls).hasValue(0);
    }

    /** 走真实的指纹与匹配路径装出一盘磁带，于是这里测的是装饰器而不是替身。 */
    private static ReplayJevTransport replay() {
        JevProtocolAdapter.Decision decision = JevProtocolAdapter.parseRequest(bytes(RECORDED_BODY));
        Fingerprints fingerprints = FingerprintEngine.of("POST", "/v1/systemone", decision);
        Cassette cassette = Cassette.of("issue-routing", new Interaction(
                new CassetteMetadata("2026-09-21T10:20:30Z", "0.1.0-test", 132),
                new RecordedRequest("POST", "/v1/systemone", decision.requestedModel(), "jev-1.13.0",
                        decision.state(), decision.questions()),
                new RecordedResponse(200, Map.of(), TextNode.valueOf("from-tape")),
                fingerprints));
        return new ReplayJevTransport(List.of(cassette), result -> {
        });
    }

    private static JevRequest request(String body) {
        return new JevRequest("POST", "/v1/systemone", Map.of(), bytes(body));
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
