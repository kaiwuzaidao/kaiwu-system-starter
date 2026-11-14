package com.kaiwu.starter.todo.client;

import com.kaiwu.starter.client.ServiceAddress;
import com.kaiwu.starter.client.ServiceAddressResolver;
import com.kaiwu.starter.todo.client.ManagedTodoResult.Disposition;
import com.kaiwu.starter.todo.client.ManagedTodoResult.Receipt;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 显式配置 C5 凭据的受管投递；只在业务事务外调用，不自动重试、不回退旧 token，调用不抛异常。 */
public final class ManagedTodoClient {
    private static final int MAX_RESPONSE_BYTES = 16_384;
    private static final Set<String> STATES = Set.of("OPEN", "DONE", "CANCELLED");
    private static final JsonMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final String projectCode;
    private final String credential;
    private final ServiceAddressResolver resolver;
    private final ServiceAddress address;
    private final RestClient client;
    private final Disposition configurationFailure;

    /** 仅显式参数参与受管身份；缺配置返回 NOT_CONFIGURED，配置错误不降级旧客户端。 */
    public ManagedTodoClient(
            String systemAddress,
            String projectCode,
            String credential,
            ServiceAddressResolver resolver,
            Duration timeout) {
        this.projectCode = projectCode;
        this.credential = credential;
        this.resolver = resolver;
        ServiceAddress parsed = null;
        RestClient configured = null;
        Disposition failure = null;
        if (blank(systemAddress) || blank(projectCode) || blank(credential)) {
            failure = Disposition.NOT_CONFIGURED;
        } else {
            try {
                if (!projectCode.matches("[a-z][a-z0-9-]{1,63}")
                        || !credential.matches("[!-~]{1,4096}")
                        || resolver == null
                        || timeout == null
                        || timeout.isNegative()
                        || timeout.isZero()
                        || timeout.compareTo(Duration.ofMinutes(1)) > 0)
                    throw new IllegalArgumentException("invalid configuration");
                validateAddress(systemAddress, true);
                parsed = ServiceAddress.parse(systemAddress);
                var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .build());
                factory.setReadTimeout(timeout);
                configured = RestClient.builder().requestFactory(factory).build();
            } catch (RuntimeException exception) {
                failure = Disposition.PERMANENT_FAILURE;
            }
        }
        this.address = parsed;
        this.client = configured;
        this.configurationFailure = failure;
    }

    /** 发出一次不可变目标；响应版本或状态不匹配时不确认，交给调用方有限重试或对账。 */
    public ManagedTodoResult putManaged(ManagedTodoOperation operation) {
        if (configurationFailure != null) return failure(configurationFailure, 0);
        try {
            if (operation == null
                    || !projectCode.equals(operation.projectCode())
                    || operation.sequence() <= 0
                    || !validKey(operation.bizKey())
                    || !validId(operation.recipientUserId())
                    || operation.state() == null
                    || !STATES.contains(operation.state())
                    || (operation.terminal() && operation.state().equals("OPEN"))
                    || operation.operationCreatedAt() == null) return failure(Disposition.PERMANENT_FAILURE, 0);
            URI endpoint = endpoint("/api/internal/managed-todos");
            byte[] body = JSON.writeValueAsBytes(operation);
            return client.method(HttpMethod.PUT)
                    .uri(endpoint)
                    .headers(h -> h.setBearerAuth(credential))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> decode(response, operation));
        } catch (ResourceAccessException exception) {
            return failure(Disposition.RETRYABLE_FAILURE, 0);
        } catch (RuntimeException exception) {
            return failure(Disposition.RETRYABLE_FAILURE, 0);
        }
    }

    /** 读取本服务同项目的投影回执；STORED 不等于确认任意本地版本。 */
    public ManagedTodoResult getManagedReceipt(String bizKey, String recipientUserId) {
        if (configurationFailure != null) return failure(configurationFailure, 0);
        try {
            if (!validKey(bizKey) || !validId(recipientUserId)) return failure(Disposition.PERMANENT_FAILURE, 0);
            URI endpoint = org.springframework.web.util.UriComponentsBuilder.fromUri(
                            endpoint("/api/internal/managed-todos/receipt"))
                    .queryParam("bizKey", "{key}")
                    .queryParam("recipientUserId", "{recipient}")
                    .encode()
                    .buildAndExpand(bizKey, recipientUserId)
                    .toUri();
            return client.get()
                    .uri(endpoint)
                    .headers(h -> h.setBearerAuth(credential))
                    .exchange((request, response) -> decode(response, null));
        } catch (RuntimeException exception) {
            return failure(Disposition.RETRYABLE_FAILURE, 0);
        }
    }

    /** 先分类拒绝状态，再有界读取成功正文；主动取消错误或超大正文，避免框架关闭排空未结束的流。 */
    private static ManagedTodoResult decode(
            org.springframework.http.client.ClientHttpResponse response, ManagedTodoOperation operation)
            throws java.io.IOException {
        int status = response.getStatusCode().value();
        try (var body = response.getBody()) {
            return decode(
                    status,
                    status < 200 || status >= 300 ? new byte[0] : body.readNBytes(MAX_RESPONSE_BYTES + 1),
                    operation);
        }
    }

    /** 限制响应大小与字段类型；格式错误从不冒充成功，摘要由业务发送端与持久事实比对。 */
    private static ManagedTodoResult decode(int status, byte[] body, ManagedTodoOperation operation) {
        if (status < 200 || status >= 300)
            return failure(
                    status == 408 || status == 425 || status == 429 || status >= 500
                            ? Disposition.RETRYABLE_FAILURE
                            : Disposition.PERMANENT_FAILURE,
                    status);
        if (body.length > MAX_RESPONSE_BYTES) return failure(Disposition.RETRYABLE_FAILURE, status);
        try {
            JsonNode envelope = JSON.readTree(body);
            JsonNode code = envelope.path("code"), data = envelope.path("data");
            if (!code.isIntegralNumber() || !code.canConvertToInt() || code.intValue() != 0 || !data.isObject())
                throw new IllegalArgumentException();
            JsonNode seq = data.path("storedSequence"),
                    state = data.path("storedState"),
                    terminal = data.path("storedTerminal"),
                    digest = data.path("payloadDigest"),
                    disposition = data.path("disposition");
            if (!seq.isIntegralNumber()
                    || !seq.canConvertToLong()
                    || seq.longValue() <= 0
                    || !state.isString()
                    || !STATES.contains(state.stringValue())
                    || !terminal.isBoolean()
                    || (terminal.booleanValue() && state.stringValue().equals("OPEN"))
                    || !digest.isString()
                    || !digest.stringValue().matches("[0-9a-f]{64}")
                    || !disposition.isString()) throw new IllegalArgumentException();
            var kind = Disposition.valueOf(disposition.stringValue());
            if (operation == null) {
                if (kind != Disposition.STORED) throw new IllegalArgumentException();
            } else if (kind == Disposition.APPLIED || kind == Disposition.DUPLICATE) {
                boolean staleDuplicate = kind == Disposition.DUPLICATE
                        && operation.state().equals("OPEN")
                        && state.stringValue().equals("CANCELLED")
                        && !terminal.booleanValue();
                if (seq.longValue() != operation.sequence()
                        || (!staleDuplicate && !state.stringValue().equals(operation.state()))
                        || terminal.booleanValue() != operation.terminal()) throw new IllegalArgumentException();
            } else if (kind != Disposition.SUPERSEDED || seq.longValue() <= operation.sequence()) {
                throw new IllegalArgumentException();
            }
            return new ManagedTodoResult(
                    kind,
                    new Receipt(seq.longValue(), state.stringValue(), terminal.booleanValue(), digest.stringValue()),
                    status);
        } catch (RuntimeException exception) {
            return failure(Disposition.RETRYABLE_FAILURE, status);
        }
    }

    /** 每次请求重新解析实例；禁止把凭据发送到带用户信息、查询或路径的目标地址。 */
    private URI endpoint(String path) {
        String resolved = resolver.resolve(address);
        validateAddress(resolved, false);
        return URI.create(resolved.replaceAll("/+$", "") + path);
    }

    /** 拒绝地址中的隐式授权信息和不受控重定向入口；允许声明式 lb 寻址。 */
    private static void validateAddress(String value, boolean allowLb) {
        URI uri = URI.create(value);
        if (uri.getScheme() == null
                || !(uri.getScheme().equals("https")
                        || uri.getScheme().equals("http")
                        || (allowLb && uri.getScheme().equals("lb")))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getRawPath() != null
                        && !uri.getRawPath().isEmpty()
                        && !uri.getRawPath().equals("/")))
            throw new IllegalArgumentException("invalid service address");
    }

    /** 返回稳定失败分类，不泄漏网络异常、内部地址或令牌。 */
    private static ManagedTodoResult failure(Disposition disposition, int status) {
        return new ManagedTodoResult(disposition, null, status);
    }

    /** 未配置字段只用来确定是否具备独立受管身份。 */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 与服务端一致的业务键字符和长度边界。 */
    private static boolean validKey(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9:_./#-]{0,127}");
    }

    /** 用户 ID 为无前导零的正数 BIGINT，不以浮点方式处理。 */
    private static boolean validId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
