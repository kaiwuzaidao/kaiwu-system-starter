package com.kaiwu.starter.config;

import com.kaiwu.starter.client.ServiceAddress;
import com.kaiwu.starter.client.ServiceAddressResolver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 读取本项目在 Kaiwu 平台上的生效配置（global 作底、项目级覆盖）。
 *
 * <p>使用约定：
 * <ul>
 *   <li>限额、阈值、开关等<b>参与校验的值必须由后端读取</b>——交给前端读再传回来等于没有
 *       校验，客户端可篡改。纯展示型参数才适合前端直接读。</li>
 *   <li>读取走内存快照，<b>请求路径上不发网络请求</b>；后台按 refreshSeconds 定期刷新，
 *       因此配置变更存在最长一个刷新周期的延迟。需要立即生效的开关不要依赖它。</li>
 *   <li>平台不可达或未配置时返回调用方给的 fallback，<b>绝不抛异常、绝不阻断业务</b>。
 *       因此每次读取都必须给出有意义的兜底值。</li>
 *   <li>secret 配置不会下发，密钥仍走环境变量。</li>
 * </ul>
 *
 * <pre>{@code
 * int batchSize = projectConfigClient.getInt("order.settle.batchSize", 200);
 * if (!projectConfigClient.getBoolean("order.autoSettle.enabled", false)) {
 *     return;
 * }
 * }</pre>
 */
public class ProjectConfigClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProjectConfigClient.class);
    private static final ParameterizedTypeReference<ApiResult> API_RESULT = new ParameterizedTypeReference<>() {};

    private final boolean enabled;
    private final String credential;
    private final RestClient client;
    private final long refreshSeconds;
    private final AtomicReference<Map<String, String>> snapshot = new AtomicReference<>(Map.of());
    private final ServiceAddress systemAddress;
    private final ServiceAddressResolver addressResolver;
    private ScheduledThreadPoolExecutor refresher;
    private volatile boolean closed;

    /** 便捷构造：直连寻址。需要 {@code lb://} 时请传入解析器。 */
    public ProjectConfigClient(ProjectConfigProperties properties) {
        this(properties, ServiceAddressResolver.direct());
    }

    public ProjectConfigClient(ProjectConfigProperties properties, ServiceAddressResolver addressResolver) {
        this.credential = properties.getCredential();
        this.refreshSeconds = Math.max(10, properties.getRefreshSeconds());
        String baseUrl = properties.getSystemBaseUrl();
        this.addressResolver = addressResolver;
        this.enabled = properties.isEnabled() && hasText(baseUrl) && hasText(this.credential);
        if (!enabled) {
            this.client = null;
            this.systemAddress = null;
            log.info("项目配置未接入（缺少 systemBaseUrl 或凭据），读取一律返回 fallback");
            return;
        }
        // 地址非法直接抛：项目配置是业务读取的数据源，静默降级成空快照会让业务拿到错误默认值。
        this.systemAddress = ServiceAddress.parse(baseUrl);
        long timeout = Math.max(1, properties.getTimeoutSeconds());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeout));
        factory.setReadTimeout(Duration.ofSeconds(timeout));
        // 不设 baseUrl：lb:// 必须每次请求重新选实例，构造时固定下来会让负载均衡失效。
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** 启动时拉一次并开启后台刷新；拉取失败不影响启动，快照保持为空。 */
    public synchronized void start() {
        if (!enabled || closed || refresher != null) {
            return;
        }
        refresh();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("kaiwu-project-config-refresh-", 0)
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        // 返回的 ScheduledFuture 刻意丢弃：取消走 close() 里的 shutdownNow()。
        // refresh() 已用 Throwable 兜底，不会异常逃逸导致调度被永久取消。
        var unused = executor.scheduleWithFixedDelay(this::refresh, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
        refresher = executor;
    }

    /** 立即从平台拉取一次。失败时保留上一次快照，不清空——旧值比无值可用。 */
    public void refresh() {
        if (!enabled) {
            return;
        }
        try {
            List<ConfigEntry> entries = client.get()
                    .uri(addressResolver.resolve(systemAddress) + "/api/internal/configs")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                    .retrieve()
                    .body(API_RESULT)
                    .data();
            if (entries == null) {
                return;
            }
            snapshot.set(entries.stream()
                    .filter(entry -> entry.key() != null)
                    .collect(Collectors.toUnmodifiableMap(
                            ConfigEntry::key,
                            entry -> entry.value() == null ? "" : entry.value(),
                            (left, right) -> right)));
        } catch (Exception exception) {
            log.warn("拉取项目配置失败，沿用上一次快照：{}", exception.toString());
        } catch (Throwable throwable) {
            // 兜住 Error：周期刷新的任务体一旦抛出去，调度就被永久取消且无人知晓，
            // 配置会永远停在最后一次快照。记一条 error 继续排期，不让它静默停摆。
            log.error("拉取项目配置时出现严重错误，沿用上一次快照", throwable);
        }
    }

    /** 当前快照，键为配置 key。仅用于诊断，业务读取请用 get* 方法以获得 fallback 语义。 */
    public Map<String, String> snapshot() {
        return snapshot.get();
    }

    public String getString(String key, String fallback) {
        String value = snapshot.get().get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    public int getInt(String key, int fallback) {
        String value = snapshot.get().get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            // 只报 key 和长度：配置项可能承载凭据类值，格式不合法不代表可以打印出来。
            log.warn("配置 {} 不是合法整数，使用 fallback：valueLength={}", key, value.length());
            return fallback;
        }
    }

    public long getLong(String key, long fallback) {
        String value = snapshot.get().get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            // 同上：诊断需要的是「哪个 key 配错了」，不是它的值。
            log.warn("配置 {} 不是合法长整数，使用 fallback：valueLength={}", key, value.length());
            return fallback;
        }
    }

    /** 只有字符串 "true"（忽略大小写）视为真，避免把任意非空值当成开启。 */
    public boolean getBoolean(String key, boolean fallback) {
        String value = snapshot.get().get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim());
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (refresher != null) {
            refresher.shutdownNow();
            refresher = null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 平台统一响应外壳。 */
    record ApiResult(int code, String message, List<ConfigEntry> data) {}

    record ConfigEntry(String key, String value, String valueType, String description) {}
}
