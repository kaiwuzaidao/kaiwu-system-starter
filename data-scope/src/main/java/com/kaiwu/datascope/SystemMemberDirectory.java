package com.kaiwu.datascope;

import com.kaiwu.starter.client.ServiceAddress;
import com.kaiwu.starter.client.ServiceAddressResolver;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link MemberDirectory} 的默认实现：调用 System 的内部成员查询
 * {@code POST /api/internal/projects/{projectCode}/members/placement}，凭据是绑定本项目且带
 * {@code member:read} 范围的项目服务凭据。
 *
 * <p>只在归属转移这类低频管理动作里调用，不在普通业务请求路径上。响应有界、严格解析；任何异常、非 2xx、
 * 形态不符都抛出，由调用方失败关闭——绝不把故障解释成「不是成员」。</p>
 */
public final class SystemMemberDirectory implements MemberDirectory {

    private static final int MAX_RESPONSE_BYTES = 16_384;
    private static final int MAX_DEPARTMENTS = 200;
    private static final Pattern ID = Pattern.compile("^[1-9][0-9]{0,18}$");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final ServiceAddress address;
    private final ServiceAddressResolver resolver;
    private final String projectCode;
    private final String credential;
    private final RestClient client;

    /**
     * @param systemAddress System 地址，{@code http(s)://} 或 {@code lb://}
     * @param projectCode   本项目编码，路径与凭据绑定的项目必须一致
     * @param credential    带 {@code member:read} 范围的项目服务凭据
     * @param resolver      出站地址解析
     * @param timeout       连接与读取超时
     */
    public SystemMemberDirectory(
            String systemAddress,
            String projectCode,
            String credential,
            ServiceAddressResolver resolver,
            Duration timeout) {
        if (projectCode == null
                || !projectCode.matches("[a-z][a-z0-9-]{1,63}")
                || credential == null
                || !credential.matches("[!-~]+")
                || timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("成员查询配置无效");
        }
        this.address = ServiceAddress.parse(systemAddress);
        this.resolver = Objects.requireNonNull(resolver);
        this.projectCode = projectCode;
        this.credential = credential;
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** 查询用户的成员资格与所属部门；任何异常都向上抛。 */
    @Override
    public Placement placement(String userId) {
        if (userId == null || !ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("用户 ID 非法");
        }
        URI uri = URI.create(resolver.resolve(address).replaceAll("/+$", "") + "/api/internal/projects/" + projectCode
                + "/members/placement");
        JsonNode data = client.post()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(credential))
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsBytes(java.util.Map.of("userId", userId)))
                .exchange((request, response) -> decode(response));
        if (!data.path("userId").isString()
                || !userId.equals(data.path("userId").stringValue())) {
            throw new IllegalStateException("成员查询响应与请求不匹配");
        }
        JsonNode active = data.path("active");
        JsonNode departments = data.path("departmentIds");
        if (!active.isBoolean() || !departments.isArray() || departments.size() > MAX_DEPARTMENTS) {
            throw new IllegalStateException("成员查询响应格式错误");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode item : departments) {
            if (!item.isString() || !ID.matcher(item.stringValue()).matches() || !ids.add(parse(item.stringValue()))) {
                throw new IllegalStateException("成员查询响应格式错误");
            }
        }
        if (!active.booleanValue() && !ids.isEmpty()) {
            throw new IllegalStateException("成员查询响应格式错误");
        }
        return new Placement(active.booleanValue(), ids);
    }

    private static long parse(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException overflow) {
            throw new IllegalStateException("成员查询响应格式错误", overflow);
        }
    }

    /** 先限长读取再解析，仅接受 2xx 且 {@code code=0} 的统一响应。 */
    private static JsonNode decode(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        byte[] bytes;
        try (var body = response.getBody()) {
            bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("成员查询被拒绝：HTTP " + status);
        }
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException("成员查询响应过大");
        }
        JsonNode envelope = JSON.readTree(bytes);
        JsonNode code = envelope.path("code");
        JsonNode data = envelope.path("data");
        if (!code.isIntegralNumber() || code.intValue() != 0 || !data.isObject()) {
            throw new IllegalStateException("成员查询响应格式错误");
        }
        return data;
    }
}
