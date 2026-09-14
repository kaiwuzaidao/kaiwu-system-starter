package com.kaiwu.starter;

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
import org.springframework.util.StringUtils;

/**
 * 本地验证 Gateway 签发的 RS256、audience-bound Context。
 */
public final class GatewayContextVerifier {

    public static final String CONTEXT_TYPE = "kaiwu-context+jwt";

    private final PublicKey publicKey;
    private final String issuer;
    private final String audience;
    private final String projectCode;
    private final int maxContextBytes;
    private final long clockSkewSeconds;

    public GatewayContextVerifier(KaiwuStarterProperties properties) {
        if (!StringUtils.hasText(properties.getContextPublicKey())) {
            throw new IllegalStateException("缺少必需配置：kaiwu.starter.context-public-key");
        }
        if (!StringUtils.hasText(properties.getAudience())) {
            throw new IllegalStateException("缺少必需配置：kaiwu.starter.audience");
        }
        this.publicKey = parsePublicKey(properties.getContextPublicKey());
        this.issuer = properties.getIssuer();
        this.audience = properties.getAudience();
        this.projectCode = properties.getProjectCode();
        this.maxContextBytes = properties.getMaxContextBytes();
        this.clockSkewSeconds = properties.getClockSkewSeconds();
    }

    public KaiwuContext verify(String token) {
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
            require(CONTEXT_TYPE.equals(header.getType()), "Context 类型错误");
            require(issuer.equals(claims.getIssuer()), "Context issuer 错误");
            require(hasAudience(claims.get("aud"), audience), "Context audience 错误");
            require(StringUtils.hasText(claims.getSubject()), "Context 缺少用户 ID");
            require(StringUtils.hasText(claims.get("sid", String.class)), "Context 缺少会话 ID");
            require(StringUtils.hasText(claims.getId()), "Context 缺少 jti");
            require(claims.getIssuedAt() != null && claims.getExpiration() != null, "Context 缺少有效期");

            Instant issuedAt = claims.getIssuedAt().toInstant();
            Instant expiresAt = claims.getExpiration().toInstant();
            Instant now = Instant.now();
            require(!issuedAt.isAfter(now.plusSeconds(clockSkewSeconds)), "Context 签发时间无效");
            require(expiresAt.isAfter(now.minusSeconds(clockSkewSeconds)), "Context 已过期");
            require(!expiresAt.isAfter(issuedAt.plusSeconds(65)), "Context 有效期超过 60 秒");

            String contextProjectCode = claims.get("projectCode", String.class);
            String contextProjectId = claims.get("projectId", String.class);
            if (StringUtils.hasText(projectCode)) {
                require(projectCode.equals(contextProjectCode), "Context 项目编码错误");
                require(StringUtils.hasText(contextProjectId), "Context 缺少项目 ID");
            }

            return new KaiwuContext(
                    claims.getSubject(),
                    claims.get("sid", String.class),
                    contextProjectId,
                    contextProjectCode,
                    stringSet(claims.get("permissions")),
                    stringSet(claims.get("projectRoleCodes")),
                    claims.get("authzVersion", String.class),
                    audience,
                    issuedAt,
                    expiresAt,
                    claims.getId());
        } catch (ContextVerificationException exception) {
            throw exception;
        } catch (Exception exception) {
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
