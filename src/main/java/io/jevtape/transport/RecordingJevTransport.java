package io.jevtape.transport;

import io.jevtape.cassette.Cassette;
import io.jevtape.cassette.CassetteMetadata;
import io.jevtape.cassette.CassetteRepository;
import io.jevtape.cassette.Fingerprints;
import io.jevtape.cassette.Interaction;
import io.jevtape.cassette.RecordedRequest;
import io.jevtape.cassette.RecordedResponse;
import io.jevtape.contract.JevProtocolAdapter;
import io.jevtape.fingerprint.FingerprintEngine;
import io.jevtape.redaction.HeaderRedactor;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * record 模式：委托实时 transport 转发，并把这一次调用落盘成 cassette（charter §15, §42）。
 *
 * <p>顺序本身就是安全设计（charter §52），固定为：
 *
 * <pre>
 * 原始请求 ──┬── 原样转发给上游（凭证照旧带上）
 *            ├── 计算 fingerprint（源 = 原始请求）
 *            └── 脱敏之后写入 cassette
 * </pre>
 *
 * <p>回给客户端的永远是上游那份原件，逐字节不变 —— 脱敏只作用于存储，绝不作用于转发或回写。上游失败时
 * 不落盘：没有响应就没有可录的 interaction，异常原样交给调用方。
 */
public final class RecordingJevTransport implements JevTransport {

    private final JevTransport delegate;
    private final CassetteRepository repository;
    private final String jevtapeVersion;
    private final Consumer<Cassette> onRecorded;

    /**
     * @param delegate    实时转发的那一层，通常是 {@link LiveJevTransport}
     * @param onRecorded  每落盘一个 cassette 就回调一次，CLI 用它渲染 REC 输出
     */
    public RecordingJevTransport(JevTransport delegate, CassetteRepository repository,
                                 String jevtapeVersion, Consumer<Cassette> onRecorded) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.jevtapeVersion = Objects.requireNonNull(jevtapeVersion, "jevtapeVersion");
        this.onRecorded = Objects.requireNonNull(onRecorded, "onRecorded");
    }

    @Override
    public JevResponse send(JevRequest request) {
        // 先识别 System One 请求：认不出来的请求根本不该打到上游（charter §15）。
        JevProtocolAdapter.Decision decision = JevProtocolAdapter.parseRequest(request.body());

        long startedAt = System.nanoTime();
        JevResponse response = delegate.send(request);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        Fingerprints fingerprints = FingerprintEngine.of(request.method(), request.path(), decision);
        JevProtocolAdapter.Response upstream = JevProtocolAdapter.parseResponse(response.body());

        Cassette cassette = Cassette.of(cassetteName(request.path(), fingerprints.request()), new Interaction(
                new CassetteMetadata(recordedAt(), jevtapeVersion, durationMs),
                new RecordedRequest(request.method(), request.path(), decision.requestedModel(),
                        upstream.resolvedModel(), decision.state(), decision.questions()),
                new RecordedResponse(response.status(), HeaderRedactor.strip(response.headers()), upstream.body()),
                fingerprints));

        repository.write(cassette);
        onRecorded.accept(cassette);
        return response;
    }

    /**
     * 内容寻址命名：{@code <path 末段>-<request 指纹前 8 位>}，例如 {@code systemone-9f2c1d4b}。同一个请求
     * 跨会话得到同一个名字，因此重复录制是幂等覆盖，而不是在 cassetteDir 里堆出新文件。
     */
    private static String cassetteName(String path, String requestFingerprint) {
        int query = path.indexOf('?');
        String target = query < 0 ? path : path.substring(0, query);
        String segment = target.substring(target.lastIndexOf('/') + 1);
        String label = segment.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[^A-Za-z0-9]+", "");
        if (label.length() > 32) {
            label = label.substring(0, 32);
        }
        String hash = requestFingerprint.substring(requestFingerprint.indexOf(':') + 1);
        return (label.isEmpty() ? "cassette" : label) + "-" + hash.substring(0, 8);
    }

    /** Cassette 格式承诺的是精确到秒的 ISO-8601 UTC 文本。 */
    private static String recordedAt() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
