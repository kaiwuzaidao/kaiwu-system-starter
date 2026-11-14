package com.kaiwu.starter;

import com.kaiwu.starter.agent.AgentWriteBinding;
import com.kaiwu.starter.auth.config.MultiProjectProperties;
import com.kaiwu.starter.auth.model.ProjectAdmission;
import com.kaiwu.starter.auth.service.DeploymentProofVerifier;
import com.kaiwu.starter.auth.service.ProjectRegistrationCache;
import com.kaiwu.starter.auth.service.TrustedProjectRegistry;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.util.StringUtils;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 本地验证 Gateway 签发的 RS256、audience-bound Context。
 */
public final class GatewayContextVerifier implements AutoCloseable {

    public static final String CONTEXT_TYPE = "kaiwu-context+jwt";

    private static final JsonMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final PublicKey publicKey;
    private final String issuer;
    private final String audience;
    private final String projectCode;
    private final int maxContextBytes;
    private final long clockSkewSeconds;

    private final ProjectRegistrationCache registrations;
    private final String serviceCode;

    /** 保留单项目构造入口；该入口无法授予多项目资格。 */
    public GatewayContextVerifier(KaiwuStarterProperties properties) {
        this(properties, null, null);
    }

    /** 在多项目模式启用前验证部署证明并要求应用提供登记。 */
    GatewayContextVerifier(
            KaiwuStarterProperties properties, MultiProjectProperties deployment, TrustedProjectRegistry registry) {
        if (!StringUtils.hasText(properties.getContextPublicKey())) {
            throw new IllegalStateException("缺少必需配置：kaiwu.starter.context-public-key");
        }
        boolean multi = properties.getTenancy() == KaiwuStarterProperties.Tenancy.MULTI_PROJECT;
        if (properties.getTenancy() == null) {
            throw new IllegalStateException("Starter Context 模式无效");
        }
        if (!multi && !StringUtils.hasText(properties.getAudience())) {
            throw new IllegalStateException("缺少必需配置：kaiwu.starter.audience");
        }
        this.publicKey = parsePublicKey(properties.getContextPublicKey());
        this.issuer = properties.getIssuer();
        this.audience = properties.getAudience();
        this.projectCode = properties.getProjectCode();
        this.maxContextBytes = properties.getMaxContextBytes();
        this.clockSkewSeconds = properties.getClockSkewSeconds();
        if (multi) {
            if (registry == null) {
                throw new IllegalStateException("Starter 多项目模式缺少 TrustedProjectRegistry");
            }
            DeploymentProofVerifier.verify(deployment, publicKey);
            this.registrations = new ProjectRegistrationCache(registry, deployment.registryTimeout());
            this.serviceCode = deployment.serviceCode();
        } else {
            this.registrations = null;
            this.serviceCode = null;
        }
    }

    /** 仅普通 Gateway Context 使用多项目登记。 */
    public KaiwuContext verify(String token) {
        return verify(token, CONTEXT_TYPE, audience, registrations != null, claims -> {});
    }

    /** 项目登记提交后，使本实例的正负缓存和在途旧读取失效。 */
    public void invalidateProjectRegistration(String projectCode) {
        if (registrations != null) {
            registrations.invalidate(projectCode);
        }
    }

    /** 部署级登记拒绝状态提交后，清除本实例的全部登记缓存。 */
    public void invalidateProjectRegistrations() {
        if (registrations != null) {
            registrations.invalidateAll();
        }
    }

    /** 随应用关闭登记读取线程池。 */
    @Override
    public void close() {
        if (registrations != null) {
            registrations.close();
        }
    }

    /**
     * 在服务签名与 nonce 校验之后验证来源 Context。来源 audience 集合必须来自本地可信登记，
     * 不得使用外部请求自报；该步骤不表示完整委托关系或业务权限已经通过。
     * 调用方须先检查业务绑定的发起权限，再调用 {@link #requireDelegation}。
     */
    public KaiwuContext verifyDelegatedSource(String token, String sourceAudience, Set<String> registeredAudiences) {
        if (registrations != null
                || !StringUtils.hasText(projectCode)
                || !StringUtils.hasText(sourceAudience)
                || registeredAudiences == null
                || !registeredAudiences.contains(sourceAudience)) {
            throw new ContextVerificationException("Context 来源未登记");
        }
        return verify(token, CONTEXT_TYPE, sourceAudience, false, claims -> {});
    }

    /** 发起权限通过后检查由服务端可信登记构造的完整委托关系。 */
    public void requireDelegation(DelegationBinding observed, Set<DelegationBinding> allowed) {
        if (registrations != null
                || !StringUtils.hasText(projectCode)
                || observed == null
                || allowed == null
                || !allowed.contains(observed)
                || !projectCode.equals(observed.projectCode())
                || !observed.isValid()) {
            throw new ContextVerificationException("Context 委托关系无效");
        }
    }

