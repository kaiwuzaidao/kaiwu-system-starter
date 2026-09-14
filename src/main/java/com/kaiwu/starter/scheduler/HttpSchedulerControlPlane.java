package com.kaiwu.starter.scheduler;

import com.kaiwu.starter.client.ServiceAddress;
import com.kaiwu.starter.client.ServiceAddressResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Starter 到 System 内部调度控制面的 HTTP 客户端。
 */
public final class HttpSchedulerControlPlane implements SchedulerControlPlane {

    private static final ParameterizedTypeReference<ApiResponse<List<ProjectSchedulerJob>>> JOB_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final ServiceAddress systemAddress;
    private final ServiceAddressResolver addressResolver;

    /** 便捷构造：默认超时 + 直连寻址。需要 {@code lb://} 时请使用完整构造并传入解析器。 */
    public HttpSchedulerControlPlane(String systemBaseUrl, String credential) {
        this(systemBaseUrl, credential, Duration.ofSeconds(3), Duration.ofSeconds(10), ServiceAddressResolver.direct());
    }

    /** 便捷构造：直连寻址。需要 {@code lb://} 时请使用带解析器的重载。 */
    public HttpSchedulerControlPlane(
            String systemBaseUrl, String credential, Duration connectTimeout, Duration readTimeout) {
        this(systemBaseUrl, credential, connectTimeout, readTimeout, ServiceAddressResolver.direct());
    }

    public HttpSchedulerControlPlane(
            String systemBaseUrl,
            String credential,
            Duration connectTimeout,
            Duration readTimeout,
            ServiceAddressResolver addressResolver) {
        if (systemBaseUrl == null || systemBaseUrl.isBlank()) {
            throw new IllegalArgumentException("System 地址不能为空");
        }
        if (credential == null || credential.isBlank()) {
            throw new IllegalArgumentException("项目调度凭据不能为空");
        }
        requirePositive(connectTimeout, "连接超时");
        requirePositive(readTimeout, "读取超时");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.addressResolver = addressResolver;
        this.systemAddress = ServiceAddress.parse(systemBaseUrl);
        // 不设 baseUrl：lb:// 必须每次请求重新选实例，构造时固定下来会让负载均衡失效。
        this.client = RestClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + credential.trim())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 本次请求要用的 System 地址。{@code lb://} 每次重新选实例：
     * 缓存下来等于把负载均衡退化成固定实例，实例下线后会一直打到死节点。
     */
    private String systemUri() {
        return addressResolver.resolve(systemAddress);
    }

    @Override
    public List<ProjectSchedulerJob> synchronize(
            String projectId, String instanceId, List<ProjectSchedulerHandlerRegistration> handlers) {
        ApiResponse<List<ProjectSchedulerJob>> response = client.post()
                .uri(systemUri() + "/api/internal/scheduler/sync")
                .body(new SyncRequest(instanceId, handlers))
                .retrieve()
                .body(JOB_LIST_TYPE);
        return requireData(response);
    }

    @Override
    public SchedulerClaim claim(
            String projectId, String instanceId, String jobId, long configVersion, Instant scheduledAt) {
        ApiResponse<SchedulerClaim> response = client.post()
                .uri(systemUri() + "/api/internal/scheduler/claim")
                .body(new ClaimRequest(jobId, configVersion, scheduledAt, instanceId))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return requireData(response);
    }

    @Override
    public boolean renew(String projectId, String instanceId, String executionId) {
        ApiResponse<Boolean> response = client.post()
                .uri(systemUri() + "/api/internal/scheduler/executions/{id}/renew", executionId)
                .body(new LeaseRequest(instanceId))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return Boolean.TRUE.equals(requireData(response));
    }

    @Override
    public void complete(String projectId, String instanceId, String executionId, String status, String message) {
        ApiResponse<Void> response = client.post()
                .uri(systemUri() + "/api/internal/scheduler/executions/{id}/complete", executionId)
                .body(new CompletionRequest(instanceId, status, message))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        requireSuccess(response);
    }

    private static <T> T requireData(ApiResponse<T> response) {
        requireSuccess(response);
        return response.data();
    }

    private static void requireSuccess(ApiResponse<?> response) {
        if (response == null) {
            throw new SchedulerRemoteException("System 调度接口没有响应");
        }
        if (response.code() != 0) {
            throw new SchedulerRemoteException(response.message() == null ? "System 调度接口失败" : response.message());
        }
    }

    private static void requirePositive(Duration duration, String label) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
    }

    private record ApiResponse<T>(int code, String message, T data) {}

    private record SyncRequest(String instanceId, List<ProjectSchedulerHandlerRegistration> handlers) {}

    private record ClaimRequest(String jobId, long configVersion, Instant scheduledAt, String instanceId) {}

    private record CompletionRequest(String instanceId, String status, String message) {}

    private record LeaseRequest(String instanceId) {}
}
