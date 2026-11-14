package com.kaiwu.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayContextVerifierTest {

    private KeyPair keyPair;
    private KaiwuStarterProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        properties = new KaiwuStarterProperties();
        properties.setContextPublicKey(pem(keyPair));
        properties.setAudience("kaiwu-demo-service");
        properties.setProjectCode("demo");
    }

    @Test
    void verifiesAudienceProjectAndPermissions() {
        GatewayContextVerifier verifier = new GatewayContextVerifier(properties);

        KaiwuContext context = verifier.verify(token("kaiwu-demo-service", "demo"));

        assertThat(context.userId()).isEqualTo("1900000000000000001");
        assertThat(context.projectId()).isEqualTo("1900000000000000100");
        assertThat(context.hasPermission("demo:record:list")).isTrue();
    }

    @Test
    void rejectsWrongAudience() {
        GatewayContextVerifier verifier = new GatewayContextVerifier(properties);

        assertThatThrownBy(() -> verifier.verify(token("other-service", "demo")))
                .isInstanceOf(ContextVerificationException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void rejectsWrongProject() {
        GatewayContextVerifier verifier = new GatewayContextVerifier(properties);

        assertThatThrownBy(() -> verifier.verify(token("kaiwu-demo-service", "other")))
                .isInstanceOf(ContextVerificationException.class)
                .hasMessageContaining("项目编码");
    }

    @Test
    void rejectsExpiredContext() {
        GatewayContextVerifier verifier = new GatewayContextVerifier(properties);

        assertThatThrownBy(() -> verifier.verify(token(
                        "kaiwu-demo-service",
                        "demo",
                        Instant.now().minusSeconds(120),
                        Instant.now().minusSeconds(60))))
                .isInstanceOf(ContextVerificationException.class);
    }

    /** 带合法 ds 的 Context 解析出数据范围；没有 ds 为 null；非法 ds 使验证失败。 */
    @Test
    void parsesDataScopeAndFailsClosedOnInvalidOne() {
        GatewayContextVerifier verifier = new GatewayContextVerifier(properties);
        java.util.Map<String, Object> ds = new java.util.LinkedHashMap<>();
        ds.put("v", 1);
        ds.put("primaryDeptId", "101");
        ds.put("depts", List.of("101"));
        ds.put("rules", java.util.Map.of("crm:customer", java.util.Map.of("set", List.of(0))));
        ds.put("fp", "0000000000000000");

        assertThat(verifier.verify(token("kaiwu-demo-service", "demo")).dataScope())
                .isNull();
        assertThatThrownBy(() -> verifier.verify(tokenWith(ds))).isInstanceOf(ContextVerificationException.class);
        ds.put("fp", fingerprintOf());
        assertThat(verifier.verify(tokenWith(ds))
                        .dataScope()
                        .rule("crm:customer")
                        .deptIds())
                .containsExactly(101L);
    }

    /** 上面单资源范围的规范化指纹（按契约 §4.3 手工拼出的字节）。 */
    private static String fingerprintOf() {
        String canonical =
                "{\"v\":1,\"primaryDeptId\":\"101\",\"depts\":[\"101\"],\"rules\":{\"crm:customer\":{\"set\":[0]}}}";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 签发带指定 ds 声明的 Context。 */
    private String tokenWith(java.util.Map<String, Object> ds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header()
                .type(GatewayContextVerifier.CONTEXT_TYPE)
                .and()
                .issuer("kaiwu-gateway-service")
                .audience()
                .add("kaiwu-demo-service")
                .and()
                .subject("1900000000000000001")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .claim("sid", "session-1")
                .claim("projectId", "1900000000000000100")
                .claim("projectCode", "demo")
                .claim("permissions", List.of("demo:record:list"))
                .claim("projectRoleCodes", List.of("project_admin"))
                .claim("authzVersion", "v1")
                .claim("ds", ds)
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /** 委托仅允许可信登记的完整关系，普通入口仍拒绝来源 audience。 */
    @Test
    void delegatedAudienceRequiresExactRegisteredBinding() {
        var verifier = new GatewayContextVerifier(properties);
        var binding = new GatewayContextVerifier.DelegationBinding(
                "purchase",
                "kaiwu-purchase-service",
                "demo",
                "workflow",
                "POST /internal/v1/instances",
                "instance:start",
                "purchase-order");
        String delegated = token("kaiwu-purchase-service", "demo");
        assertThatThrownBy(() -> verifier.verify(delegated)).isInstanceOf(ContextVerificationException.class);
        assertThat(verifier.verifyDelegatedSource(delegated, binding.sourceAudience(), Set.of(binding.sourceAudience()))
                        .audience())
                .isEqualTo("kaiwu-purchase-service");
        verifier.requireDelegation(binding, Set.of(binding));
        assertThatThrownBy(() -> verifier.verifyDelegatedSource(delegated, binding.sourceAudience(), Set.of()))
                .isInstanceOf(ContextVerificationException.class);
        var wrong = new GatewayContextVerifier.DelegationBinding(
                "purchase",
                "kaiwu-purchase-service",
                "demo",
                "workflow",
                "POST /internal/v1/instances",
                "instance:start",
                "other");
        assertThatThrownBy(() -> verifier.requireDelegation(wrong, Set.of(binding)))
                .isInstanceOf(ContextVerificationException.class);
        assertThatThrownBy(() -> verifier.verifyDelegatedSource(
                        token("kaiwu-purchase-service", "other"),
                        binding.sourceAudience(),
                        Set.of(binding.sourceAudience())))
                .isInstanceOf(ContextVerificationException.class);
        assertThatThrownBy(() -> verifier.verifyDelegatedSource(
                        token("wrong-service", "demo"), binding.sourceAudience(), Set.of(binding.sourceAudience())))
                .isInstanceOf(ContextVerificationException.class);
    }

    private String token(String audience, String projectCode) {
        Instant now = Instant.now();
        return token(audience, projectCode, now, now.plusSeconds(60));
    }

    private String token(String audience, String projectCode, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .header()
                .type(GatewayContextVerifier.CONTEXT_TYPE)
                .and()
                .issuer("kaiwu-gateway-service")
                .audience()
                .add(audience)
                .and()
                .subject("1900000000000000001")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claim("sid", "session-1")
                .claim("projectId", "1900000000000000100")
                .claim("projectCode", projectCode)
                .claim("permissions", List.of("demo:record:list"))
                .claim("projectRoleCodes", List.of("project_admin"))
                .claim("authzVersion", "v1")
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String pem(KeyPair pair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }
}
