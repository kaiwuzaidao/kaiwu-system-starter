package com.kaiwu.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.GatewayContextVerifier.DelegationBinding;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 对端点显式使用的C4原语执行反例矩阵，普通及Agent入口仍保持固定受众。 */
class DelegatedContextVerifierTest {
    private static KeyPair keys;
    private static GatewayContextVerifier verifier;
    private static final String SOURCE = "kaiwu-demo.purchase-service";
    private static final DelegationBinding BINDING = new DelegationBinding(
            "purchase", SOURCE, "demo", "workflow", "POST /internal/v1/instances", "instance:start", "purchase-order");

    /** 仅测试内存中生成RSA身份；不写私钥或令牌到证据和日志。 */
    @BeforeAll
    static void setup() {
        keys = Jwts.SIG.RS256.keyPair().build();
        verifier = new GatewayContextVerifier(properties("demo"));
    }

    /** 来源身份保留实际audience和19位字符串ID，不替换为目标服务身份。 */
    @Test
    void preservesVerifiedSourceIdentity() {
        var user = verify(token(b -> {}));
        assertThat(user.audience()).isEqualTo(SOURCE);
        assertThat(user.userId()).isEqualTo("1900000000000000001");
        assertThat(user.projectId()).isEqualTo("1900000000000000100");
        assertThat(user.hasPermission("purchase:order:submit")).isTrue();
        verifier.requireDelegation(BINDING, Set.of(BINDING));
    }

    /** 普通拦截器不会因新增显式方法而接受来源服务令牌，项目头也不能覆盖绑定。 */
    @Test
    void normalRequestRemainsAudienceBound() throws Exception {
        var interceptor = new StarterAuthInterceptor(verifier, new ContextPermissionResolver());
        var request = new MockHttpServletRequest("POST", "/internal/v1/instances");
        request.addHeader(StarterAuthInterceptor.CONTEXT_HEADER, token(b -> {}));
        request.addHeader("X-Project-Id", "1900000000000000100");
        var response = new MockHttpServletResponse();
        try {
            assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(StarterContext.current()).isNull();
        } finally {
            interceptor.afterCompletion(request, response, new Object(), null);
        }
    }

    /** 每一项委托事实均须精确匹配服务器登记，不允许只比较项目或受众。 */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void rejectsEachTupleMismatch(int field) {
        String[] values = {
            "purchase", SOURCE, "demo", "workflow", "POST /internal/v1/instances", "instance:start", "purchase-order"
        };
        values[field] += "-other";
        var observed =
                new DelegationBinding(values[0], values[1], values[2], values[3], values[4], values[5], values[6]);
        assertThatThrownBy(() -> verifier.requireDelegation(observed, Set.of(BINDING)))
                .isInstanceOf(ContextVerificationException.class);
    }

    /** 即使允许集合误含不规范元组，空白或缺失字段也不能通过。 */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void rejectsMalformedRegisteredTuple(int field) {
        String[] values = {
            "purchase", SOURCE, "demo", "workflow", "POST /internal/v1/instances", "instance:start", "purchase-order"
        };
        values[field] = " ";
        var observed =
                new DelegationBinding(values[0], values[1], values[2], values[3], values[4], values[5], values[6]);
        assertThatThrownBy(() -> verifier.requireDelegation(observed, Set.of(observed)))
                .isInstanceOf(ContextVerificationException.class);
    }

    /** 缺可信登记或未绑定项目时失败关闭，不能建立全平台受众通配。 */
    @Test
    void rejectsAbsentTrustedRegistration() {
        String jwt = token(b -> {});
        assertThatThrownBy(() -> verifier.verifyDelegatedSource(jwt, SOURCE, null))
                .isInstanceOf(ContextVerificationException.class);
        assertThatThrownBy(() -> verifier.verifyDelegatedSource(jwt, SOURCE, Set.of()))
                .isInstanceOf(ContextVerificationException.class);
        assertThatThrownBy(() -> verifier.verifyDelegatedSource(jwt, " ", Set.of(" ")))
                .isInstanceOf(ContextVerificationException.class);
        assertThatThrownBy(() ->
                        new GatewayContextVerifier(properties(null)).verifyDelegatedSource(jwt, SOURCE, Set.of(SOURCE)))
                .isInstanceOf(ContextVerificationException.class);
        assertThatThrownBy(() -> verifier.requireDelegation(null, Set.of()))
                .isInstanceOf(ContextVerificationException.class);
        assertThatThrownBy(() -> verifier.requireDelegation(BINDING, null))
                .isInstanceOf(ContextVerificationException.class);
    }

