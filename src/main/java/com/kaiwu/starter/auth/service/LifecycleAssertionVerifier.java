package com.kaiwu.starter.auth.service;

import com.kaiwu.starter.KaiwuStarterProperties;
import com.kaiwu.starter.auth.config.MultiProjectProperties;
import com.kaiwu.starter.auth.exception.LifecycleVerificationException;
import com.kaiwu.starter.auth.model.LifecycleEndpoint;
import com.kaiwu.starter.auth.model.LifecycleRequest;
import com.kaiwu.starter.auth.model.VerifiedLifecycleAssertion;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ADR 0045 的独立生命周期身份校验，只授权固定端点并在领域调用前占用 nonce。 */
public final class LifecycleAssertionVerifier {
    public static final String HEADER = "X-Kaiwu-Lifecycle";
    public static final String TYPE = "kaiwu-app-lifecycle+jwt";
    private static final String ISSUER = "kaiwu-system";
    private static final int MAX_ASSERTION_CHARS = 16 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final String appCode;
    private final String deploymentId;
    private final Map<String, PublicKey> keys;
    private final LifecycleNonceStore nonces;
    private final List<LifecycleEndpoint> endpoints;
    private final Clock clock;

    /** 正常应用复用受控部署公钥集，并采用 ADR 固定端点和 UTC 时钟。 */
    public LifecycleAssertionVerifier(
            MultiProjectProperties deployment, KaiwuStarterProperties starter, LifecycleNonceStore nonces) {
        this(deployment, starter, nonces, LifecycleEndpoint.standardEndpoints(), Clock.systemUTC());
    }