    /** 由服务端登记的完整委托关系，不包含凭据或外部自报授权事实。 */
    public record DelegationBinding(
            String callerService,
            String sourceAudience,
            String projectCode,
            String targetService,
            String endpoint,
            String scope,
            String businessType) {
        /** 所有元组字段均应为非空的规范登记值。 */
        private boolean isValid() {
            return valid(callerService)
                    && valid(sourceAudience)
                    && valid(projectCode)
                    && valid(targetService)
                    && valid(endpoint)
                    && valid(scope)
                    && valid(businessType);
        }
        /** 拒绝带首尾空白的模糊登记值。 */
        private static boolean valid(String value) {
            return value != null && !value.isBlank() && value.equals(value.trim());
        }
    }

    /** Agent Context 仅供固定 Handler 入口调用；不扩大普通 API 的令牌类型。 */
    public KaiwuContext verifyAgent(
            String token, String code, String version, String manifestHash, String paramsHash, String correlationId) {
        if (token != null && token.length() > 16384) throw new ContextVerificationException("Agent Context 过大");
        if (registrations != null || !StringUtils.hasText(projectCode))
            throw new ContextVerificationException("Agent 必须绑定项目");
        return verify(token, "kaiwu-agent-action-context+jwt", claims -> {
            require(code.equals(claims.get("capability", String.class)), "Agent 能力不匹配");
            require(version.equals(claims.get("version", String.class)), "Agent 版本不匹配");
            require(manifestHash.equals(claims.get("manifestHash", String.class)), "Agent 清单不匹配");
            require(paramsHash.equals(claims.get("paramsHash", String.class)), "Agent 参数不匹配");
            require(correlationId.equals(claims.get("correlationId", String.class)), "Agent 请求不匹配");
            require(Boolean.TRUE.equals(claims.get("readOnly", Boolean.class)), "Agent 只读标记缺失");
            require(
                    !claims.getExpiration()
                            .after(new java.util.Date(claims.getIssuedAt().getTime() + 60_000)),
                    "Agent Context 有效期超过 60 秒");
        });
    }

    /** 写用途 Context 与查询及普通 API 严格隔离，预览、执行、结果查询也不能互换。 */
    public KaiwuContext verifyAgentWrite(String token, AgentWriteBinding binding) {
        if (token != null && token.length() > 16_384) throw new ContextVerificationException("Agent Context 过大");
        if (registrations != null || !StringUtils.hasText(projectCode))
            throw new ContextVerificationException("Agent 必须绑定项目");
        return verify(token, "kaiwu-agent-write-context+jwt", claims -> {
            require(binding.operation().equals(claims.get("operation", String.class)), "Agent 写用途不匹配");
            require(binding.code().equals(claims.get("capability", String.class)), "Agent 能力不匹配");
            require(binding.version().equals(claims.get("version", String.class)), "Agent 版本不匹配");
            require(binding.manifestHash().equals(claims.get("manifestHash", String.class)), "Agent 清单不匹配");
            require(binding.paramsHash().equals(claims.get("paramsHash", String.class)), "Agent 参数不匹配");
            require(
                    java.util.Objects.equals(
                            binding.preconditionsHash(), claims.get("preconditionsHash", String.class)),
                    "Agent 前置条件不匹配");
            require(
                    java.util.Objects.equals(binding.previewHash(), claims.get("previewHash", String.class)),
                    "Agent 预览不匹配");
            require(java.util.Objects.equals(binding.actionId(), claims.get("actionId", String.class)), "Agent 动作不匹配");
            require(
                    java.util.Objects.equals(binding.actionExpiresAt(), claims.get("actionExpiresAt", Long.class)),
                    "Agent 动作有效期不匹配");
            require(binding.correlationId().equals(claims.get("correlationId", String.class)), "Agent 请求不匹配");
            boolean execute = "EXECUTE".equals(binding.operation());
            require(Boolean.valueOf(!execute).equals(claims.get("readOnly", Boolean.class)), "Agent 写类型不匹配");
            require(
                    Boolean.valueOf(!"PREVIEW".equals(binding.operation()))
                            .equals(claims.get("confirmed", Boolean.class)),
                    "Agent 确认状态不匹配");
            if (execute) require(claims.getExpiration().getTime() <= binding.actionExpiresAt(), "Agent 写动作已过期");
            require(
                    !claims.getExpiration()
                            .after(new java.util.Date(claims.getIssuedAt().getTime() + 60_000)),
                    "Agent Context 有效期超过 60 秒");
        });
    }

    /** 普通及 Agent 入口保持配置的固定目标 audience。 */
    private KaiwuContext verify(String token, String expectedType, Consumer<Claims> binding) {
        return verify(token, expectedType, audience, false, binding);
    }

