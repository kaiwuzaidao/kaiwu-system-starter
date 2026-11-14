package com.kaiwu.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaiwu.starter.GatewayContextVerifier.DelegationBinding;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 测试专用S2S接入方：真实HTTP/HMAC/RSA/共享MySQL nonce验证C4必须被正确编排。
 * 不注册生产端点，不替代Workflow完整协议、业务事务或真实Gateway验收。
 */
class DelegatedContextHttpMySqlTest {
    private static final String PATH = "/internal/v1/instances";
    private static final String SOURCE = "kaiwu-demo.purchase-service";
    private static final byte[] BODY = "{\"businessType\":\"purchase-order\"}".getBytes(StandardCharsets.UTF_8);
    private static final DelegationBinding ALLOWED = new DelegationBinding(
            "purchase", SOURCE, "demo", "workflow", "POST " + PATH, "instance:start", "purchase-order");
    private static final AtomicInteger EFFECTS = new AtomicInteger();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static MySQLContainer<?> database;
    private static HttpServer first;
    private static HttpServer second;
    private static KeyPair keys;
    private static byte[] secret;
    private static GatewayContextVerifier verifier;

    /** 两个接收副本使用同一原子唯一键nonce表，密钥仅在测试内存中存在。 */
    @BeforeAll
    static void start() throws Exception {
        keys = Jwts.SIG.RS256.keyPair().build();
        secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        var properties = new KaiwuStarterProperties();
        properties.setAudience("kaiwu-demo.workflow-service");
        properties.setProjectCode("demo");
        properties.setContextPublicKey("-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
        verifier = new GatewayContextVerifier(properties);
        database = new MySQLContainer<>(
                DockerImageName.parse(System.getenv().getOrDefault("KAIWU_TEST_MYSQL_IMAGE", "mysql:8.4"))
                        .asCompatibleSubstituteFor("mysql"));
        database.start();
        try (var connection = database.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE c4_nonce(kid VARBINARY(64) NOT NULL,nonce VARBINARY(64) NOT NULL,expires_at DATETIME NOT NULL,PRIMARY KEY(kid,nonce))");
        }
        first = server();
        second = server();
    }

    /** 只清理本用例拥有的HTTP监听与MySQL容器，不影响工作区或共享环境。 */
    @AfterAll
    static void stop() {
        if (first != null) first.stop(0);
        if (second != null) second.stop(0);
        if (database != null) database.stop();
    }

    /** 完整签名、nonce、用户权限与服务登记全部匹配才产生一次业务副作用。 */
    @Test
    void acceptsOnlyFullyAuthorizedDelegation() throws Exception {
        int before = EFFECTS.get();
        String jwt = token("1900000000000000001", "demo", SOURCE, true, false);
        assertThat(send(first, request(PATH, jwt, jwt, BODY, false, false))).isEqualTo(200);
        assertThat(EFFECTS.get()).isEqualTo(before + 1);
    }

    /** 缺签名或正文篡改不能在Context有效时被放行。 */
    @Test
    void rejectsMissingOrTamperedSignature() throws Exception {
        int before = EFFECTS.get();
        String jwt = token("1", "demo", SOURCE, true, false);
        assertThat(send(first, request(PATH, jwt, jwt, BODY, true, false))).isEqualTo(401);
        byte[] changed = "{\"businessType\":\"other\"}".getBytes(StandardCharsets.UTF_8);
        var signed = request(PATH, jwt, jwt, BODY, false, false);
        var tampered = new SignedRequest(
                signed.path(), signed.jwt(), changed, signed.timestamp(), signed.nonce(), signed.signature(), false);
        assertThat(send(first, tampered)).isEqualTo(401);
        assertThat(EFFECTS.get()).isEqualTo(before);
    }

    /** 缺少nonce即使保留正确签名也不得进入Context或业务授权步骤。 */
    @Test
    void rejectsMissingNonce() throws Exception {
        int before = EFFECTS.get();
        String jwt = token("1", "demo", SOURCE, true, false);
        var signed = request(PATH, jwt, jwt, BODY, false, false);
        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + first.getAddress().getPort() + PATH))
                .header("X-Kaiwu-Context", jwt)
                .header("X-Kaiwu-Timestamp", signed.timestamp())
                .header("X-Kaiwu-Signature", signed.signature())
                .POST(HttpRequest.BodyPublishers.ofByteArray(BODY))
                .build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(401);
        assertThat(EFFECTS.get()).isEqualTo(before);
    }

    /** 两个真实HTTP副本共享MySQL重放状态，第二次提交不产生业务副作用。 */
    @Test
    void rejectsNonceAcrossReplicas() throws Exception {
        String jwt = token("1", "demo", SOURCE, true, false);
        var signed = request(PATH, jwt, jwt, BODY, false, false);
        int before = EFFECTS.get();
        assertThat(send(first, signed)).isEqualTo(200);
        assertThat(send(second, signed)).isEqualTo(401);
        assertThat(EFFECTS.get()).isEqualTo(before + 1);
    }

    /** 签名绑定原始Context摘要，换成另一有效用户的Context也必须失败。 */
    @Test
    void rejectsReplacementWithAnotherValidUser() throws Exception {
        int before = EFFECTS.get();
        String original = token("1", "demo", SOURCE, true, false);
        String replacement = token("2", "demo", SOURCE, true, false);
        assertThat(send(first, request(PATH, original, replacement, BODY, false, false)))
                .isEqualTo(401);
        assertThat(EFFECTS.get()).isEqualTo(before);
    }

    /** 正确HMAC也不能弥补过期、跨项目或未登记来源audience。 */
    @Test
    void rejectsInvalidContextAfterValidServiceSignature() throws Exception {
        int before = EFFECTS.get();
        for (String jwt : List.of(
                token("1", "other", SOURCE, true, false),
                token("1", "demo", "other-service", true, false),
                token("1", "demo", SOURCE, true, true))) {
            assertThat(send(first, request(PATH, jwt, jwt, BODY, false, false))).isEqualTo(401);
        }
        assertThat(EFFECTS.get()).isEqualTo(before);
    }

    /** 用户initiatePermission和服务业务类型授权缺一不可，不能仅以合法Context代表授权。 */
    @Test
    void rejectsMissingFinalPermissionAndBusinessType() throws Exception {
        int before = EFFECTS.get();
        String denied = token("1", "demo", SOURCE, false, false);
        assertThat(send(first, request(PATH, denied, denied, BODY, false, false)))
                .isEqualTo(403);
        String jwt = token("1", "demo", SOURCE, true, false);
        assertThat(send(
                        first,
                        request(
                                PATH,
                                jwt,
                                jwt,
                                "{\"businessType\":\"other\"}".getBytes(StandardCharsets.UTF_8),
                                false,
                                false)))
                .isEqualTo(403);
        assertThat(EFFECTS.get()).isEqualTo(before);
    }

    /** 未登记端点和带query的目标不能调用C4；签名路径不能替换成另一目标。 */
    @Test
    void rejectsUnregisteredEndpointAndRawTarget() throws Exception {
        int before = EFFECTS.get();
        String jwt = token("1", "demo", SOURCE, true, false);
        assertThat(send(first, request("/api/instances", jwt, jwt, BODY, false, false)))
                .isEqualTo(404);
        assertThat(send(first, request(PATH + "?x=1", jwt, jwt, BODY, false, false)))
                .isEqualTo(400);
        assertThat(EFFECTS.get()).isEqualTo(before);
    }

    /** 实际JDBC无法连接共享nonce库时返回503，不退化到本地内存防重放。 */
    @Test
    void failsClosedWhenNonceDatabaseIsUnavailable() throws Exception {
        int before = EFFECTS.get();
        String jwt = token("1", "demo", SOURCE, true, false);
        assertThat(send(first, request(PATH, jwt, jwt, BODY, false, true))).isEqualTo(503);
        assertThat(EFFECTS.get()).isEqualTo(before);
    }

    /** 创建只监听回环地址的参考入口，未知路径不会委托验证。 */
    private static HttpServer server() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", DelegatedContextHttpMySqlTest::handle);
        server.start();
        return server;
    }

    /** 测试接入方固定顺序：精确目标、HMAC、共享nonce、Context、用户权限、完整委托、业务副作用。 */
    private static void handle(HttpExchange exchange) throws IOException {
        int status;
        try {
            String raw = exchange.getRequestURI().toString();
            if (exchange.getRequestURI().getRawQuery() != null) status = 400;
            else if (!PATH.equals(raw) || !"POST".equals(exchange.getRequestMethod())) status = 404;
            else status = authorize(exchange, raw);
        } catch (ContextVerificationException denied) {
            status = 401;
        } catch (SQLException unavailable) {
            status = unavailable.getErrorCode() == 1062 ? 401 : 503;
        } catch (Exception invalid) {
            status = 401;
        }
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    /** 测试端点登记来自服务器常量；不读取自报caller、scope或权限头作为事实。 */
    private static int authorize(HttpExchange exchange, String raw) throws Exception {
        var headers = exchange.getRequestHeaders();
        for (String name : List.of("X-Kaiwu-Context", "X-Kaiwu-Timestamp", "X-Kaiwu-Nonce", "X-Kaiwu-Signature")) {
            if (headers.get(name) == null || headers.get(name).size() != 1) return 401;
        }
        String jwt = headers.getFirst("X-Kaiwu-Context");
        String timestamp = headers.getFirst("X-Kaiwu-Timestamp");
        String nonce = headers.getFirst("X-Kaiwu-Nonce");
        if (Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) > 30) return 401;
        if (Base64.getUrlDecoder().decode(nonce).length != 16) return 401;
        byte[] body = exchange.getRequestBody().readNBytes(4097);
        if (body.length > 4096) return 413;
        byte[] actual = Base64.getUrlDecoder().decode(headers.getFirst("X-Kaiwu-Signature"));
        if (!MessageDigest.isEqual(mac(raw, body, jwt, timestamp, nonce), actual)) return 401;
        String url = "true".equals(headers.getFirst("X-Test-Nonce-Unavailable"))
                ? "jdbc:mysql://127.0.0.1:1/test?connectTimeout=500&socketTimeout=500"
                : database.getJdbcUrl();
        try (var connection = DriverManager.getConnection(url, database.getUsername(), database.getPassword());
                var insert = connection.prepareStatement(
                        "INSERT INTO c4_nonce(kid,nonce,expires_at) VALUES(?,?,DATE_ADD(NOW(),INTERVAL 5 MINUTE))")) {
            insert.setBytes(1, "key-1".getBytes(StandardCharsets.US_ASCII));
            insert.setBytes(2, nonce.getBytes(StandardCharsets.US_ASCII));
            insert.executeUpdate();
        }
        var user = verifier.verifyDelegatedSource(jwt, SOURCE, Set.of(SOURCE));
        if (!user.hasPermission("purchase:order:submit")) return 403;
        String businessType = JSON.readTree(body).path("businessType").asText();
        try {
            verifier.requireDelegation(
                    new DelegationBinding(
                            "purchase",
                            SOURCE,
                            user.projectCode(),
                            "workflow",
                            "POST " + raw,
                            "instance:start",
                            businessType),
                    Set.of(ALLOWED));
        } catch (ContextVerificationException forbidden) {
            return 403;
        }
        EFFECTS.incrementAndGet();
        return 200;
    }

    /** 参考协议长度编码绑定原始Context摘要；固定身份只用于本测试登记。 */
    private static byte[] mac(String path, byte[] body, String jwt, String timestamp, String nonce) throws Exception {
        byte[][] fields = {
            text("KW-S2S-1"),
            text("key-1"),
            text("purchase"),
            text("workflow"),
            text("POST"),
            text(path),
            new byte[0],
            digest(body),
            digest(text(jwt)),
            text(timestamp),
            text(nonce),
            new byte[0]
        };
        var bytes = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(bytes)) {
            for (byte[] field : fields) {
                data.writeInt(field.length);
                data.write(field);
            }
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(bytes.toByteArray());
    }

    /** 记录请求所签Context与实际发送Context，可构造不重签的替换负例。 */
    private static SignedRequest request(
            String path,
            String signedJwt,
            String sentJwt,
            byte[] body,
            boolean missingSignature,
            boolean databaseUnavailable)
            throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String signature = missingSignature
                ? null
                : Base64.getUrlEncoder().withoutPadding().encodeToString(mac(path, body, signedJwt, timestamp, nonce));
        return new SignedRequest(path, sentJwt, body, timestamp, nonce, signature, databaseUnavailable);
    }

    /** 真实HTTP调用不打印头或签名，仅向JUnit暴露安全状态码。 */
    private static int send(HttpServer server, SignedRequest signed) throws Exception {
        var builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + signed.path()))
                .header("X-Kaiwu-Context", signed.jwt())
                .header("X-Kaiwu-Timestamp", signed.timestamp())
                .header("X-Kaiwu-Nonce", signed.nonce())
                .POST(HttpRequest.BodyPublishers.ofByteArray(signed.body()));
        if (signed.signature() != null) builder.header("X-Kaiwu-Signature", signed.signature());
        if (signed.databaseUnavailable()) builder.header("X-Test-Nonce-Unavailable", "true");
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    /** 构造由临时Gateway密钥签发的来源Context，用户身份仅来自已验签sub。 */
    private static String token(String user, String project, String audience, boolean permitted, boolean expired) {
        Instant issued = Instant.now().minusSeconds(expired ? 120 : 0);
        return Jwts.builder()
                .header()
                .type(GatewayContextVerifier.CONTEXT_TYPE)
                .and()
                .issuer("kaiwu-gateway-service")
                .audience()
                .add(audience)
                .and()
                .subject(user)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(issued))
                .expiration(Date.from(issued.plusSeconds(60)))
                .claim("sid", "session")
                .claim("projectId", "1900000000000000100")
                .claim("projectCode", project)
                .claim("permissions", permitted ? List.of("purchase:order:submit") : List.of())
                .signWith(keys.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /** 编码协议文本，不使用平台默认字符集。 */
    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 摘要使用原始字节，避免十六进制文本与真实协议混淆。 */
    private static byte[] digest(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    /** 请求值只在测试内存中存在，禁止记录自动生成的toString。 */
    private record SignedRequest(
            String path,
            String jwt,
            byte[] body,
            String timestamp,
            String nonce,
            String signature,
            boolean databaseUnavailable) {}
}
