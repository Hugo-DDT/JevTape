package io.jevtape.redaction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 敏感 header 的脱敏（charter §47, §50）。
 *
 * <p>两种形态，用途不同：
 *
 * <ul>
 *   <li>{@link #strip} 整条剥离 —— cassette 里连 header 名字都不留；</li>
 *   <li>{@link #masked} 保留名字、把值换成 {@value #REDACTED} —— 面向 console 与日志，让人看得出
 *       "这里有个凭证被脱敏了"而读不到内容。</li>
 * </ul>
 *
 * <p>判定只看 header 名字，并且**失败关闭**：charter §50 点名的五个之外，任何名字里带 key / token /
 * secret / password / credential / authorization / cookie 的 header 一并视为敏感。漏存一个凭证的代价
 * 远高于多脱敏一个无害 header。
 *
 * <p>脱敏只作用于存储与显示，**绝不**作用于发往上游的请求 —— 原样转发是 {@code LiveJevTransport} 的职责。
 */
public final class HeaderRedactor {

    /** Console 与日志里替代凭证的值（charter §50）。 */
    public static final String REDACTED = "[REDACTED]";

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(api[_-]?key|token|secret|passw(or)?d|credential|authorization|cookie).*");

    private HeaderRedactor() {
    }

    /** Header 名字大小写不敏感。 */
    public static boolean isSensitive(String name) {
        return name != null && SENSITIVE.matcher(name).matches();
    }

    /** 移除全部敏感 header；其余原样保留。 */
    public static Map<String, List<String>> strip(Map<String, List<String>> headers) {
        Map<String, List<String>> kept = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (!isSensitive(name)) {
                kept.put(name, values);
            }
        });
        return kept;
    }

    /** 保留全部 header 名字，敏感的值统一替换成一条 {@value #REDACTED}。 */
    public static Map<String, List<String>> masked(Map<String, List<String>> headers) {
        Map<String, List<String>> view = new LinkedHashMap<>();
        headers.forEach((name, values) -> view.put(name, isSensitive(name) ? List.of(REDACTED) : values));
        return view;
    }
}
