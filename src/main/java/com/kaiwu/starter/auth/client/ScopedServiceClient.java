package com.kaiwu.starter.auth.client;

import com.kaiwu.starter.client.ServiceAddress;
import com.kaiwu.starter.client.ServiceAddressResolver;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 显式配置的scope/Bearer内部服务传输；仅后台显式调用，不注册请求拦截器，不回退旧共享令牌。 */
public final class ScopedServiceClient {
    private static final int LIMIT = 32_768;
    private static final Set<String> SCOPES = Set.of("member:read", "todo:managed");
    private final ServiceAddress address;
    private final ServiceAddressResolver resolver;
    private final Credential credential;
    private final RestClient client;

    /** 固定服务编码、声明范围与凭据；实际授权仍由System持久化事实及目标端点固定scope决定。 */
    public record Credential(String serviceCode, Set<String> scopes, String token) {
        /** 拒绝default或缺失凭据，不自动寻找旧配置；复制范围并禁止令牌进入请求头之外的输出。 */
        public Credential {
            if (serviceCode == null
                    || !serviceCode.matches("[a-z][a-z0-9]{1,15}")
                    || "default".equals(serviceCode)
                    || scopes == null
                    || scopes.isEmpty()
                    || scopes.stream().anyMatch(Objects::isNull)
                    || !SCOPES.containsAll(scopes)
                    || token == null
                    || !token.matches("[!-~]{1,4096}"))
                throw new IllegalArgumentException("invalid scoped credential configuration");
            scopes = Set.copyOf(scopes);
        }
        /** 诊断只包含服务编码与范围，绝不展开凭据。 */
        @Override
        public String toString() {
            return "ScopedCredential[serviceCode=" + serviceCode + ",scopes=" + scopes + "]";
        }
    }

    /** 调用方代码固定的内部端点与所需scope；不能来自浏览器输入或模型输出。 */
    public record Endpoint(String path, String scope) {
        /** 仅允许精确内部路径，不接受query、编码路径、父目录或外部地址。 */
        public Endpoint {
            if (path == null
                    || path.length() > 256
                    || !path.matches("/api/internal/[A-Za-z0-9_/-]+")
                    || path.contains("//")
                    || path.endsWith("/")
                    || scope == null
                    || !SCOPES.contains(scope)) throw new IllegalArgumentException("invalid scoped endpoint");
        }
    }

    /** 区分成功、远端拒绝、依赖不可用、正文超限与本地范围拒绝，不把故障当空业务结果。 */
    public enum Outcome {
        SUCCESS,
        REJECTED,
        UNAVAILABLE,
        RESPONSE_LIMIT,
        SCOPE_DENIED
    }

    /** 有界原始成功响应；失败不返回远端正文或异常详情，调用方必须检查outcome。 */
    public record Result(Outcome outcome, int status, byte[] body) {
        /** 冻结响应字节，避免调用方修改传输结果。 */
        public Result {
            body = body.clone();
        }
        /** 提供响应副本；网络错误没有可当成功解析的响应体。 */
        @Override
        public byte[] body() {
            return body.clone();
        }
        /** 不向日志展开可能含业务数据的响应正文。 */
        @Override
        public String toString() {
            return "ScopedResult[outcome=" + outcome + ",status=" + status + "]";
        }
    }

    /** 校验纯服务原点及有界超时，禁止重定向；不自动启用任何scope端点或业务客户端。 */
    public ScopedServiceClient(
            String systemAddress, Credential credential, ServiceAddressResolver resolver, Duration timeout) {
        validateOrigin(systemAddress, true);
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("invalid scoped client timeout");
        this.address = ServiceAddress.parse(systemAddress);
        this.credential = Objects.requireNonNull(credential);
        this.resolver = Objects.requireNonNull(resolver);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** 本地校验范围后仅发送一次Bearer请求；传输故障返回明确失败，不抛回业务、不重试或降级成旧令牌。 */
    public Result exchange(Endpoint endpoint, String method, byte[] payload) {
        Objects.requireNonNull(endpoint);
        if (!credential.scopes().contains(endpoint.scope())) return failure(Outcome.SCOPE_DENIED, 0);
        if (!Set.of("GET", "POST").contains(method) || payload == null || payload.length > LIMIT)
            throw new IllegalArgumentException("invalid scoped request");
        try {
            String origin = resolver.resolve(address);
            validateOrigin(origin, false);
            var request = client.method(HttpMethod.valueOf(method))
                    .uri(URI.create(origin.replaceAll("/+$", "") + endpoint.path()))
                    .headers(headers -> headers.setBearerAuth(credential.token()));
            if (payload.length > 0)
                request.contentType(MediaType.APPLICATION_JSON).body(payload.clone());
            return request.exchange((sent, response) -> {
                int status = response.getStatusCode().value();
                // 必须先close JDK正文流以取消剩余响应，避免外层close排空无限正文。
                try (var body = response.getBody()) {
                    if (status < 200 || status >= 300) return failure(Outcome.REJECTED, status);
                    byte[] bytes = body.readNBytes(LIMIT + 1);
                    return bytes.length > LIMIT
                            ? failure(Outcome.RESPONSE_LIMIT, status)
                            : new Result(Outcome.SUCCESS, status, bytes);
                }
            });
        } catch (RuntimeException failure) {
            return failure(Outcome.UNAVAILABLE, 0);
        }
    }

    /** 创建不含敏感正文的失败回执，禁止凭据和远端错误内容进入日志。 */
    private static Result failure(Outcome outcome, int status) {
        return new Result(outcome, status, new byte[0]);
    }

    /** 解析前可使用lb逻辑原点，解析后只允许纯HTTP(S)原点，拒绝userinfo、路径及query外带凭据。 */
    private static void validateOrigin(String value, boolean allowLb) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid scoped service origin");
        }
        if (!("http".equals(uri.getScheme())
                        || "https".equals(uri.getScheme())
                        || allowLb && "lb".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())))
            throw new IllegalArgumentException("invalid scoped service origin");
    }
}