    /** 所有入口复用相同的签名、有效期和项目校验。 */
    private KaiwuContext verify(
            String token, String expectedType, String requiredAudience, boolean multi, Consumer<Claims> binding) {
        if (!StringUtils.hasText(token)) {
            throw new ContextVerificationException("缺少 Gateway Context");
        }
        if (token.getBytes(StandardCharsets.US_ASCII).length > maxContextBytes) {
            throw new ContextVerificationException("Gateway Context 超过大小限制");
        }

        try {
            Jws<Claims> parsed = Jwts.parser()
                    .verifyWith(publicKey)
                    .clockSkewSeconds(clockSkewSeconds)
                    .build()
                    .parseSignedClaims(token);
            JwsHeader header = parsed.getHeader();
            Claims claims = parsed.getPayload();

            require("RS256".equals(header.getAlgorithm()), "Context 签名算法错误");
            require(expectedType.equals(header.getType()), "Context 类型错误");
            require(issuer.equals(claims.getIssuer()), "Context issuer 错误");
            if (!multi) {
                require(hasAudience(claims.get("aud"), requiredAudience), "Context audience 错误");
            }
            require(StringUtils.hasText(claims.getSubject()), "Context 缺少用户 ID");
            require(StringUtils.hasText(claims.get("sid", String.class)), "Context 缺少会话 ID");
            require(StringUtils.hasText(claims.getId()), "Context 缺少 jti");
            require(claims.getIssuedAt() != null && claims.getExpiration() != null, "Context 缺少有效期");

            Instant issuedAt = claims.getIssuedAt().toInstant();
            Instant expiresAt = claims.getExpiration().toInstant();
            Instant now = Instant.now();
            require(!issuedAt.isAfter(now.plusSeconds(clockSkewSeconds)), "Context 签发时间无效");
            require(expiresAt.isAfter(issuedAt), "Context 有效期无效");
            require(expiresAt.isAfter(now.minusSeconds(clockSkewSeconds)), "Context 已过期");
            require(!expiresAt.isAfter(issuedAt.plusSeconds(65)), "Context 有效期超过 60 秒");

            String contextProjectCode = claims.get("projectCode", String.class);
            String contextProjectId = claims.get("projectId", String.class);
            ProjectAdmission admission = null;
            if (multi) {
                require(
                        StringUtils.hasText(contextProjectCode) && StringUtils.hasText(contextProjectId),
                        "Context 缺少项目绑定");
                JsonNode payload = STRICT_JSON.readTree(
                        Base64.getUrlDecoder().decode(token.substring(token.indexOf('.') + 1, token.lastIndexOf('.'))));
                require(
                        !payload.has("serviceCode") || matchesText(payload.get("serviceCode"), serviceCode),
                        "Context 服务绑定错误");
                var registration = registrations
                        .find(contextProjectCode)
                        .orElseThrow(() -> new ContextVerificationException("Context 项目准入被拒"));
                require(
                        registration.admission() != null
                                && registration.admission() != ProjectAdmission.DENY
                                && registration.revision() >= 0
                                && StringUtils.hasText(registration.audience())
                                && contextProjectId.equals(registration.projectId())
                                && hasExactAudience(payload.get("aud"), registration.audience()),
                        "Context 项目准入被拒");
                require(expiresAt.isAfter(Instant.now().minusSeconds(clockSkewSeconds)), "Context 已过期");
                admission = registration.admission();
                requiredAudience = registration.audience();
            } else if (StringUtils.hasText(projectCode)) {
                require(projectCode.equals(contextProjectCode), "Context 项目编码错误");
                require(StringUtils.hasText(contextProjectId), "Context 缺少项目 ID");
            }

            binding.accept(claims);
            return new KaiwuContext(
                    claims.getSubject(),
                    claims.get("sid", String.class),
                    contextProjectId,
                    contextProjectCode,
                    stringSet(claims.get("permissions")),
                    stringSet(claims.get("projectRoleCodes")),
                    claims.get("authzVersion", String.class),
                    requiredAudience,
                    issuedAt,
                    expiresAt,
                    claims.getId(),
                    com.kaiwu.starter.auth.model.ContextDataScope.parse(claims.get("ds")),
                    admission);
        } catch (ContextVerificationException exception) {
            throw exception;
        } catch (Exception exception) {
            if (multi) {
                throw new ContextVerificationException("Gateway Context 验证失败");
            }
            throw new ContextVerificationException("Gateway Context 验证失败", exception);
        }
    }

    private static PublicKey parsePublicKey(String pem) {
        try {
            String normalized = pem.replace("\\n", "\n")
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] encoded = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("kaiwu.starter.context-public-key 不是有效的 RSA 公钥", exception);
        }
    }

    /** JWT 库会将受众数组规范化成集合，多项目模式按已验签的原始数组检查唯一值。 */
    private static boolean hasExactAudience(JsonNode audience, String expected) {
        return matchesText(audience, expected)
                || (audience != null
                        && audience.isArray()
                        && audience.size() == 1
                        && matchesText(audience.get(0), expected));
    }

    /** 声明必须为文本且与权威值逐字一致。 */
    private static boolean matchesText(JsonNode node, String expected) {
        return node != null && node.isString() && expected.equals(node.asString());
    }

    private static boolean hasAudience(Object rawAudience, String required) {
        if (rawAudience instanceof String value) {
            return required.equals(value);
        }
        if (rawAudience instanceof Collection<?> values) {
            return values.size() == 1 && values.contains(required);
        }
        return false;
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : values) {
            if (item instanceof String text && StringUtils.hasText(text)) {
                result.add(text);
            }
        }
        return Set.copyOf(result);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ContextVerificationException(message);
        }
    }
}
