package com.kaiwu.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.auth.config.MultiProjectProperties;
import com.kaiwu.starter.auth.model.ProjectAdmission;
import com.kaiwu.starter.auth.service.TrustedProjectRegistry;
import com.kaiwu.starter.auth.service.TrustedProjectRegistry.Registration;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 多项目模式在验签、登记与请求上下文之间的边界。 */
class MultiProjectContextTest {
    private static final String DIGEST = "a".repeat(64);
    private static KeyPair gateway;
    private static KeyPair platform;
    private static KeyPair nextPlatform;

    /** 独立模拟 Gateway 和两代平台签名密钥。 */
    @BeforeAll
    static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        gateway = generator.generateKeyPair();
        platform = generator.generateKeyPair();
        nextPlatform = generator.generateKeyPair();
    }

    /** 真实自动装配可无固定 audience，准入随请求建立并清理。 */
    @Test
    void autoConfigurationBindsProjectAndClearsRequestContext() {
        runner(proof("primary", platform, "kaiwu-app-deployment+jwt", "workflow"), "deploy-aaa", DIGEST)
                .withBean(TrustedProjectRegistry.class, () -> code -> switch (code) {
                    case "alpha" -> registration(ProjectAdmission.DRAIN);
                    case "beta" -> Optional.of(new Registration("102", "beta-workflow", 2, ProjectAdmission.READ_ONLY));
                    default -> Optional.empty();
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var verifier = context.getBean(GatewayContextVerifier.class);
                    var interceptor = new StarterAuthInterceptor(verifier, new ContextPermissionResolver());
                    var request = new MockHttpServletRequest();
                    request.addHeader(
                            StarterAuthInterceptor.CONTEXT_HEADER,
                            token("alpha", "101", List.of("alpha-workflow"), "workflow"));
                    request.addHeader("X-Project-Id", "102");
                    request.setParameter("projectId", "102");
                    request.setContent("{\"projectId\":\"102\"}".getBytes(StandardCharsets.UTF_8));
                    var response = new MockHttpServletResponse();
                    var handler = new Object();
                    try {
                        assertThat(interceptor.preHandle(request, response, handler))
                                .isTrue();
                        assertThat(StarterContext.require().projectId()).isEqualTo("101");
                        assertThat(StarterContext.require().admission()).isEqualTo(ProjectAdmission.DRAIN);
                    } finally {
                        interceptor.afterCompletion(request, response, handler, null);
                    }
                    assertThat(StarterContext.current()).isNull();
                    assertThat(verifier.verify(token("beta", "102", List.of("beta-workflow"), null))
                                    .admission())
                            .isEqualTo(ProjectAdmission.READ_ONLY);
                });
    }

    /** 未登记、状态拒绝及任何项目、服务、受众错配均失败关闭。 */
    @Test
    void rejectsMissingUnknownDeniedAndMismatchedBindings() {
        var verifier = verifier(code -> switch (code) {
            case "alpha" -> registration(ProjectAdmission.FULL);
            case "disabled" -> registration(ProjectAdmission.DENY);
            default -> Optional.empty();
        });
        assertThat(verifier.verify(token("alpha", "101", List.of("alpha-workflow"), null))
                        .admission())
                .isEqualTo(ProjectAdmission.FULL);
        for (String invalid : List.of(
                token(null, "101", List.of("alpha-workflow"), null),
                token("alpha", null, List.of("alpha-workflow"), null),
                token("unknown", "101", List.of("alpha-workflow"), null),
                token("disabled", "101", List.of("alpha-workflow"), null),
                token("alpha", "102", List.of("alpha-workflow"), null),
                token("alpha", "101", List.of("other"), null),
                token("alpha", "101", List.of("alpha-workflow", "other"), null),
                token("alpha", "101", List.of("alpha-workflow"), "other"))) {
            assertThatThrownBy(() -> verifier.verify(invalid)).isInstanceOf(ContextVerificationException.class);
        }
    }

    /** 原始受众数组只能有一个值，重复项不能被 JWT 库折叠后放行。 */
    @Test
    void rejectsRepeatedAudienceOnTheWire() {
        long now = Instant.now().getEpochSecond();
        var body =
                """
                {"iss":"kaiwu-gateway-service","sub":"user","sid":"session","jti":"token",
                 "iat":%d,"exp":%d,"projectCode":"alpha","projectId":"101",
                 "aud":["alpha-workflow","alpha-workflow"]}
                """
                        .formatted(now, now + 60);
        var token = Jwts.builder()
                .header()
                .type("kaiwu-context+jwt")
                .and()
                .content(body)
                .signWith(gateway.getPrivate(), Jwts.SIG.RS256)
                .compact();
        try (var verifier = verifier(code -> registration(ProjectAdmission.FULL))) {
            assertThatThrownBy(() -> verifier.verify(token))
                    .isInstanceOf(ContextVerificationException.class)
                    .hasMessage("Context 项目准入被拒");
        }
    }

    /** 无效签名、类型、签发方与过期令牌不会到达 Registry。 */
    @Test
    void verifiesJwtBeforeLookup() {
        var calls = new AtomicInteger();
        var verifier = verifier(code -> {
            calls.incrementAndGet();
            return registration(ProjectAdmission.FULL);
        });
        Instant now = Instant.now();
        for (String invalid : List.of(
                contextToken(
                        "alpha",
                        "101",
                        List.of("alpha-workflow"),
                        null,
                        platform,
                        "kaiwu-context+jwt",
                        "kaiwu-gateway-service",
                        now),
                contextToken(
                        "alpha",
                        "101",
                        List.of("alpha-workflow"),
                        null,
                        gateway,
                        "other",
                        "kaiwu-gateway-service",
                        now),
                contextToken(
                        "alpha", "101", List.of("alpha-workflow"), null, gateway, "kaiwu-context+jwt", "other", now),
                contextToken(
                        "alpha",
                        "101",
                        List.of("alpha-workflow"),
                        null,
                        gateway,
                        "kaiwu-context+jwt",
                        "kaiwu-gateway-service",
                        now.minusSeconds(120)))) {
            assertThatThrownBy(() -> verifier.verify(invalid)).isInstanceOf(ContextVerificationException.class);
        }
        assertThat(calls).hasValue(0);
    }

    /** T6 停用通知使已签发且曾被允许的令牌立即收到 401。 */
    @Test
    void t6RejectsPreviouslyAcceptedTokenAfterInvalidation() throws Exception {
        var admission = new AtomicReference<>(ProjectAdmission.FULL);
        try (var verifier = verifier(code -> registration(admission.get()))) {
            var token = token("alpha", "101", List.of("alpha-workflow"), null);
            assertThat(verifier.verify(token).admission()).isEqualTo(ProjectAdmission.FULL);

            admission.set(ProjectAdmission.DENY);
            verifier.invalidateProjectRegistration("alpha");

            var request = new MockHttpServletRequest();
            request.addHeader(StarterAuthInterceptor.CONTEXT_HEADER, token);
            var response = new MockHttpServletResponse();
            assertThat(new StarterAuthInterceptor(verifier, new ContextPermissionResolver())
                            .preHandle(request, response, new Object()))
                    .isFalse();
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(StarterContext.current()).isNull();
        }
    }

    /** 权威源异常不能回退到单项目配置，HTTP 错误不回显驱动信息。 */
    @Test
    void registryFailureReturns401WithoutDetails() throws Exception {
        var verifier = verifier(code -> {
            throw new IllegalStateException("registry-private-detail");
        });
        var request = new MockHttpServletRequest();
        request.addHeader(
                StarterAuthInterceptor.CONTEXT_HEADER, token("alpha", "101", List.of("alpha-workflow"), null));
        var response = new MockHttpServletResponse();
        assertThat(new StarterAuthInterceptor(verifier, new ContextPermissionResolver())
                        .preHandle(request, response, new Object()))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain("registry-private-detail");
    }

    /** 多项目开关、普通项目和缺少应用标记的签名不能授权启动。 */
    @Test
    void rejectsAbsentProofAppMarkerRegistryOrKnownMode() {
        var valid = proof("primary", platform, "kaiwu-app-deployment+jwt", "workflow");
        runner(valid, "deploy-aaa", DIGEST).run(context -> assertThat(context).hasFailed());
        withRegistry(runner("", "deploy-aaa", DIGEST))
                .run(context -> assertThat(context).hasFailed());
        var noMarker = proof("primary", platform, "kaiwu-app-deployment+jwt", null);
        withRegistry(runner(noMarker, "deploy-aaa", DIGEST))
                .run(context -> assertThat(context).hasFailed());
        withRegistry(runner(valid, "deploy-aaa", DIGEST).withPropertyValues("kaiwu.starter.tenancy=unknown"))
                .run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> new GatewayContextVerifier(properties())).isInstanceOf(IllegalStateException.class);
    }

    /** 双 kid 可轮换，未知 kid、签名错配、类型错配和复制证明均拒绝。 */
    @Test
    void bindsDeploymentDigestAndRotatingPlatformKeys() {
        var valid = proof("primary", platform, "kaiwu-app-deployment+jwt", "workflow");
        withRegistry(runner(valid, "deploy-bbb", DIGEST))
                .run(context -> assertThat(context).hasFailed());
        withRegistry(runner(valid, "deploy-aaa", "b".repeat(64)))
                .run(context -> assertThat(context).hasFailed());
        for (String invalid : List.of(
                proof("primary", gateway, "kaiwu-app-deployment+jwt", "workflow"),
                proof("unknown", platform, "kaiwu-app-deployment+jwt", "workflow"),
                proof("primary", platform, "kaiwu-context+jwt", "workflow"))) {
            withRegistry(runner(invalid, "deploy-aaa", DIGEST))
                    .run(context -> assertThat(context).hasFailed());
        }
        var rotated = proof("next", nextPlatform, "kaiwu-app-deployment+jwt", "workflow");
        withRegistry(runner(rotated, "deploy-aaa", DIGEST))
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** S1 另一个应用的合法证明和不规范的本地应用编码均不能启动。 */
    @Test
    void bindsLocalAppCodeAndRejectsOtherApplicationProof() {
        withRegistry(runner(
                        proof("primary", platform, "kaiwu-app-deployment+jwt", "another-app"), "deploy-aaa", DIGEST))
                .run(context -> assertThat(context).hasFailed());
        for (String appCode : List.of("", "a", "a.b", "Workflow")) {
            withRegistry(runner(
                                    proof("primary", platform, "kaiwu-app-deployment+jwt", "workflow"),
                                    "deploy-aaa",
                                    DIGEST)
                            .withPropertyValues("kaiwu.starter.multi-project.app-code=" + appCode))
                    .run(context -> assertThat(context).hasFailed());
        }
        withRegistry(runner(proof("primary", platform, "kaiwu-app-deployment+jwt", "workflow"), "deploy-aaa", DIGEST)
                        .withPropertyValues("kaiwu.starter.multi-project.app-code="))
                .run(context -> assertThat(context).hasFailed());
    }

    /** S2 未被证明选中的第三把或坏公钥也必须在启动阶段被拒绝。 */
    @Test
    void validatesEveryConfiguredPublicKeyBeforeStarting() throws Exception {
        var valid = proof("primary", platform, "kaiwu-app-deployment+jwt", "workflow");
        withRegistry(runner(valid, "deploy-aaa", DIGEST)
                        .withPropertyValues(
                                "kaiwu.starter.multi-project.deployment-public-keys[third]=" + pem(platform)))
                .run(context -> assertThat(context).hasFailed());
        withRegistry(runner(valid, "deploy-aaa", DIGEST)
                        .withPropertyValues("kaiwu.starter.multi-project.deployment-public-keys[next]=invalid"))
                .run(context -> assertThat(context).hasFailed());
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        withRegistry(runner(valid, "deploy-aaa", DIGEST)
                        .withPropertyValues("kaiwu.starter.multi-project.deployment-public-keys[next]="
                                + pem(generator.generateKeyPair())))
                .run(context -> assertThat(context).hasFailed());
        withRegistry(runner(valid, "deploy-aaa", DIGEST)
                        .withPropertyValues("kaiwu.starter.multi-project.deployment-public-keys[next]=" + pem(gateway)))
                .run(context -> assertThat(context).hasFailed());
    }

    /** 模拟平台安装时注入的部署配置。 */
    private static ApplicationContextRunner runner(String proof, String id, String digest) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KaiwuStarterAutoConfiguration.class))
                .withPropertyValues(
                        "kaiwu.starter.tenancy=multi-project",
                        "kaiwu.starter.context-public-key=" + pem(gateway),
                        "kaiwu.starter.multi-project.service-code=workflow",
                        "kaiwu.starter.multi-project.app-code=workflow",
                        "kaiwu.starter.multi-project.deployment-id=" + id,
                        "kaiwu.starter.multi-project.release-digest=" + digest,
                        "kaiwu.starter.multi-project.deployment-proof=" + proof,
                        "kaiwu.starter.multi-project.deployment-public-keys[primary]=" + pem(platform),
                        "kaiwu.starter.multi-project.deployment-public-keys[next]=" + pem(nextPlatform));
    }

    /** 安装空登记实现，用于隔离启动验证。 */
    private static ApplicationContextRunner withRegistry(ApplicationContextRunner runner) {
        return runner.withBean(TrustedProjectRegistry.class, () -> code -> Optional.empty());
    }

    /** 装配已具备部署资格的验证器。 */
    private static GatewayContextVerifier verifier(TrustedProjectRegistry registry) {
        return new GatewayContextVerifier(
                properties(),
                new MultiProjectProperties(
                        "workflow",
                        "workflow",
                        "deploy-aaa",
                        DIGEST,
                        proof("primary", platform, "kaiwu-app-deployment+jwt", "workflow"),
                        Map.of("primary", pem(platform)),
                        null),
                registry);
    }

    /** 使用与登记不同的旧静态配置，以探测意外回退。 */
    private static KaiwuStarterProperties properties() {
        var properties = new KaiwuStarterProperties();
        properties.setTenancy(KaiwuStarterProperties.Tenancy.MULTI_PROJECT);
        properties.setContextPublicKey(pem(gateway));
        properties.setProjectCode("legacy");
        properties.setAudience("legacy-service");
        return properties;
    }

    /** 模拟权威登记中 alpha 的准入。 */
    private static Optional<Registration> registration(ProjectAdmission admission) {
        return Optional.of(new Registration("101", "alpha-workflow", 1, admission));
    }

    /** 生成正常网关令牌，可指定项目绑定和服务。 */
    private static String token(String code, String id, List<String> audience, String service) {
        return contextToken(
                code, id, audience, service, gateway, "kaiwu-context+jwt", "kaiwu-gateway-service", Instant.now());
    }

    /** 签发可控安全声明以测试无效令牌。 */
    private static String contextToken(
            String code,
            String id,
            List<String> audience,
            String service,
            KeyPair keys,
            String typ,
            String issuer,
            Instant issuedAt) {
        return Jwts.builder()
                .header()
                .type(typ)
                .and()
                .issuer(issuer)
                .subject("user")
                .audience()
                .add(audience)
                .and()
                .id("token")
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plusSeconds(60)))
                .claim("sid", "session")
                .claim("projectCode", code)
                .claim("projectId", id)
                .claim("serviceCode", service)
                .signWith(keys.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /** 模拟独立平台密钥生成长期部署证明。 */
    private static String proof(String kid, KeyPair keys, String typ, String appCode) {
        return Jwts.builder()
                .header()
                .type(typ)
                .keyId(kid)
                .and()
                .issuer("kaiwu-system")
                .issuedAt(Date.from(Instant.now().minusSeconds(86_400)))
                .claim("appCode", appCode)
                .claim("appProjectId", "900")
                .claim("deploymentId", "deploy-aaa")
                .claim("tenancy", "multi-project")
                .claim("releaseDigest", DIGEST)
                .signWith(keys.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /** 受控配置支持 X.509 公钥的 Base64 表示。 */
    private static String pem(KeyPair keys) {
        return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
    }
}