    /** 应用可收窄消费白名单，所有副本必须使用同一权威 nonce 存储。 */
    public LifecycleAssertionVerifier(
            MultiProjectProperties deployment,
            KaiwuStarterProperties starter,
            LifecycleNonceStore nonces,
            List<LifecycleEndpoint> endpoints,
            Clock clock) {
        this.keys = LifecyclePublicKeys.loadConfiguration(deployment, starter.getContextPublicKey());
        this.appCode = deployment.appCode();
        this.deploymentId = deployment.deploymentId();
        this.nonces = Objects.requireNonNull(nonces, "生命周期 nonce 存储必须由应用提供");
        this.endpoints = List.copyOf(endpoints);
        if (this.endpoints.isEmpty()
                || !LifecycleEndpoint.standardEndpoints().containsAll(this.endpoints)
                || Set.copyOf(this.endpoints).size() != this.endpoints.size()) {
            throw new IllegalStateException("生命周期端点白名单无效");
        }
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 仅由已匹配固定生命周期处理器的入口调用，普通业务端点继续使用原认证。
     * 原始目标和正文必须在转发重写、解码、解析或解压前捕获，缺失时不能用框架归一化值代替。
     * 所有凭据及绑定失败返回 401，nonce 未知或异常返回 503，不建立用户 StarterContext。
     */
    public VerifiedLifecycleAssertion verify(LifecycleRequest request) {
        if (request == null) {
            throw rejected();
        }
        if (!request.contentEncodingHeaders().isEmpty()) {
            throw new LifecycleVerificationException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        final VerifiedLifecycleAssertion verified;
        final String jti;
        final Instant retainUntil;
        try {
            require(request.assertionHeaders().size() == 1);
            String compact = request.assertionHeaders().getFirst();
            require(compact.length() <= MAX_ASSERTION_CHARS);
            String[] parts = compact.split("\\.", -1);
            require(parts.length == 3);
            JsonNode header = json(decode(parts[0]));
            require(header.size() == 3 && TYPE.equals(text(header, "typ")) && "RS256".equals(text(header, "alg")));
            String kid = text(header, "kid");
            PublicKey key = keys.get(kid);
            require(key != null);
            byte[] signature = decode(parts[2]);
            var verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            require(verifier.verify(signature));
            JsonNode claims = json(decode(parts[1]));
            require(ISSUER.equals(text(claims, "iss"))
                    && "system".equals(text(claims, "sub"))
                    && appCode.equals(text(claims, "appCode"))
                    && deploymentId.equals(text(claims, "deploymentId"))
                    && ("kaiwu-app-lifecycle." + appCode + "." + deploymentId).equals(text(claims, "aud")));
            long issuedAt = seconds(claims, "iat");
            long expiresAt = seconds(claims, "exp");
            long lifetime = Math.subtractExact(expiresAt, issuedAt);
            long now = clock.instant().getEpochSecond();
            require(lifetime > 0
                    && lifetime <= 30
                    && issuedAt <= Math.addExact(now, 5)
                    && now < Math.addExact(expiresAt, 5));
            String path = LifecycleRequestTarget.path(request.rawTarget());
            LifecycleEndpoint endpoint = endpoint(request.method(), path);
            require(text(claims, "scope").equals(endpoint.scope())
                    && text(claims, "htm").equals(request.method())
                    && text(claims, "htu").equals(request.rawTarget()));
            String projectCode = projectCode(endpoint, path);
            String projectId = null;
            if (projectCode != null) {
                projectId = text(claims, "projectId");
                require(projectId.matches("[1-9][0-9]*"));
                require(request.registeredProjectId() == null
                        || request.registeredProjectId().equals(projectId));
            } else {
                require(!claims.has("projectId") && request.registeredProjectId() == null);
            }
            byte[] body = request.body();
            byte[] digest = decode(text(claims, "bsh"));
            require(digest.length == 32
                    && MessageDigest.isEqual(
                            digest, MessageDigest.getInstance("SHA-256").digest(body)));
            String operationId = text(claims, "operationId");
            if (body.length != 0) {
                JsonNode payload = json(body);
                requireSameIfPresent(payload, "operationId", operationId);
                requireSameIfPresent(payload, "projectId", projectId);
            }
            jti = text(claims, "jti");
            require(decode(jti).length == 16);
            // 包含验证端最多 5 秒落后，不能仅以某一副本的 exp+5 提前清理。
            retainUntil = Instant.ofEpochSecond(Math.addExact(expiresAt, 10));
            verified = new VerifiedLifecycleAssertion(
                    operationId,
                    projectId,
                    projectCode,
                    appCode,
                    deploymentId,
                    endpoint.scope(),
                    kid,
                    Instant.ofEpochSecond(expiresAt));
        } catch (Exception invalid) {
            throw rejected();
        }
        final LifecycleNonceStore.ClaimResult claimed;
        try {
            claimed = nonces.claim(ISSUER, deploymentId, jti, retainUntil);
        } catch (Exception unavailable) {
            throw new LifecycleVerificationException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (claimed == LifecycleNonceStore.ClaimResult.REPLAY) {
            throw rejected();
        }
        if (claimed != LifecycleNonceStore.ClaimResult.CLAIMED) {
            throw new LifecycleVerificationException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return verified;
    }

    /** 按实际方法与规范路径精确选择白名单，拒绝前缀和任意端点。 */
    private LifecycleEndpoint endpoint(String method, String path) {
        for (LifecycleEndpoint endpoint : endpoints) {
            if (endpoint.method().equals(method)) {
                String template = endpoint.pathTemplate();
                int marker = template.indexOf("{projectCode}");
                if (marker < 0 && template.equals(path)) {
                    return endpoint;
                }
                if (marker >= 0
                        && path.startsWith(template.substring(0, marker))
                        && path.endsWith(template.substring(marker + "{projectCode}".length()))) {
                    projectCode(endpoint, path);
                    return endpoint;
                }
            }
        }
        throw new IllegalArgumentException();
    }

    /** 从白名单模板的唯一项目槽位取得已绑定编码，禁止额外路径段。 */
    private static String projectCode(LifecycleEndpoint endpoint, String path) {
        String template = endpoint.pathTemplate();
        int marker = template.indexOf("{projectCode}");
        if (marker < 0) {
            return null;
        }
        String suffix = template.substring(marker + "{projectCode}".length());
        String code = path.substring(marker, path.length() - suffix.length());
        require(code.matches("[a-z][a-z0-9-]{1,63}"));
        return code;
    }

    /** 拒绝重复键、尾随 JSON 和非法 UTF-8，载荷必须为对象。 */
    private static JsonNode json(byte[] bytes) throws Exception {
        String text = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        JsonNode node = JSON.readTree(text);
        require(node != null && node.isObject());
        return node;
    }

    /** 三段及声明中的 base64url 均须无填充且按原字节规范编码。 */
    private static byte[] decode(String value) {
        require(value != null && !value.isEmpty() && value.matches("[A-Za-z0-9_-]+"));
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value));
        return bytes;
    }

    /** 声明类型必须确为非空字符串，不允许数字、数组或空白替代。 */
    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        require(value != null && value.isString() && !value.asString().isBlank());
        return value.asString();
    }

    /** 时间必须是可表示的 UTC 整秒整数，拒绝浮点和字符串转换。 */
    private static long seconds(JsonNode object, String field) {
        JsonNode value = object.get(field);
        require(value != null && value.isIntegralNumber() && value.canConvertToLong());
        return value.longValue();
    }

    /** 正文重复身份只能与签名身份完全相等，应用级请求不得附项目身份。 */
    private static void requireSameIfPresent(JsonNode body, String field, String expected) {
        if (body.has(field)) {
            require(expected != null && expected.equals(text(body, field)));
        }
    }

    /** 所有凭据错误使用固定无原因链异常。 */
    private static LifecycleVerificationException rejected() {
        return new LifecycleVerificationException(HttpStatus.UNAUTHORIZED);
    }

    /** 在 nonce 占用前统一终止无效输入。 */
    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException();
        }
    }
}
