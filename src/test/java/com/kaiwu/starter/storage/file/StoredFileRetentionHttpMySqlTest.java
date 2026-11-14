package com.kaiwu.starter.storage.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaiwu.starter.ContextPermissionResolver;
import com.kaiwu.starter.GatewayContextVerifier;
import com.kaiwu.starter.KaiwuStarterProperties;
import com.kaiwu.starter.StarterAuthInterceptor;
import com.kaiwu.starter.storage.adapter.LocalDiskObjectStorage;
import com.kaiwu.starter.storage.controller.StoredFileController;
import com.kaiwu.starter.storage.repository.JdbcStoredFileRepository;
import com.kaiwu.starter.storage.service.ObjectKeyPolicy;
import com.kaiwu.starter.storage.service.StoredFileService;
import io.jsonwebtoken.Jwts;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 实际Tomcat→普通Starter拦截器→文件Controller→MySQL/本地对象存储，未部署到共享环境。 */
class StoredFileRetentionHttpMySqlTest {
    private static final byte[] CONTENT = {1, 2, 3};
    private static final HttpClient HTTP =
            HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private static MySQLContainer<?> database;
    private static Tomcat tomcat;
    private static Path root;
    private static KeyPair keys;
    private static GatewayContextVerifier verifier;
    private static StoredFileService service;
    private static String fileId;
    private static String baseUrl;

    /** 全部监听与数据源为本轮独占；真实文件记录和保留引用先于HTTP请求持久化。 */
    @BeforeAll
    static void start() throws Exception {
        database = new MySQLContainer<>(
                DockerImageName.parse(System.getenv().getOrDefault("KAIWU_TEST_MYSQL_IMAGE", "mysql:8.4"))
                        .asCompatibleSubstituteFor("mysql"));
        database.start();
        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        jdbc.execute(Files.readString(Path.of("docs/storage-file.sql")));
        jdbc.execute(Files.readString(Path.of("docs/storage-file-retention.sql")));
        root = Files.createTempDirectory("retention-http-");
        var clock = Clock.systemUTC();
        var policy = new ObjectKeyPolicy("demo", clock);
        var storage = new LocalDiskObjectStorage(root.resolve("objects"), policy, 1024);
        service = new StoredFileService(
                new JdbcStoredFileRepository(jdbc, true),
                storage,
                ref -> storage,
                policy,
                clock,
                1024,
                Duration.ofMinutes(5));
        fileId = service.upload(
                        "invoice",
                        "proof.bin",
                        "application/octet-stream",
                        new ByteArrayInputStream(CONTENT),
                        CONTENT.length,
                        "1900000000000000001")
                .id();
        service.retain(fileId, "workflow", "generation-1");
        keys = Jwts.SIG.RS256.keyPair().build();
        var properties = new KaiwuStarterProperties();
        properties.setProjectCode("demo");
        properties.setAudience("kaiwu-demo-service");
        properties.setContextPublicKey("-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
        verifier = new GatewayContextVerifier(properties);
        tomcat = new Tomcat();
        tomcat.setBaseDir(root.resolve("tomcat").toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addContext("", root.toString());
        var web = new AnnotationConfigWebApplicationContext();
        web.register(HttpConfiguration.class);
        var servlet = Tomcat.addServlet(context, "dispatcher", new DispatcherServlet(web));
        servlet.setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "dispatcher");
        tomcat.start();
        baseUrl = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort();
    }

    /** 清理自有监听、容器和临时对象，不执行外部环境删除。 */
    @AfterAll
    static void stop() throws Exception {
        try {
            if (tomcat != null) {
                tomcat.stop();
                tomcat.destroy();
            }
        } finally {
            if (database != null) database.stop();
            if (root != null) {
                try (var paths = Files.walk(root)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    /** 真实Context鉴权后仍能按旧JSON包络列出并中转下载被引用文件。 */
    @Test
    void retainedFileRemainsReadableWithExistingHttpContract() throws Exception {
        var list = send("GET", "/api/files", token(List.of("file:record:list"), "demo", "kaiwu-demo-service"));
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(new String(list.body(), StandardCharsets.UTF_8)).contains("\"code\":0", fileId, "proof.bin");
        var download = send(
                "GET",
                "/api/files/" + fileId + "/content",
                token(List.of("file:record:download"), "demo", "kaiwu-demo-service"));
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).containsExactly(CONTENT);
        assertThat(download.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }

    /** 有删除权限也不能经普通管理入口删被保留证据，且不存在公开release端点。 */
    @Test
    void ordinaryHttpCannotDeleteOrReleaseRetainedEvidence() throws Exception {
        String jwt = token(List.of("file:record:delete"), "demo", "kaiwu-demo-service");
        assertThat(send("DELETE", "/api/files/" + fileId, jwt).statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/api/files/" + fileId + "/release", jwt).statusCode())
                .isEqualTo(404);
        assertThat(service.find(fileId)).isPresent();
        assertThat(service.purgeExpired(Duration.ZERO, Duration.ZERO)).isZero();
    }

    /** 无Context、错误受众/项目和缺权限全部拒绝，文件与引用事实不变。 */
    @Test
    void rejectsDirectAndUnauthorizedHttpAccess() throws Exception {
        String path = "/api/files/" + fileId + "/content";
        assertThat(send("GET", path, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", path, token(List.of("file:record:download"), "demo", "kaiwu-gateway-service"))
                        .statusCode())
                .isEqualTo(401);
        assertThat(send("GET", path, token(List.of("file:record:download"), "other", "kaiwu-demo-service"))
                        .statusCode())
                .isEqualTo(401);
        assertThat(send("GET", path, token(List.of(), "demo", "kaiwu-demo-service"))
                        .statusCode())
                .isEqualTo(403);
        assertThat(service.find(fileId)).isPresent();
    }

    /** HTTP只发送临时签发Context，不记录令牌或真实部署地址。 */
    private static HttpResponse<byte[]> send(String method, String path, String jwt) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(baseUrl + path)).method(method, HttpRequest.BodyPublishers.noBody());
        if (jwt != null) request.header(StarterAuthInterceptor.CONTEXT_HEADER, jwt);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** 签发测试用固定audience项目Context，可逐项移除所需权限。 */
    private static String token(List<String> permissions, String project, String audience) {
        Instant now = Instant.now();
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
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .claim("sid", "session")
                .claim("projectCode", project)
                .claim("projectId", "1900000000000000100")
                .claim("permissions", permissions)
                .signWith(keys.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /** 最小真实Servlet配置，不扫描或自动放行生产其他端点。 */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class HttpConfiguration implements WebMvcConfigurer {
        /** 直接装配本轮真实MySQL文件服务，Controller保持已有协议。 */
        @Bean
        StoredFileController files() {
            return new StoredFileController(service, Duration.ofMinutes(5));
        }

        /** 普通请求逐次走Starter固定audience及声明式权限校验，并由MVC清理请求Context。 */
        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new StarterAuthInterceptor(verifier, new ContextPermissionResolver()));
        }
    }
}