    /** 已签名也不能放宽typ、issuer、单一受众、项目或时间的普通Context约束。 */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "type",
                "issuer",
                "audience",
                "missingAudience",
                "multipleAudiences",
                "project",
                "projectId",
                "subject",
                "session",
                "jti",
                "expired",
                "future",
                "longLifetime"
            })
    void rejectsInvalidSignedClaims(String violation) {
        String jwt = token(b -> {
            switch (violation) {
                case "type" -> b.header().type("kaiwu-agent-action-context+jwt");
                case "issuer" -> b.issuer("kaiwu-system-service");
                case "audience" -> b.audience().clear().add("other-service");
                case "missingAudience" -> b.audience().clear();
                case "multipleAudiences" -> b.audience().add("other-service");
                case "project" -> b.claim("projectCode", "other");
                case "projectId" -> b.claim("projectId", null);
                case "subject" -> b.subject(null);
                case "session" -> b.claim("sid", null);
                case "jti" -> b.id(null);
                case "expired" ->
                    b.issuedAt(Date.from(Instant.now().minusSeconds(120)))
                            .expiration(Date.from(Instant.now().minusSeconds(60)));
                case "future" ->
                    b.issuedAt(Date.from(Instant.now().plusSeconds(120)))
                            .expiration(Date.from(Instant.now().plusSeconds(180)));
                case "longLifetime" -> b.expiration(Date.from(Instant.now().plusSeconds(120)));
                default -> throw new IllegalArgumentException("未知测试场景");
            }
        });
        assertThatThrownBy(() -> verify(jwt)).isInstanceOf(ContextVerificationException.class);
    }

    /** 换签名密钥、无令牌及超限输入都不能借显式入口绕过验签。 */
    @Test
    void rejectsSignatureAndSizeFailures() {
        assertThatThrownBy(() -> verify(
                        token(b -> b.signWith(Jwts.SIG.RS256.keyPair().build().getPrivate(), Jwts.SIG.RS256))))
                .isInstanceOf(ContextVerificationException.class);
        assertThatThrownBy(() -> verify(null)).isInstanceOf(ContextVerificationException.class);
        assertThatThrownBy(() -> verify("x".repeat(17000))).isInstanceOf(ContextVerificationException.class);
    }

    /** 来源Context不能进入Agent固定只读或写用途校验，避免新增受众传播到Agent。 */
    @Test
    void rejectsSourceContextAtAgentEntry() {
        assertThatThrownBy(() -> verifier.verifyAgent(token(b -> {}), "test", "1", "hash", "hash", "correlation"))
                .isInstanceOf(ContextVerificationException.class);
        assertThatThrownBy(() -> verifier.verifyAgentWrite(token(b -> {}), null))
                .isInstanceOf(ContextVerificationException.class);
    }

    /** 创建与目标服务绑定的校验器配置，公钥是本轮测试临时生成。 */
    private static KaiwuStarterProperties properties(String project) {
        var properties = new KaiwuStarterProperties();
        properties.setAudience("kaiwu-demo.workflow-service");
        properties.setProjectCode(project);
        properties.setContextPublicKey("-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
        return properties;
    }

    /** 测试只向服务器固定登记的单一来源受众开放。 */
    private static KaiwuContext verify(String jwt) {
        return verifier.verifyDelegatedSource(jwt, SOURCE, Set.of(SOURCE));
    }

    /** 构造正常签名后逐项变更待验证声明，不让负例依赖无效编译或伪令牌。 */
    private static String token(Consumer<JwtBuilder> mutation) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .header()
                .type(GatewayContextVerifier.CONTEXT_TYPE)
                .and()
                .issuer("kaiwu-gateway-service")
                .audience()
                .add(SOURCE)
                .and()
                .subject("1900000000000000001")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .claim("sid", "session-1")
                .claim("projectId", "1900000000000000100")
                .claim("projectCode", "demo")
                .claim("permissions", List.of("purchase:order:submit"))
                .claim("projectRoleCodes", List.of("project_admin"))
                .claim("authzVersion", "v1")
                .signWith(keys.getPrivate(), Jwts.SIG.RS256);
        mutation.accept(builder);
        return builder.compact();
    }
}
