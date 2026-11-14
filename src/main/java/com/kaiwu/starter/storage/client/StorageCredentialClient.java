package com.kaiwu.starter.storage.client;

import com.kaiwu.starter.client.ServiceAddress;
import com.kaiwu.starter.client.ServiceAddressResolver;
import com.kaiwu.starter.storage.model.StorageCredential;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 向 System 申请并缓存本项目的对象存储临时凭证（ADR 0039 第 6 节）。
 *
 * <p>使用约定：
 * <ul>
 *   <li>凭证获取<b>不在业务请求路径上</b>：启动时预取一次，之后由后台线程按剩余有效期续签。
 *       业务请求只读内存快照，快照里没有可用凭证时直接失败，不在请求内同步调用 System。</li>
 *   <li>System 不可达时继续使用未过期的旧凭证；过期后存储操作失败，但不阻断应用启动。</li>
 *   <li>前缀必须等于 {@code {projectCode}/}：System 返回的前缀与本项目编码不一致时整批丢弃，
 *       避免凭据配置错项目后把文件写进别的项目目录。</li>
 * </ul>
 */
public class StorageCredentialClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StorageCredentialClient.class);
    private static final ParameterizedTypeReference<ApiResult> API_RESULT = new ParameterizedTypeReference<>() {};
    /** 剩余有效期低于总时长的这个比例时续签。 */
    private static final double RENEW_RATIO = 0.2;
    /** 不论总时长多少，剩余不足 2 分钟一律续签，给网络抖动留出余量。 */
    private static final Duration RENEW_FLOOR = Duration.ofMinutes(2);

    private final ServiceAddress systemAddress;
    private final ServiceAddressResolver addressResolver;
    private final String serviceToken;
    private final String expectedPrefix;
    private final long checkIntervalMillis;
    private final Clock clock;
    private final RestClient client;
    private final AtomicReference<Map<String, Entry>> snapshot = new AtomicReference<>(Map.of());
    private ScheduledThreadPoolExecutor refresher;
    private volatile boolean closed;

    /**
     * 创建客户端。地址或凭据缺失属于配置错误，构造时即失败。
     *
     * @param systemBaseUrl System 地址，支持 {@code lb://}
     * @param serviceToken 项目服务凭据，与调度、项目配置使用同一个值
     * @param projectCode 本项目编码，用于校验 System 返回的前缀
     * @param checkInterval 后台检查是否需要续签的间隔
     * @param timeout 单次请求超时
     * @param addressResolver 服务地址解析器
     * @param clock 时间来源
     * @throws IllegalStateException 地址或凭据缺失时抛出
     */
    public StorageCredentialClient(
            String systemBaseUrl,
            String serviceToken,
            String projectCode,
            Duration checkInterval,
            Duration timeout,
            ServiceAddressResolver addressResolver,
            Clock clock) {
        if (systemBaseUrl == null || systemBaseUrl.isBlank() || serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalStateException(
                    "provider=kaiwu 需要配置 kaiwu.starter.storage.kaiwu.system-base-url 与 credential");
        }
        this.systemAddress = ServiceAddress.parse(systemBaseUrl);
        this.addressResolver = addressResolver;
        this.serviceToken = serviceToken;
        this.expectedPrefix = projectCode + "/";
        this.checkIntervalMillis = Math.max(1_000, checkInterval.toMillis());
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** 启动时预取一次并开启后台续签；预取失败不影响启动。 */
    public synchronized void start() {
        if (closed || refresher != null) {
            return;
        }
        refresh();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("kaiwu-storage-credential-", 0)
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        // 取消走 close() 里的 shutdownNow()；renewIfNeeded 已兜住 Throwable，调度不会被异常取消。
        var unused = executor.scheduleWithFixedDelay(
                this::renewIfNeeded, checkIntervalMillis, checkIntervalMillis, TimeUnit.MILLISECONDS);
        refresher = executor;
    }

    /** 快照为空或任一凭证接近过期时续签。由后台线程调用。 */
    void renewIfNeeded() {
        try {
            Instant now = clock.instant();
            Map<String, Entry> current = snapshot.get();
            if (current.isEmpty() || current.values().stream().anyMatch(entry -> entry.dueForRenewal(now))) {
                refresh();
            }
        } catch (Throwable throwable) {
            // 兜住 Error：周期任务一旦抛出，调度会被永久取消，凭证过期后所有存储操作都会失败。
            log.error("对象存储凭证续签出现严重错误，下个周期重试", throwable);
        }
    }

    /** 立即向 System 申请一次。失败时保留旧快照，未过期的旧凭证仍可使用。 */
    public void refresh() {
        try {
            ApiResult result = client.post()
                    .uri(addressResolver.resolve(systemAddress) + "/api/internal/storage/credentials")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .retrieve()
                    .body(API_RESULT);
            Bundle bundle = result == null ? null : result.data();
            if (bundle == null || bundle.credentials() == null) {
                log.warn("申请对象存储凭证返回空结果，沿用上一次快照");
                return;
            }
            Instant fetchedAt = clock.instant();
            Map<String, Entry> next = new LinkedHashMap<>();
            for (StorageCredential issued : bundle.credentials()) {
                if (!expectedPrefix.equals(issued.prefix())) {
                    log.error(
                            "对象存储凭证前缀与本项目不一致，整批丢弃：expectedPrefix={} actualPrefix={}", expectedPrefix, issued.prefix());
                    return;
                }
                next.put(issued.storageRef(), new Entry(issued, fetchedAt));
            }
            snapshot.set(Map.copyOf(next));
            if (bundle.unavailable() != null && !bundle.unavailable().isEmpty()) {
                log.warn("以下存储绑定本次未能签发，原因见 System 日志：{}", bundle.unavailable());
            }
        } catch (Exception exception) {
            log.warn("申请对象存储凭证失败，沿用上一次快照：{}", exception.toString());
        }
    }

    /**
     * 按存储引用取未过期的凭证。
     *
     * @param storageRef 存储引用
     * @return 凭证；未知或已过期时为空
     */
    public Optional<StorageCredential> byRef(String storageRef) {
        Entry entry = snapshot.get().get(storageRef);
        return entry == null || entry.expired(clock.instant()) ? Optional.empty() : Optional.of(entry.value());
    }

    /**
     * 取某个存储名当前生效（可写）的未过期凭证。
     *
     * @param storageName 项目内存储名
     * @return 凭证；没有生效绑定或已过期时为空
     */
    public Optional<StorageCredential> active(String storageName) {
        Instant now = clock.instant();
        return snapshot.get().values().stream()
                .filter(entry -> !entry.expired(now))
                .map(Entry::value)
                .filter(value -> value.storageName().equals(storageName) && !value.readOnly())
                .findFirst();
    }

    /** 停止后台续签。 */
    @Override
    public synchronized void close() {
        closed = true;
        if (refresher != null) {
            refresher.shutdownNow();
            refresher = null;
        }
    }

    /**
     * 快照条目：凭证与取得时间，用于按总时长计算续签时机。
     *
     * @param value 凭证
     * @param fetchedAt 取得时间
     */
    private record Entry(StorageCredential value, Instant fetchedAt) {

        /**
         * 判断凭证是否已过期。
         *
         * @param now 当前时间
         * @return 已过期时返回 {@code true}
         */
        boolean expired(Instant now) {
            return !now.isBefore(value.expiresAt());
        }

        /**
         * 判断是否到了续签时机：剩余不足总时长的 20%，或不足 2 分钟。
         *
         * @param now 当前时间
         * @return 需要续签时返回 {@code true}
         */
        boolean dueForRenewal(Instant now) {
            Duration total = Duration.between(fetchedAt, value.expiresAt());
            Duration remaining = Duration.between(now, value.expiresAt());
            Duration threshold = Duration.ofMillis((long) (total.toMillis() * RENEW_RATIO));
            return remaining.compareTo(threshold.compareTo(RENEW_FLOOR) > 0 ? threshold : RENEW_FLOOR) <= 0;
        }
    }

    /**
     * 平台统一响应外壳。
     *
     * @param code 业务码
     * @param message 消息
     * @param data 凭证集合
     */
    record ApiResult(int code, String message, Bundle data) {}

    /**
     * 一次签发的结果。
     *
     * @param projectCode 项目编码
     * @param credentials 已签发的凭证
     * @param unavailable 未能签发的存储名
     */
    record Bundle(String projectCode, List<StorageCredential> credentials, List<String> unavailable) {}
}
