package com.kaiwu.starter.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.GatewayContextVerifier;
import com.kaiwu.starter.KaiwuStarterAutoConfiguration;
import com.kaiwu.starter.KaiwuStarterProperties;
import com.kaiwu.starter.StarterContext;
import com.kaiwu.starter.auth.config.MultiProjectProperties;
import com.kaiwu.starter.auth.exception.LifecycleVerificationException;
import com.kaiwu.starter.auth.model.LifecycleEndpoint;
import com.kaiwu.starter.auth.model.LifecycleRequest;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/** 对 ADR 0045 报文 v1、R1–R4 与防重放顺序执行真实 RSA 验签测试。 */
class LifecycleAssertionVerifierTest {
    private static final long NOW = 1_793_754_000L;
    private static final String DEPLOYMENT = "deploy-aaa";
    private static final String PROJECT = "/internal/apps/projects/sample-business/enablement";
    private static final String BLOCKERS = "/internal/apps/uninstall-blockers";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static KeyPair platform;
    private static KeyPair nextPlatform;
    private static KeyPair gateway;
    private static final AtomicInteger IDS = new AtomicInteger();

    /** 测试密钥均来自内存，独立于任何真实平台部署。 */
    @BeforeAll
    static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        platform = generator.generateKeyPair();
        nextPlatform = generator.generateKeyPair();
        gateway = generator.generateKeyPair();
    }

    /** §4a 的空正文摘要和声明样例可互通，read 身份不建立用户 Context。 */
    @Test
    void acceptsWireExampleAndRetainsNoncePastAllReplicaClocks() throws Exception {
        var store = new InMemoryLifecycleNonceStore();
        var claims = claims("GET", BLOCKERS, new byte[0]);
        claims.put("jti", "AAECAwQFBgcICQoLDA0ODw");
        assertThat(claims.get("bsh")).isEqualTo("47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU");
        var result = verifier(store).verify(request(sign(claims), "GET", BLOCKERS, new byte[0], null));
        assertThat(result.operationId()).isEqualTo("op-01");
        assertThat(result.projectId()).isNull();
        assertThat(result.projectCode()).isNull();
        assertThat(result.scope()).isEqualTo("app:lifecycle:read");
        assertThat(result.expiresAt()).isEqualTo(Instant.ofEpochSecond(NOW + 30));
        assertThat(store.retention(DEPLOYMENT, "AAECAwQFBgcICQoLDA0ODw")).isEqualTo(Instant.ofEpochSecond(NOW + 40));
        assertThat(StarterContext.current()).isNull();
    }

    /** read、write 的六个方法路径组合均精确命中，项目身份来自签名及可信登记。 */
    @Test
    void acceptsEveryWhitelistedEndpointAndBodyIdentity() throws Exception {
        var store = new InMemoryLifecycleNonceStore();
        for (var endpoint : LifecycleEndpoint.standardEndpoints()) {
            var target = endpoint.pathTemplate().replace("{projectCode}", "sample-business");
            boolean project = target.contains("/projects/");
            byte[] body = project ? bytes("{\"operationId\":\"op-01\",\"projectId\":\"9001\"}") : new byte[0];
            var result = verifier(store)
                    .verify(request(
                            sign(claims(endpoint.method(), target, body)),
                            endpoint.method(),
                            target,
                            body,
                            project ? "9001" : null));
            assertThat(result.scope()).isEqualTo(endpoint.scope());
            assertThat(result.projectId()).isEqualTo(project ? "9001" : null);
            assertThat(result.projectCode()).isEqualTo(project ? "sample-business" : null);
        }
        assertThat(store.size()).isEqualTo(6);
    }

    /** R3 非保留字节和 UTF-8 cursor 使用唯一百分号编码，分页参数可独立省略。 */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "?limit=1",
                "?limit=500",
                "?cursor=next-page",
                "?cursor=x&limit=10",
                "?cursor=%E4%B8%AD%20%2F&limit=20"
            })
    void acceptsCanonicalPagination(String query) throws Exception {
        var target = BLOCKERS + query;
        var result = verifier(new InMemoryLifecycleNonceStore())
                .verify(request(sign(claims("GET", target, new byte[0])), "GET", target, new byte[0], null));
        assertThat(result.scope()).isEqualTo("app:lifecycle:read");
    }

    /** S4 指定的非规范 htu 即使签名完全匹配也不能授权。 */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/internal/%61pps/uninstall-blockers",
                "/internal/apps/../apps/uninstall-blockers",
                "/internal//apps/uninstall-blockers",
                "/internal/apps/uninstall-blockers/",
                "/internal/apps/uninstall-blockers;x=1",
                "/internal\\apps/uninstall-blockers",
                "https://app/internal/apps/uninstall-blockers",
                "/internal/apps/uninstall-blockers#fragment",
                "/internal/apps/uninstall-blockers?",
                "/internal/apps/uninstall-blockers?cursor=",
                "/internal/apps/uninstall-blockers?cursor=&limit=10",
                "/internal/apps/uninstall-blockers?limit=10&cursor=",
                "/internal/apps/uninstall-blockers?limit=10&cursor=x",
                "/internal/apps/uninstall-blockers?cursor=x&cursor=y",
                "/internal/apps/uninstall-blockers?limit=01",
                "/internal/apps/uninstall-blockers?limit=0",
                "/internal/apps/uninstall-blockers?limit=501",
                "/internal/apps/uninstall-blockers?limit=+1",
                "/internal/apps/uninstall-blockers?limit=1.0",
                "/internal/apps/uninstall-blockers?unknown=x",
                "/internal/apps/uninstall-blockers?cursor=%78",
                "/internal/apps/uninstall-blockers?cursor=%2f",
                "/internal/apps/uninstall-blockers?cursor=two+words",
                "/internal/apps/uninstall-blockers?cursor=%FF",
                "/internal/apps/uninstall-blockers?cursor=%C0%AF",
                "/internal/apps/uninstall-blockers?cursor=%",
                "/internal/apps/uninstall-blockers?cursor=中",
                "/internal/apps/uninstall-blockers?cursor=x&&limit=1",
                "/internal/apps/uninstall-blockers?cursor=x&",
                "/internal/apps/projects/sample-business/enablement?limit=1",
                "/internal/apps/projects/sample.business/enablement",
                "/internal/apps/projects/a/enablement",
                "/internal/apps/projects/UPPER/enablement",
                "/internal/apps/projects/sample-business/extra/enablement"
            })
    void rejectsNoncanonicalTargetsBeforeNonce(String target) throws Exception {
        var store = new InMemoryLifecycleNonceStore();
        reject(
                verifier(store),
                request(sign(claims("GET", target, new byte[0])), "GET", target, new byte[0], null),
                HttpStatus.UNAUTHORIZED);
        assertThat(store.size()).isZero();
    }

    /** cursor 长度以解码后的 Unicode 字符数计算，上限 256。 */
    @Test
    void rejectsOverlongCursor() throws Exception {
        var target = BLOCKERS + "?cursor=" + "a".repeat(257);
        reject(
                verifier(new InMemoryLifecycleNonceStore()),
                request(sign(claims("GET", target, new byte[0])), "GET", target, new byte[0], null),
                HttpStatus.UNAUTHORIZED);
    }

    /** 时间边界逐秒覆盖，接受 iat=now+5 与 now=exp+4。 */
    @Test
    void enforcesExactIntegerTimeWindow() throws Exception {
        for (long[] window :
                List.of(new long[] {NOW, NOW + 30}, new long[] {NOW + 5, NOW + 6}, new long[] {NOW - 5, NOW - 4})) {
            var claims = claims("GET", BLOCKERS, new byte[0]);
            claims.put("iat", window[0]);
            claims.put("exp", window[1]);
            verifier(new InMemoryLifecycleNonceStore())
                    .verify(request(sign(claims), "GET", BLOCKERS, new byte[0], null));
        }
        for (Object[] window : List.of(
                new Object[] {NOW, NOW},
                new Object[] {NOW, NOW - 1},
                new Object[] {NOW, NOW + 31},
                new Object[] {NOW + 6, NOW + 7},
                new Object[] {NOW - 6, NOW - 5},
                new Object[] {String.valueOf(NOW), NOW + 1},
                new Object[] {NOW + 0.5, NOW + 1},
                new Object[] {NOW, String.valueOf(NOW + 1)},
                new Object[] {Long.MAX_VALUE, Long.MIN_VALUE})) {
            var claims = claims("GET", BLOCKERS, new byte[0]);
            claims.put("iat", window[0]);
            claims.put("exp", window[1]);
            reject(
                    verifier(new InMemoryLifecycleNonceStore()),
                    request(sign(claims), "GET", BLOCKERS, new byte[0], null),
                    HttpStatus.UNAUTHORIZED);
        }
    }

    /** 每个必要声明的缺失、类型错误及错配均不触及 nonce。 */
    @Test
    void rejectsMissingAndMismatchedClaims() throws Exception {
        for (String field : List.of(
                "iss",
                "sub",
                "aud",
                "appCode",
                "deploymentId",
                "iat",
                "exp",
                "jti",
                "scope",
                "operationId",
                "htm",
                "htu",
                "bsh")) {
            invalidClaims(claims -> claims.remove(field));
        }
        for (Map.Entry<String, Object> entry : Map.<String, Object>ofEntries(
                        Map.entry("iss", "other"),
                        Map.entry("sub", "user"),
                        Map.entry("aud", "kaiwu-app-lifecycle.another-app." + DEPLOYMENT),
                        Map.entry("appCode", "another-app"),
                        Map.entry("deploymentId", "another-deploy"),
                        Map.entry("scope", "app:lifecycle:write"),
                        Map.entry("operationId", " "),
                        Map.entry("htm", "get"),
                        Map.entry("htu", PROJECT),
                        Map.entry("bsh", "x".repeat(43)),
                        Map.entry("jti", "x".repeat(21)),
                        Map.entry("projectId", "9001"))
                .entrySet()) {
            invalidClaims(claims -> claims.put(entry.getKey(), entry.getValue()));
        }
        invalidClaims(claims -> claims.put("aud", List.of("kaiwu-app-lifecycle.workflow." + DEPLOYMENT)));
        invalidClaims(claims -> claims.put("scope", List.of("app:lifecycle:read")));
        invalidClaims(claims -> claims.put("operationId", 12));
        invalidClaims(claims -> claims.put("jti", "AAECAwQFBgcICQoLDA0ODx"));
        invalidClaims(claims -> claims.put("bsh", "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFV"));
    }

    /** 端点和请求绑定先校验，read 不能写，write 不能读或访问业务 API。 */
    @Test
    void rejectsMethodScopeTargetAndBodyChanges() throws Exception {
        var store = new InMemoryLifecycleNonceStore();
        var verifier = verifier(store);
        String read = sign(claims("GET", PROJECT, new byte[0]));
        reject(verifier, request(read, "PUT", PROJECT, new byte[0], "9001"), HttpStatus.UNAUTHORIZED);
        String write = sign(claims("PUT", PROJECT, new byte[0]));
        reject(verifier, request(write, "GET", PROJECT, new byte[0], "9001"), HttpStatus.UNAUTHORIZED);
        reject(
                verifier,
                request(write, "PUT", PROJECT.replace("sample-business", "other-project"), new byte[0], "9001"),
                HttpStatus.UNAUTHORIZED);
        reject(verifier, request(write, "PUT", PROJECT, bytes("{}"), "9001"), HttpStatus.UNAUTHORIZED);
        for (String target : List.of(
                "/api/workflow/instances",
                "/internal/apps/anything",
                "/internal/apps/projects/sample-business/freeze-extra")) {
            var forged = claims("POST", target, new byte[0]);
            forged.put("scope", "app:lifecycle:write");
            reject(verifier, request(sign(forged), "POST", target, new byte[0], null), HttpStatus.UNAUTHORIZED);
        }
        assertThat(store.size()).isZero();
    }

    /** 已有项目登记与正文重复身份均需精确相等，不把任意 JSON 数字转换为项目 ID。 */
    @Test
    void rejectsProjectAndBodyIdentityMismatch() throws Exception {
        var verifier = verifier(new InMemoryLifecycleNonceStore());
        reject(
                verifier,
                request(sign(claims("PUT", PROJECT, new byte[0])), "PUT", PROJECT, new byte[0], "9002"),
                HttpStatus.UNAUTHORIZED);
        for (Object id : List.of("0", "09001", "-1", "9.1", 9001)) {
            var claims = claims("PUT", PROJECT, new byte[0]);
            claims.put("projectId", id);
            reject(verifier, request(sign(claims), "PUT", PROJECT, new byte[0], null), HttpStatus.UNAUTHORIZED);
        }
        var missing = claims("GET", PROJECT, new byte[0]);
        missing.remove("projectId");
        reject(verifier, request(sign(missing), "GET", PROJECT, new byte[0], null), HttpStatus.UNAUTHORIZED);
        for (String raw : List.of(
                "{\"projectId\":\"9002\"}",
                "{\"projectId\":9001}",
                "{\"operationId\":\"other\"}",
                "{\"operationId\":\"op-01\",\"operationId\":\"op-01\"}")) {
            var body = bytes(raw);
            reject(
                    verifier,
                    request(sign(claims("PUT", PROJECT, body)), "PUT", PROJECT, body, "9001"),
                    HttpStatus.UNAUTHORIZED);
        }
    }

    /** 每次调用只消费一个完整 JWS 头，拒绝前缀、多头、非规范编码和跨类型使用。 */
    @Test
    void rejectsMalformedJoseAndNoncanonicalBase64() throws Exception {
        String valid = sign(claims("GET", BLOCKERS, new byte[0]));
        var store = new InMemoryLifecycleNonceStore();
        var verifier = verifier(store);
        for (List<String> headers : List.of(
                List.<String>of(),
                List.of(valid, valid),
                List.of("Bearer " + valid),
                List.of(valid + "," + valid),
                List.of("x".repeat(16385)))) {
            reject(
                    verifier,
                    new LifecycleRequest("GET", BLOCKERS, new byte[0], headers, List.of(), null),
                    HttpStatus.UNAUTHORIZED);
        }
        var parts = valid.split("\\.");
        for (String invalid : List.of(
                valid + ".extra",
                parts[0] + "=." + parts[1] + "." + parts[2],
                parts[0] + "." + parts[1] + "=." + parts[2],
                parts[0] + "." + parts[1] + "." + parts[2] + "=")) {
            reject(verifier, request(invalid, "GET", BLOCKERS, new byte[0], null), HttpStatus.UNAUTHORIZED);
        }
        var claims = JSON.writeValueAsString(claims("GET", BLOCKERS, new byte[0]));
        for (String header : List.of(
                "{\"typ\":\"kaiwu-context+jwt\",\"alg\":\"RS256\",\"kid\":\"primary\"}",
                "{\"typ\":\"kaiwu-app-deployment+jwt\",\"alg\":\"RS256\",\"kid\":\"primary\"}",
                "{\"typ\":\"kaiwu-app-lifecycle+jwt\",\"alg\":\"HS256\",\"kid\":\"primary\"}",
                "{\"typ\":\"kaiwu-app-lifecycle+jwt\",\"alg\":\"RS256\",\"kid\":\"unknown\"}",
                "{\"typ\":\"kaiwu-app-lifecycle+jwt\",\"alg\":\"RS256\",\"kid\":\"primary\",\"jku\":\"https://untrusted/\"}",
                "{\"typ\":\"kaiwu-app-lifecycle+jwt\",\"alg\":\"RS256\",\"kid\":\"primary\",\"kid\":\"primary\"}")) {
            reject(
                    verifier,
                    request(signRaw(header, bytes(claims), platform), "GET", BLOCKERS, new byte[0], null),
                    HttpStatus.UNAUTHORIZED);
        }
        for (byte[] payload : List.of(
                bytes(claims.replaceFirst("\\{", "{\"iss\":\"kaiwu-system\",")),
                bytes(claims + " {}"),
                new byte[] {(byte) 0xFF},
                bytes("[]"))) {
            reject(
                    verifier,
                    request(signRaw(header("primary"), payload, platform), "GET", BLOCKERS, new byte[0], null),
                    HttpStatus.UNAUTHORIZED);
        }
        reject(
                verifier,
                request(signRaw(header("primary"), bytes(claims), gateway), "GET", BLOCKERS, new byte[0], null),
                HttpStatus.UNAUTHORIZED);
        assertThat(store.size()).isZero();
    }

    /** 内容编码无论是否 identity 都拒绝，正文上限在哈希之前执行，快照不会被外部修改。 */
    @Test
    void enforcesTransportLimitsAndRequestSnapshot() throws Exception {
        String token = sign(claims("PUT", PROJECT, bytes("{}")));
        for (String encoding : List.of("gzip", "identity", "")) {
            reject(
                    verifier(new InMemoryLifecycleNonceStore()),
                    new LifecycleRequest("PUT", PROJECT, bytes("{}"), List.of(token), List.of(encoding), null),
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        assertThatThrownBy(() -> new LifecycleRequest("PUT", PROJECT, new byte[65537], List.of(token), List.of(), null))
                .isInstanceOfSatisfying(LifecycleVerificationException.class, error -> assertThat(error.getStatusCode())
                        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        byte[] body = bytes("{\"operationId\":\"op-01\"}");
        String snapshotToken = sign(claims("PUT", PROJECT, body));
        var request = request(snapshotToken, "PUT", PROJECT, body, "9001");
        body[0] = 0;
        request.body()[0] = 0;
        assertThat(verifier(new InMemoryLifecycleNonceStore()).verify(request).operationId())
                .isEqualTo("op-01");
        assertThat(request.toString()).doesNotContain(token, "op-01");
        reject(
                verifier(new InMemoryLifecycleNonceStore()),
                new LifecycleRequest("GET", null, new byte[0], List.of(token), List.of(), null),
                HttpStatus.UNAUTHORIZED);
    }

    /** 两个校验副本共享唯一存储，同一个 jti 并发只允许一个成功，域失败后仍拒绝重放。 */
    @Test
    void claimsNonceAtomicallyAcrossReplicasAndNeverReleasesIt() throws Exception {
        var store = new InMemoryLifecycleNonceStore();
        var first = verifier(store);
        var second = verifier(store);
        String token = sign(claims("GET", BLOCKERS, new byte[0]));
        var request = request(token, "GET", BLOCKERS, new byte[0], null);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                var verifier = i % 2 == 0 ? first : second;
                tasks.add(() -> {
                    start.await();
                    try {
                        verifier.verify(request);
                        return 200;
                    } catch (LifecycleVerificationException error) {
                        return error.getStatusCode().value();
                    }
                });
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var result : executor.invokeAll(tasks)) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 401, 401, 401, 401, 401, 401, 401, 401, 401, 401, 401);
        }
        reject(first, request, HttpStatus.UNAUTHORIZED);
        // 新签名沿用 operationId 但换 jti，只允许身份进入；领域幂等由应用台账处理。
        assertThat(second.verify(
                                request(sign(claims("GET", BLOCKERS, new byte[0])), "GET", BLOCKERS, new byte[0], null))
                        .operationId())
                .isEqualTo("op-01");
    }

    /** nonce 服务不可用、提交结果未知和空返回都映射 503，并剥离底层异常。 */
    @Test
    void failsClosedOnUnknownNonceOutcome() throws Exception {
        var request = request(sign(claims("GET", BLOCKERS, new byte[0])), "GET", BLOCKERS, new byte[0], null);
        for (LifecycleNonceStore store : List.<LifecycleNonceStore>of(
                (iss, deployment, jti, expiry) -> {
                    throw new IllegalStateException("private-db-password");
                },
                (iss, deployment, jti, expiry) -> LifecycleNonceStore.ClaimResult.UNKNOWN,
                (iss, deployment, jti, expiry) -> null)) {
            reject(verifier(store), request, HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /** 两代密钥可重叠，撤旧后旧断言拒绝，公钥集合的每个条目均在启动时校验。 */
    @Test
    void validatesKeySetAndRotatesKids() throws Exception {
        String next = signRaw(
                header("next"), bytes(JSON.writeValueAsString(claims("GET", BLOCKERS, new byte[0]))), nextPlatform);
        verifier(new InMemoryLifecycleNonceStore()).verify(request(next, "GET", BLOCKERS, new byte[0], null));
        var newOnly = verifier(properties(Map.of("next", pem(nextPlatform))), new InMemoryLifecycleNonceStore());
        newOnly.verify(request(next, "GET", BLOCKERS, new byte[0], null));
        reject(
                newOnly,
                request(sign(claims("GET", BLOCKERS, new byte[0])), "GET", BLOCKERS, new byte[0], null),
                HttpStatus.UNAUTHORIZED);
        var weak = KeyPairGenerator.getInstance("RSA");
        weak.initialize(1024);
        var ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        for (Map<String, String> keys : List.of(
                Map.<String, String>of(),
                Map.of(" ", pem(platform)),
                Map.of("primary", pem(platform), "next", pem(nextPlatform), "third", pem(platform)),
                Map.of("primary", pem(platform), "next", "bad-key"),
                Map.of("primary", pem(weak.generateKeyPair())),
                Map.of("primary", pem(ec.generateKeyPair())),
                Map.of("primary", pem(platform), "next", pem(gateway)))) {
            assertThatThrownBy(() -> verifier(properties(keys), new InMemoryLifecycleNonceStore()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** 白名单可以只收窄，不能把业务 API 或错误 scope 加入生命周期通道。 */
    @Test
    void restrictsCustomEndpointTable() throws Exception {
        var store = new InMemoryLifecycleNonceStore();
        var onlyRead = new LifecycleAssertionVerifier(
                properties(),
                starter(),
                store,
                List.of(LifecycleEndpoint.standardEndpoints().getFirst()),
                CLOCK);
        reject(
                onlyRead,
                request(sign(claims("PUT", PROJECT, new byte[0])), "PUT", PROJECT, new byte[0], null),
                HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> new LifecycleAssertionVerifier(
                        properties(),
                        starter(),
                        store,
                        List.of(new LifecycleEndpoint("GET", "/api/data", "app:lifecycle:read")),
                        CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 原自动装配在应用提供 SPI 后暴露校验器，普通单项目业务仍无默认 nonce 实现。 */
    @Test
    void autoConfigurationRequiresExplicitApplicationNonceStore() {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KaiwuStarterAutoConfiguration.class))
                .withPropertyValues(
                        "kaiwu.starter.audience=business-service",
                        "kaiwu.starter.context-public-key=" + pem(gateway),
                        "kaiwu.starter.multi-project.app-code=workflow",
                        "kaiwu.starter.multi-project.deployment-id=" + DEPLOYMENT,
                        "kaiwu.starter.multi-project.deployment-public-keys[primary]=" + pem(platform));
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LifecycleAssertionVerifier.class);
            assertThat(context).hasSingleBean(GatewayContextVerifier.class);
        });
        runner.withBean(LifecycleNonceStore.class, InMemoryLifecycleNonceStore::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LifecycleAssertionVerifier.class);
                });
    }

    /** 真实 MVC 入口将验证器异常映射为 401、503 和传输错误，领域处理只在占用后执行。 */
    @Test
    void mapsFailuresAtHttpEntryBeforeDomainHandling() throws Exception {
        var handled = new AtomicInteger();
        var store = new InMemoryLifecycleNonceStore();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        new LifecycleController(verifier(store), handled))
                .build();
        String token = sign(claims("GET", BLOCKERS, new byte[0]));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BLOCKERS)
                        .header(LifecycleAssertionVerifier.HEADER, token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BLOCKERS)
                        .header(LifecycleAssertionVerifier.HEADER, token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BLOCKERS)
                        .header(LifecycleAssertionVerifier.HEADER, token, token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BLOCKERS)
                        .header(LifecycleAssertionVerifier.HEADER, token)
                        .header("Content-Encoding", "gzip"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isUnsupportedMediaType());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BLOCKERS)
                        .header(LifecycleAssertionVerifier.HEADER, token)
                        .content(new byte[65537]))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isPayloadTooLarge());
        assertThat(handled).hasValue(1);
        var unavailable = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        new LifecycleController(
                                verifier((iss, deployment, jti, expiry) -> {
                                    throw new IllegalStateException("private-db-password");
                                }),
                                handled))
                .build();
        unavailable
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BLOCKERS)
                        .header(LifecycleAssertionVerifier.HEADER, sign(claims("GET", BLOCKERS, new byte[0]))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isServiceUnavailable());
        assertThat(handled).hasValue(1);
    }

    /** 原始读取层限制正文，再将未解码的 URI、全部同名头和原始字节交给 Starter。 */
    @org.springframework.web.bind.annotation.RestController
    static class LifecycleController {
        private final LifecycleAssertionVerifier verifier;
        private final AtomicInteger handled;

        /** 测试应用显式注入校验器，不创建普通业务认证旁路。 */
        LifecycleController(LifecycleAssertionVerifier verifier, AtomicInteger handled) {
            this.verifier = verifier;
            this.handled = handled;
        }

        /** 固定白名单处理器在 nonce 独立占用成功后才执行领域动作。 */
        @org.springframework.web.bind.annotation.GetMapping("/internal/apps/uninstall-blockers")
        String blockers(jakarta.servlet.http.HttpServletRequest request) throws Exception {
            String target =
                    request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            var verified = verifier.verify(new LifecycleRequest(
                    request.getMethod(),
                    target,
                    request.getInputStream().readNBytes(LifecycleRequest.MAX_BODY_BYTES + 1),
                    java.util.Collections.list(request.getHeaders(LifecycleAssertionVerifier.HEADER)),
                    java.util.Collections.list(request.getHeaders("Content-Encoding")),
                    null));
            handled.incrementAndGet();
            return verified.operationId();
        }
    }

    /** R1 格式校验在所有能力应用的启动配置上生效。 */
    @Test
    void rejectsAmbiguousLocalDeploymentIdentity() {
        for (String code : List.of("w", "Workflow", "work.flow", "workflow ")) {
            var config = new MultiProjectProperties(
                    "workflow", code, DEPLOYMENT, null, null, Map.of("primary", pem(platform)), null);
            assertThatThrownBy(() -> verifier(config, new InMemoryLifecycleNonceStore()))
                    .isInstanceOf(IllegalStateException.class);
        }
        for (String id : List.of("dep-01", "deploy.aaa", "Deploy-aaa", "-deploy-aaa", "deploy-aaa ")) {
            var config = new MultiProjectProperties(
                    "workflow", "workflow", id, null, null, Map.of("primary", pem(platform)), null);
            assertThatThrownBy(() -> verifier(config, new InMemoryLifecycleNonceStore()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** 64 KiB 原始 JSON 可验证，额外一个字节在构造请求时就失败。 */
    @Test
    void acceptsExactBodyAndCursorLimits() throws Exception {
        byte[] body = bytes("{\"value\":\"" + "x".repeat(65524) + "\"}");
        assertThat(body).hasSize(65536);
        verifier(new InMemoryLifecycleNonceStore())
                .verify(request(sign(claims("PUT", PROJECT, body)), "PUT", PROJECT, body, "9001"));
        String target = BLOCKERS + "?cursor=" + "x".repeat(256);
        verifier(new InMemoryLifecycleNonceStore())
                .verify(request(sign(claims("GET", target, new byte[0])), "GET", target, new byte[0], null));
    }

    /** 统一检查无效断言未占用 nonce。 */
    private static void invalidClaims(Consumer<Map<String, Object>> change) throws Exception {
        var claims = claims("GET", BLOCKERS, new byte[0]);
        change.accept(claims);
        var store = new InMemoryLifecycleNonceStore();
        reject(verifier(store), request(sign(claims), "GET", BLOCKERS, new byte[0], null), HttpStatus.UNAUTHORIZED);
        assertThat(store.size()).isZero();
    }

    /** 明确 HTTP 分类，异常原因链不得携带秘密。 */
    private static void reject(LifecycleAssertionVerifier verifier, LifecycleRequest request, HttpStatus status) {
        assertThatThrownBy(() -> verifier.verify(request))
                .isInstanceOfSatisfying(LifecycleVerificationException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(status);
                    assertThat(error.getCause()).isNull();
                    assertThat(error.getMessage()).doesNotContain("private-db-password", "op-01", "9001");
                });
    }

    /** 使用固定时钟复现边界，信任根来自同一份部署配置。 */
    private static LifecycleAssertionVerifier verifier(LifecycleNonceStore store) {
        return verifier(properties(), store);
    }

    /** 支持测试受控轮换配置变化。 */
    private static LifecycleAssertionVerifier verifier(MultiProjectProperties properties, LifecycleNonceStore store) {
        return new LifecycleAssertionVerifier(
                properties, starter(), store, LifecycleEndpoint.standardEndpoints(), CLOCK);
    }

    /** 默认两代公钥代表正常轮换窗口。 */
    private static MultiProjectProperties properties() {
        return properties(Map.of("primary", pem(platform), "next", pem(nextPlatform)));
    }

    /** 测试配置没有生产密钥或真实部署证明。 */
    private static MultiProjectProperties properties(Map<String, String> keys) {
        return new MultiProjectProperties("workflow", "workflow", DEPLOYMENT, "a".repeat(64), null, keys, null);
    }

    /** 单项目配置也可承载能力应用的独立生命周期身份。 */
    private static KaiwuStarterProperties starter() {
        var result = new KaiwuStarterProperties();
        result.setContextPublicKey(pem(gateway));
        return result;
    }

    /** 构造原始请求快照，绑定信息只接受服务端登记值。 */
    private static LifecycleRequest request(String token, String method, String target, byte[] body, String projectId) {
        return new LifecycleRequest(method, target, body, List.of(token), List.of(), projectId);
    }

    /** 独立生成 jti，项目端点只使用十进制签名 ID。 */
    private static Map<String, Object> claims(String method, String target, byte[] body) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iss", "kaiwu-system");
        result.put("sub", "system");
        result.put("aud", "kaiwu-app-lifecycle.workflow." + DEPLOYMENT);
        result.put("appCode", "workflow");
        result.put("deploymentId", DEPLOYMENT);
        result.put("iat", NOW);
        result.put("exp", NOW + 30);
        result.put(
                "jti",
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(java.nio.ByteBuffer.allocate(16)
                                .putLong(0)
                                .putLong(IDS.incrementAndGet())
                                .array()));
        result.put("scope", method.equals("GET") ? "app:lifecycle:read" : "app:lifecycle:write");
        result.put("operationId", "op-01");
        if (target.contains("/projects/")) {
            result.put("projectId", "9001");
        }
        result.put("htm", method);
        result.put("htu", target);
        result.put(
                "bsh",
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(body)));
        return result;
    }

    /** 使用标准 JCA 而非生产解析器签发，避免同实现自证。 */
    private static String sign(Map<String, Object> claims) throws Exception {
        return signRaw(header("primary"), bytes(JSON.writeValueAsString(claims)), platform);
    }

    /** 可手写重复键和畸形 UTF-8，并仍保持真实有效签名。 */
    private static String signRaw(String header, byte[] claims, KeyPair key) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String input = encoder.encodeToString(bytes(header)) + "." + encoder.encodeToString(claims);
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.getPrivate());
        signature.update(bytes(input));
        return input + "." + encoder.encodeToString(signature.sign());
    }

    /** 固定 JOSE 头由三项组成。 */
    private static String header(String kid) {
        return "{\"typ\":\"kaiwu-app-lifecycle+jwt\",\"alg\":\"RS256\",\"kid\":\"" + kid + "\"}";
    }

    /** 原始字节供哈希和独立签名器共享。 */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 测试受控配置支持 SPKI 公钥编码。 */
    private static String pem(KeyPair key) {
        return Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
    }
}
