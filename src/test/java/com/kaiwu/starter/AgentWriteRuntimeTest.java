package com.kaiwu.starter;

import static org.assertj.core.api.Assertions.*;

import com.kaiwu.starter.agent.*;
import com.kaiwu.starter.agent.controller.AgentWriteCapabilityController;
import com.kaiwu.starter.agent.protocol.*;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AgentWriteRuntimeTest {
    private static KeyPair keys;
    private final AtomicInteger previews = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();
    private final Map<AgentWriteAction, String> committed = new ConcurrentHashMap<>();
    private final Set<String> consumed = ConcurrentHashMap.newKeySet();
    private CapabilityManifest manifest = new CapabilityManifest(
            "demo",
            "demo.record.rename",
            "1",
            "重命名记录",
            "demo:record:update",
            DataScope.SELF,
            Map.of("name", new ParamSpec(ParamType.STRING, true, null, null, 30, null, null, null)),
            Set.of("recordId", "name"),
            Map.of(),
            false,
            true,
            ConfirmationMode.REQUIRED,
            1000);
    private GatewayContextVerifier verifier;
    private AgentWriteRuntime runtime;

    @BeforeAll
    static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    @BeforeEach
    void setup() {
        var properties = new KaiwuStarterProperties();
        properties.setAudience("demo-service");
        properties.setProjectCode("demo");
        properties.setContextPublicKey(
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        verifier = new GatewayContextVerifier(properties);
        AgentWriteCapabilityHandler handler = new AgentWriteCapabilityHandler() {
            public CapabilityManifest manifest() {
                return manifest;
            }

            public AgentWritePreview preview(Map<String, Object> parameters, KaiwuContext actor) {
                assertThat(StarterContext.require()).isEqualTo(actor);
                previews.incrementAndGet();
                return new AgentWritePreview(Map.of("name", (String) parameters.get("name")), 1, "{\"revision\":1}");
            }

            public String execute(Map<String, Object> parameters, String preconditions, KaiwuContext actor) {
                assertThat(StarterContext.require()).isEqualTo(actor);
                assertThat(preconditions).isEqualTo("{\"revision\":1}");
                writes.incrementAndGet();
                return "{\"recordId\":\"42\",\"name\":\"changed\",\"secret\":\"must-not-leak\"}";
            }
        };
        handler = adapt(handler);
        manifest = handler.manifest();
        runtime = new AgentWriteRuntime(
                List.of(handler),
                "demo",
                "123",
                verifier,
                (jti, expiry) -> consumed.add(jti),
                new AgentWriteLedger() {
                    public String executeOnce(AgentWriteAction action, java.util.function.Supplier<String> mutation) {
                        return committed.computeIfAbsent(action, ignored -> mutation.get());
                    }

                    public Optional<String> result(AgentWriteAction action) {
                        return Optional.ofNullable(committed.get(action));
                    }
                },
                new ContextPermissionResolver());
    }

    AgentWriteCapabilityHandler adapt(AgentWriteCapabilityHandler handler) {
        return handler;
    }

    @AfterEach
    void close() {
        runtime.close();
        assertThat(StarterContext.current()).isNull();
    }

    private AgentWriteInvocation invocation(String operation) {
        boolean preview = "PREVIEW".equals(operation);
        return new AgentWriteInvocation(
                manifest.code(),
                manifest.version(),
                AgentExecutionCodec.manifestHash(manifest),
                "{\"name\":\"changed\"}",
                preview ? null : "{\"revision\":1}",
                preview ? null : "a".repeat(64),
                preview ? null : "9b7023f3-84bf-48db-ae92-dd6e84a48b6c",
                preview ? null : Instant.now().plusSeconds(120).toEpochMilli(),
                "trace-1");
    }

    private String token(String operation, AgentWriteInvocation request, Map<String, Object> overrides) {
        return token(operation, request, overrides, "kaiwu-agent-write-context+jwt");
    }

    private String token(
            String operation, AgentWriteInvocation request, Map<String, Object> overrides, String contextType) {
        var claims = new HashMap<String, Object>();
        claims.put("sid", "session-1");
        claims.put("projectId", "123");
        claims.put("projectCode", "demo");
        claims.put("permissions", List.of(manifest.permission()));
        claims.put("operation", operation);
        claims.put("capability", request.code());
        claims.put("version", request.version());
        claims.put("manifestHash", request.manifestHash());
        claims.put("paramsHash", AgentExecutionCodec.digest(request.paramsJson()));
        if (request.preconditionsJson() != null)
            claims.put("preconditionsHash", AgentExecutionCodec.digest(request.preconditionsJson()));
        if (request.previewHash() != null) claims.put("previewHash", request.previewHash());
        if (request.actionId() != null) claims.put("actionId", request.actionId());
        if (request.actionExpiresAt() != null) claims.put("actionExpiresAt", request.actionExpiresAt());
        claims.put("correlationId", request.correlationId());
        claims.put("readOnly", !"EXECUTE".equals(operation));
        claims.put("confirmed", !"PREVIEW".equals(operation));
        claims.putAll(overrides);
        return Jwts.builder()
                .header()
                .type(contextType)
                .and()
                .issuer("kaiwu-gateway-service")
                .audience()
                .add("demo-service")
                .and()
                .subject("user-1")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(45)))
                .claims(claims)
                .signWith(keys.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    @Test
    void previewExecuteAndResultHaveSeparatePurposeAndProjectOutput() {
        var preview = invocation("PREVIEW");
        String previewToken = token("PREVIEW", preview, Map.of());
        assertThat(new String(runtime.invoke("PREVIEW", preview.code(), preview, previewToken), StandardCharsets.UTF_8))
                .contains("revision", "changed");
        assertThatThrownBy(() -> runtime.invoke("EXECUTE", preview.code(), preview, previewToken))
                .isInstanceOf(IllegalArgumentException.class);
        var execute = invocation("EXECUTE");
        String executeToken = token("EXECUTE", execute, Map.of());
        assertThat(new String(runtime.invoke("EXECUTE", execute.code(), execute, executeToken), StandardCharsets.UTF_8))
                .contains("SUCCEEDED", "changed")
                .doesNotContain("secret", "must-not-leak");
        assertThatThrownBy(() -> runtime.invoke("EXECUTE", execute.code(), execute, executeToken))
                .isInstanceOf(ContextVerificationException.class);
        assertThatThrownBy(() -> runtime.invoke("RESULT", execute.code(), execute, executeToken))
                .isInstanceOf(ContextVerificationException.class);
        assertThat(new String(
                        runtime.invoke("RESULT", execute.code(), execute, token("RESULT", execute, Map.of())),
                        StandardCharsets.UTF_8))
                .contains("SUCCEEDED", "changed")
                .doesNotContain("secret");
        assertThat(previews).hasValue(1);
        assertThat(writes).hasValue(1);
    }

    @Test
    void rejectsClaimSubstitutionAndMissingPermissionBeforeWrite() {
        var request = invocation("EXECUTE");
        for (var override : List.of(
                Map.<String, Object>of("operation", "RESULT"),
                Map.<String, Object>of("confirmed", false),
                Map.<String, Object>of("readOnly", true),
                Map.<String, Object>of("projectId", "124"),
                Map.<String, Object>of("permissions", List.of()),
                Map.<String, Object>of("preconditionsHash", "b".repeat(64)),
                Map.<String, Object>of("previewHash", "b".repeat(64))))
            assertThatThrownBy(() ->
                            runtime.invoke("EXECUTE", request.code(), request, token("EXECUTE", request, override)))
                    .isInstanceOf(ContextVerificationException.class);
        assertThat(writes).hasValue(0);
        assertThat(consumed).isEmpty();
    }

    @Test
    void resultSurvivesHandlerVersionReplacementButStillRequiresExactBinding() {
        var request = invocation("EXECUTE");
        runtime.invoke("EXECUTE", request.code(), request, token("EXECUTE", request, Map.of()));
        var replacement = new CapabilityManifest(
                manifest.projectCode(),
                manifest.code(),
                "2",
                manifest.description(),
                manifest.permission(),
                manifest.dataScope(),
                manifest.params(),
                manifest.outboundFields(),
                manifest.redact(),
                false,
                true,
                ConfirmationMode.REQUIRED,
                1000);
        var retired = new AgentWriteRuntime(
                List.of(new AgentWriteCapabilityHandler() {
                    public CapabilityManifest manifest() {
                        return replacement;
                    }

                    public AgentWritePreview preview(Map<String, Object> parameters, KaiwuContext actor) {
                        throw new AssertionError("Old preview must not run");
                    }

                    public String execute(Map<String, Object> parameters, String preconditions, KaiwuContext actor) {
                        throw new AssertionError("Old execute must not run");
                    }
                }),
                "demo",
                "123",
                verifier,
                (jti, expiry) -> consumed.add(jti),
                new AgentWriteLedger() {
                    public String executeOnce(AgentWriteAction action, java.util.function.Supplier<String> mutation) {
                        throw new AssertionError("RESULT must not execute");
                    }

                    public Optional<String> result(AgentWriteAction action) {
                        return Optional.ofNullable(committed.get(action));
                    }
                },
                new ContextPermissionResolver());
        try {
            assertThat(new String(
                            retired.invoke("RESULT", request.code(), request, token("RESULT", request, Map.of())),
                            StandardCharsets.UTF_8))
                    .contains("SUCCEEDED", "changed");
            var substituted = new AgentWriteInvocation(
                    request.code(),
                    request.version(),
                    request.manifestHash(),
                    "{\"name\":\"different\"}",
                    request.preconditionsJson(),
                    request.previewHash(),
                    request.actionId(),
                    request.actionExpiresAt(),
                    request.correlationId());
            assertThatThrownBy(() -> retired.invoke(
                            "RESULT", substituted.code(), substituted, token("RESULT", request, Map.of())))
                    .isInstanceOf(ContextVerificationException.class);
        } finally {
            retired.close();
        }
        assertThat(writes).hasValue(1);
    }

    @Test
    void ordinaryAndReadTokensCannotCallWriteOrOrdinaryRoutes() throws Exception {
        var request = invocation("EXECUTE");
        String signed = token("EXECUTE", request, Map.of());
        String read = token("EXECUTE", request, Map.of(), "kaiwu-agent-action-context+jwt");
        assertThatThrownBy(() -> runtime.invoke("EXECUTE", request.code(), request, read))
                .isInstanceOf(ContextVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(signed)).isInstanceOf(ContextVerificationException.class);
        var servlet = new MockHttpServletRequest();
        servlet.addHeader(StarterAuthInterceptor.CONTEXT_HEADER, signed);
        var response = new MockHttpServletResponse();
        assertThat(new StarterAuthInterceptor(verifier, new ContextPermissionResolver())
                        .preHandle(servlet, response, this))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        var controller = new AgentWriteCapabilityController(runtime);
        servlet.setContent(AgentExecutionCodec.encode(request));
        assertThat(controller.preview(request.code(), servlet).getStatusCode().value())
                .isEqualTo(400);
        var fresh = new MockHttpServletRequest();
        fresh.setContent(AgentExecutionCodec.encode(request));
        fresh.addHeader(StarterAuthInterceptor.CONTEXT_HEADER, signed);
        assertThat(controller.execute(request.code(), fresh).getStatusCode().value())
                .isEqualTo(200);
        var direct = new MockHttpServletRequest();
        direct.setContent(AgentExecutionCodec.encode(request));
        direct.addHeader("Authorization", "Bearer untrusted");
        assertThat(controller.execute(request.code(), direct).getStatusCode().value())
                .isEqualTo(401);
    }
}
