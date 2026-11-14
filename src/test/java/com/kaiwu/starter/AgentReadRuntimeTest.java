package com.kaiwu.starter;

import static org.assertj.core.api.Assertions.*;

import com.kaiwu.starter.agent.*;
import com.kaiwu.starter.agent.controller.AgentCapabilityController;
import com.kaiwu.starter.agent.protocol.*;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AgentReadRuntimeTest {
    private static KeyPair keys;
    private GatewayContextVerifier verifier;
    private AgentReadRuntime runtime;
    private final AtomicInteger calls = new AtomicInteger();
    private final Set<String> consumed = ConcurrentHashMap.newKeySet();
    private final CapabilityManifest manifest = new CapabilityManifest(
            "demo",
            "demo.record.list",
            "1",
            "查询记录",
            "demo:record:list",
            DataScope.SELF,
            Map.of(
                    "keyword",
                    new ParamSpec(ParamType.STRING, true, null, null, 20, null, null, null),
                    "pageSize",
                    ParamSpec.pageSize(50, 10)),
            Set.of("name"),
            Map.of(),
            true,
            true,
            ConfirmationMode.NONE,
            1000);

    @BeforeAll
    static void key() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    @BeforeEach
    void setup() {
        var props = new KaiwuStarterProperties();
        props.setAudience("demo-service");
        props.setProjectCode("demo");
        props.setContextPublicKey(
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        verifier = new GatewayContextVerifier(props);
        runtime = runtime((id, expiry) -> consumed.add(id), new AgentReadCapabilityHandler() {
            public CapabilityManifest manifest() {
                return manifest;
            }

            public List<Map<String, Object>> query(Map<String, Object> parameters, KaiwuContext actor) {
                assertThat(StarterContext.require()).isEqualTo(actor);
                assertThat(parameters).containsEntry("pageSize", 10);
                calls.incrementAndGet();
                return List.of(Map.of("name", "可见数据", "secret", "should-not-leave-service"));
            }
        });
    }

    @AfterEach
    void close() {
        runtime.close();
        assertThat(StarterContext.current()).isNull();
    }

    private AgentReadRuntime runtime(AgentReplayStore store, AgentReadCapabilityHandler handler) {
        return new AgentReadRuntime(List.of(handler), "demo", "123", verifier, store, new ContextPermissionResolver());
    }

    private AgentInvocation request(String json) {
        return new AgentInvocation(
                manifest.code(), manifest.version(), AgentExecutionCodec.manifestHash(manifest), json, "request-1");
    }

    private String token(AgentInvocation request, Map<String, Object> overrides) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sid", "session-1");
        claims.put("projectId", "123");
        claims.put("projectCode", "demo");
        claims.put("permissions", List.of(manifest.permission()));
        claims.put("capability", request.code());
        claims.put("version", request.version());
        claims.put("manifestHash", request.manifestHash());
        claims.put("paramsHash", AgentExecutionCodec.digest(request.paramsJson()));
        claims.put("correlationId", request.correlationId());
        claims.put("readOnly", true);
        claims.putAll(overrides);
        return Jwts.builder()
                .header()
                .type("kaiwu-agent-action-context+jwt")
                .and()
                .issuer("kaiwu-gateway-service")
                .audience()
                .add("demo-service")
                .and()
                .subject("user-1")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .claims(claims)
                .signWith(keys.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    @Test
    void matchesSystemManifestDigest() throws Exception {
        try (var input = getClass().getResourceAsStream("/agent-registration-v1.json")) {
            var manifest = tools.jackson.databind.json.JsonMapper.builder()
                    .build()
                    .readValue(input.readAllBytes(), CapabilityManifest.class);
            assertThat(AgentExecutionCodec.manifestHash(manifest))
                    .isEqualTo("85d4800dcf4e0f2656cd1e2f2d9f0ea8f684d48ecd50a7f4cef3a0d573eddf97");
        }
    }

    @Test
    void invokesDeclaredHandlerProjectsOutputAndRejectsReplay() {
        var req = request("{\"keyword\":\"test\"}");
        var jwt = token(req, Map.of());
        assertThat(new String(runtime.invoke(req.code(), req, jwt), java.nio.charset.StandardCharsets.UTF_8))
                .contains("可见数据")
                .doesNotContain("secret", "should-not-leave-service");
        assertThatThrownBy(() -> runtime.invoke(req.code(), req, jwt)).isInstanceOf(ContextVerificationException.class);
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "projectId",
                "projectCode",
                "capability",
                "version",
                "manifestHash",
                "paramsHash",
                "correlationId",
                "sid"
            })
    void refusesWrongBindings(String claim) {
        var req = request("{\"keyword\":\"test\"}");
        assertThatThrownBy(() -> runtime.invoke(req.code(), req, token(req, Map.of(claim, ""))))
                .isInstanceOf(ContextVerificationException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsMissingPermissionAndWriteClaim() {
        var req = request("{\"keyword\":\"test\"}");
        for (var override :
                List.of(Map.<String, Object>of("permissions", List.of()), Map.<String, Object>of("readOnly", false)))
            assertThatThrownBy(() -> runtime.invoke(req.code(), req, token(req, override)))
                    .isInstanceOf(ContextVerificationException.class);
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"keyword\":null}",
                "{\"keyword\":{}}",
                "{\"keyword\":\"test\",\"userId\":\"other\"}",
                "{\"keyword\":\"test\",\"pageSize\":1.5}",
                "{\"keyword\":\"test\",\"pageSize\":999999999999}",
                "{\"keyword\":\"a\",\"keyword\":\"b\"}",
                "{\"keyword\":\"test\"} {}"
            })
    void refusesInvalidParametersWithoutExecuting(String json) {
        var req = request(json);
        assertThatThrownBy(() -> runtime.invoke(req.code(), req, token(req, Map.of())))
                .isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(0);
        assertThat(consumed).isEmpty();
    }

    @Test
    void boundedStringListRejectsWrongShapeDuplicatesAndOversizedItems() {
        assertThatThrownBy(() -> ParamSpec.stringList(true, 0, 101, 19)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParamSpec.stringList(true, 3, 2, 19)).isInstanceOf(IllegalArgumentException.class);
        var listManifest = new CapabilityManifest(
                "demo",
                "demo.role.grant",
                "1",
                "授权角色",
                "demo:role:grant",
                DataScope.ALL,
                Map.of("menuIds", ParamSpec.stringList(true, 0, 3, 19)),
                Set.of("id"),
                Map.of(),
                false,
                true,
                ConfirmationMode.REQUIRED,
                1000);
        assertThat(AgentReadRuntime.validate(listManifest, Map.of("menuIds", List.of("11", "12"))))
                .containsEntry("menuIds", List.of("11", "12"));
        assertThat(AgentReadRuntime.validate(listManifest, Map.of("menuIds", List.of())))
                .containsEntry("menuIds", List.of());
        for (Object invalid : List.of(
                "11,12",
                List.of("11", "11"),
                List.of(" 11"),
                List.of(""),
                List.of("11", "12", "13", "14"),
                List.of("12345678901234567890"),
                List.of(11))) {
            assertThatThrownBy(() -> AgentReadRuntime.validate(listManifest, Map.of("menuIds", invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void ordinaryVerifierAndRoutesRejectAgentContext() throws Exception {
        var req = request("{\"keyword\":\"test\"}");
        var jwt = token(req, Map.of());
        assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(ContextVerificationException.class);
        var servlet = new MockHttpServletRequest();
        servlet.addHeader("X-Kaiwu-Context", jwt);
        var response = new MockHttpServletResponse();
        assertThat(new StarterAuthInterceptor(verifier, new ContextPermissionResolver())
                        .preHandle(servlet, response, this))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void fixedControllerRejectsOrdinaryContextAndAuthorizationHeader() throws Exception {
        var req = request("{\"keyword\":\"test\"}");
        String ordinary = Jwts.builder()
                .header()
                .type("kaiwu-context+jwt")
                .and()
                .issuer("kaiwu-gateway-service")
                .audience()
                .add("demo-service")
                .and()
                .subject("user-1")
                .id("ordinary")
                .claim("sid", "session-1")
                .claim("projectId", "123")
                .claim("projectCode", "demo")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(keys.getPrivate(), Jwts.SIG.RS256)
                .compact();
        var controller = new AgentCapabilityController(runtime);
        var servlet = new MockHttpServletRequest();
        servlet.setContent(AgentExecutionCodec.encode(req));
        servlet.addHeader("X-Kaiwu-Context", ordinary);
        assertThat(controller.invoke(req.code(), servlet).getStatusCode().value())
                .isEqualTo(401);
        var direct = new MockHttpServletRequest();
        direct.addHeader("Authorization", "Bearer untrusted");
        assertThat(controller.invoke(req.code(), direct).getStatusCode().value())
                .isEqualTo(401);
        assertThat(calls).hasValue(0);
    }

    @Test
    void concurrencyConsumesOneContextOnly() throws Exception {
        var req = request("{\"keyword\":\"test\"}");
        var jwt = token(req, Map.of());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 16; i++)
                results.add(pool.submit(() -> {
                    try {
                        runtime.invoke(req.code(), req, jwt);
                        return true;
                    } catch (ContextVerificationException denied) {
                        return false;
                    }
                }));
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(1);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void replayStorageFailureNeverExecutes() {
        var handler = new AgentReadCapabilityHandler() {
            public CapabilityManifest manifest() {
                return manifest;
            }

            public List<Map<String, Object>> query(Map<String, Object> p, KaiwuContext a) {
                calls.incrementAndGet();
                return List.of();
            }
        };
        try (var unavailable = runtime(
                (id, expiry) -> {
                    throw new IllegalStateException("unavailable");
                },
                handler)) {
            var req = request("{\"keyword\":\"test\"}");
            assertThatThrownBy(() -> unavailable.invoke(req.code(), req, token(req, Map.of())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(calls).hasValue(0);
        }
    }
}
