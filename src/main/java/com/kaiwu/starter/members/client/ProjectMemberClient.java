package com.kaiwu.starter.members.client;

import com.kaiwu.starter.client.ServiceAddress;
import com.kaiwu.starter.client.ServiceAddressResolver;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 内网候选成员客户端；失败明确抛错，不能把依赖故障解释为空成员。 */
public final class ProjectMemberClient {
    private static final int MAX_RESPONSE_BYTES = 32_768;
    private static final int MAX_MEMBERS = 200;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final ServiceAddress address;
    private final ServiceAddressResolver resolver;
    private final RestClient client;
    private final String projectCode;
    private final String credential;

    /** 显式注入 project 与 member:read 凭据；不与旧共享投递令牌自动回退。 */
    public ProjectMemberClient(
            String systemAddress,
            String projectCode,
            String credential,
            ServiceAddressResolver resolver,
            Duration timeout) {
        if (projectCode == null
                || !projectCode.matches("[a-z][a-z0-9-]{1,63}")
                || credential == null
                || !credential.matches("[!-~]{1,4096}")
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("invalid member client configuration");
        }
        validateAddress(systemAddress, true);
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

    /** 获取角色完整候选 ID；重复、溢出、超限或依赖异常整体拒绝。 */
    public List<String> membersByRole(String roleCode) {
        if (!validRole(roleCode)) throw new IllegalArgumentException("invalid role code");
        try {
            URI uri = org.springframework.web.util.UriComponentsBuilder.fromUri(endpoint("/members/by-role"))
                    .queryParam("roleCode", "{role}")
                    .encode()
                    .buildAndExpand(roleCode)
                    .toUri();
            JsonNode data = client.get()
                    .uri(uri)
                    .headers(h -> h.setBearerAuth(credential))
                    .exchange((request, response) -> decode(response));
            var role = data.path("roleCode");
            var ids = data.path("userIds");
            if (!role.isString() || !roleCode.equals(role.stringValue()) || !ids.isArray() || ids.size() > MAX_MEMBERS)
                throw new MemberQueryException("invalid member response");
            var users = new java.util.ArrayList<String>();
            for (var id : ids) {
                if (!id.isString() || !validId(id.stringValue()) || users.contains(id.stringValue()))
                    throw new MemberQueryException("invalid member response");
                users.add(id.stringValue());
            }
            return List.copyOf(users);
        } catch (MemberQueryException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new MemberQueryException("member query failed");
        }
    }

    /** 逐项核验完整静态候选，缺失字段、重复项、非法类型均不视为空候选。 */
    public List<Eligibility> eligibility(List<String> userIds, String roleCode) {
        if (userIds == null
                || userIds.isEmpty()
                || userIds.size() > MAX_MEMBERS
                || userIds.stream().anyMatch(id -> !validId(id))
                || (roleCode != null && !validRole(roleCode)))
            throw new IllegalArgumentException("invalid eligibility request");
        var unique = userIds.stream().distinct().toList();
        try {
            byte[] payload = JSON.writeValueAsBytes(new EligibilityRequest(unique, roleCode));
            JsonNode data = client.post()
                    .uri(endpoint("/members/eligibility"))
                    .headers(h -> h.setBearerAuth(credential))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((request, response) -> decode(response));
            var items = data.path("items");
            if (!items.isArray() || items.size() != unique.size())
                throw new MemberQueryException("invalid eligibility response");
            var result = new java.util.ArrayList<Eligibility>();
            for (int i = 0; i < unique.size(); i++) {
                var item = items.get(i);
                var id = item.path("userId");
                var eligible = item.path("eligible");
                if (!id.isString() || !unique.get(i).equals(id.stringValue()) || !eligible.isBoolean())
                    throw new MemberQueryException("invalid eligibility response");
                result.add(new Eligibility(id.stringValue(), eligible.booleanValue()));
            }
            return List.copyOf(result);
        } catch (MemberQueryException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new MemberQueryException("member query failed");
        }
    }

    /** 先识别拒绝状态，再有界读取成功正文；在外层关闭前取消JDK流，禁止排空未结束的远端响应。 */
    private static JsonNode decode(org.springframework.http.client.ClientHttpResponse response)
            throws java.io.IOException {
        int status = response.getStatusCode().value();
        try (var body = response.getBody()) {
            if (status < 200 || status >= 300) throw new MemberQueryException("member query rejected", status);
            return decode(status, body.readNBytes(MAX_RESPONSE_BYTES + 1));
        }
    }

    /** 仅解析有界成功响应；失败保留 HTTP 分类，不保留正文。 */
    private static JsonNode decode(int status, byte[] bytes) {
        if (status < 200 || status >= 300) throw new MemberQueryException("member query rejected", status);
        if (bytes.length > MAX_RESPONSE_BYTES) throw new MemberQueryException("invalid member response");
        var envelope = JSON.readTree(bytes);
        var code = envelope.path("code");
        var data = envelope.path("data");
        if (!code.isIntegralNumber() || !code.canConvertToInt() || code.intValue() != 0 || !data.isObject())
            throw new MemberQueryException("invalid member response");
        return data;
    }

    /** 每次调用重新寻址，拒绝向用户信息、额外路径或 query 指定的地址发送凭据。 */
    private URI endpoint(String path) {
        String base = resolver.resolve(address);
        validateAddress(base, false);
        return URI.create(base.replaceAll("/+$", "") + "/api/internal/projects/" + projectCode + path);
    }

    /** 所有网络目标须为纯服务原点，逻辑地址只在解析前允许。 */
    private static void validateAddress(String value, boolean allowLb) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid member service address");
        }
        if (uri.getScheme() == null
                || !(uri.getScheme().equals("http")
                        || uri.getScheme().equals("https")
                        || (allowLb && uri.getScheme().equals("lb")))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getRawPath() != null
                        && !uri.getRawPath().isEmpty()
                        && !uri.getRawPath().equals("/")))
            throw new IllegalArgumentException("invalid member service address");
    }

    /** 平台用户 ID 的无前导零正数 BIGINT 范围。 */
    private static boolean validId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) return false;
        try {
            return Long.parseLong(id) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 角色编码与平台 C3 契约一致。 */
    private static boolean validRole(String role) {
        return role != null && role.matches("[a-zA-Z0-9][a-zA-Z0-9:_-]{0,63}");
    }

    /** System 包装字段；错误信息不用于记录敏感响应。 */
    public record Envelope<T>(int code, T data) {}
    /** 角色与服务端完整返回的候选人。 */
    public record RoleResult(String roleCode, List<String> userIds) {}
    /** 请求中指定候选快照和可选角色。 */
    public record EligibilityRequest(List<String> userIds, String roleCode) {}
    /** 服务端逐项资格。 */
    public record EligibilityResult(List<Eligibility> items) {}
    /** 用户 ID 及当前资格；Boolean 用于检测缺失字段。 */
    public record Eligibility(String userId, Boolean eligible) {}
}
