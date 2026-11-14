package com.kaiwu.starter.storage.adapter;

import static com.kaiwu.starter.storage.support.FakeStorageSystem.bundle;
import static com.kaiwu.starter.storage.support.FakeStorageSystem.credential;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.client.ServiceAddressResolver;
import com.kaiwu.starter.storage.client.StorageCredentialClient;
import com.kaiwu.starter.storage.exception.ObjectNotFoundException;
import com.kaiwu.starter.storage.exception.ObjectStorageException;
import com.kaiwu.starter.storage.model.PresignedUpload;
import com.kaiwu.starter.storage.model.StoredObject;
import com.kaiwu.starter.storage.service.ObjectKeyPolicy;
import com.kaiwu.starter.storage.support.FakeStorageSystem;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 在 LocalStack 上验证 provider=kaiwu 的完整读写链路：模拟 System 签发凭证，starter 经 S3 协议读写。
 *
 * <p>LocalStack 不校验签名、不执行会话策略，所以本测试覆盖的是协议与行为（只读拦截、类型白名单、
 * 大小上限、按 storage_ref 找回旧绑定、预签名可用）。内网 CI 拉不到镜像，
 * 只有设置 {@code KAIWU_TEST_LOCALSTACK_IMAGE} 时才运行。
 */
@EnabledIfEnvironmentVariable(named = "KAIWU_TEST_LOCALSTACK_IMAGE", matches = ".+")
class KaiwuObjectStorageLocalStackTest {

    private static final GenericContainer<?> LOCALSTACK = new GenericContainer<>(
                    DockerImageName.parse(System.getenv("KAIWU_TEST_LOCALSTACK_IMAGE")))
            .withEnv("SERVICES", "s3")
            .withExposedPorts(4566);
    private static final HttpClient HTTP =
            HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    private static FakeStorageSystem system;
    private static KaiwuObjectStorage storage;
    private static StorageCredentialClient credentials;

    /**
     * 启动 LocalStack 与模拟 System，创建桶并装配存储。
     *
     * @throws Exception 初始化失败时抛出
     */
    @BeforeAll
    static void start() throws Exception {
        LOCALSTACK.start();
        // DinD的映射端口位于docker服务主机，不能把CI任务容器的localhost当作Docker主机。
        String endpoint = new URI("http", null, LOCALSTACK.getHost(), LOCALSTACK.getMappedPort(4566), null, null, null)
                .toString();
        try (S3Client admin = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClient(UrlConnectionHttpClient.create())
                .build()) {
            admin.createBucket(request -> request.bucket("kaiwu-files"));
        }
        system = new FakeStorageSystem();
        String expiresAt = Instant.now().plus(Duration.ofMinutes(30)).toString();
        system.respond(
                200,
                bundle(credential("20", "default", "READ_WRITE", "demo/", endpoint, expiresAt, "[\"text/*\"]")
                        + ","
                        + credential("10", "default", "READ_ONLY", "demo/", endpoint, expiresAt, "[]")));
        credentials = new StorageCredentialClient(
                system.baseUrl(),
                FakeStorageSystem.TOKEN,
                "demo",
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                ServiceAddressResolver.direct(),
                Clock.systemUTC());
        credentials.refresh();
        storage = new KaiwuObjectStorage(
                credentials, new ObjectKeyPolicy("demo", Clock.systemUTC()), 64, "default", Clock.systemUTC());
    }

    /** 关闭资源。 */
    @AfterAll
    static void stop() {
        try {
            if (storage != null) {
                storage.close();
            }
            if (credentials != null) {
                credentials.close();
            }
            if (system != null) {
                system.close();
            }
        } finally {
            LOCALSTACK.stop();
        }
    }

    /**
     * 写入、读取、元数据、删除的往返，写入结果带当前生效绑定的引用。
     *
     * @throws Exception 读取失败时抛出
     */
    @Test
    void writesReadsStatsAndDeletes() throws Exception {
        StoredObject stored = storage.put("demo/invoice/a.txt", text("hello"), 5, "text/plain");

        assertThat(stored.storageRef()).isEqualTo("20");
        try (InputStream in = storage.get("demo/invoice/a.txt")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        assertThat(storage.stat("demo/invoice/a.txt")).map(StoredObject::size).contains(5L);
        assertThat(storage.forRef("10").stat("demo/invoice/a.txt")).isPresent();

        storage.delete("demo/invoice/a.txt");
        assertThat(storage.stat("demo/invoice/a.txt")).isEmpty();
        assertThatThrownBy(() -> storage.get("demo/invoice/a.txt")).isInstanceOf(ObjectNotFoundException.class);
    }

    /** 未知长度的内容先缓冲再上传，超过上限时拒绝。 */
    @Test
    void enforcesLimitForUnknownLength() {
        StoredObject stored = storage.put("demo/invoice/b.txt", text("abc"), -1, "text/plain");
        assertThat(stored.size()).isEqualTo(3);

        assertThatThrownBy(() -> storage.put("demo/invoice/big.txt", text("x".repeat(65)), -1, "text/plain"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("大小上限");
    }

    /** 类型白名单、只读绑定与项目前缀都会在写入前拦截。 */
    @Test
    void rejectsDisallowedTypesReadOnlyBindingsAndForeignKeys() {
        assertThatThrownBy(() -> storage.put("demo/invoice/a.png", text("x"), 1, "image/png"))
                .hasMessageContaining("允许范围");
        assertThatThrownBy(() -> storage.forRef("10").put("demo/invoice/c.txt", text("x"), 1, "text/plain"))
                .hasMessageContaining("只读");
        assertThatThrownBy(() -> storage.put("other/c.txt", text("x"), 1, "text/plain"))
                .isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> storage.forRef("unknown")).isInstanceOf(ObjectStorageException.class);
    }

    /**
     * 预签名直传与下载地址可直接被 HTTP 客户端使用。
     *
     * @throws Exception 请求失败时抛出
     */
    @Test
    void presignedUploadAndDownloadWork() throws Exception {
        PresignedUpload upload = storage.presignPut("demo/invoice/d.txt", "text/plain", Duration.ofHours(2));
        assertThat(upload.expiresAt()).isBefore(Instant.now().plus(Duration.ofMinutes(31)));
        HttpRequest.Builder put =
                HttpRequest.newBuilder(upload.uri()).PUT(HttpRequest.BodyPublishers.ofString("direct"));
        upload.headers().forEach(put::header);
        assertThat(HTTP.send(put.build(), HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(200);

        URI download = storage.presignGet("demo/invoice/d.txt", Duration.ofMinutes(5));
        HttpResponse<String> response =
                HTTP.send(HttpRequest.newBuilder(download).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).isEqualTo("direct");
    }

    /**
     * 构造 UTF-8 内容流。
     *
     * @param value 文本
     * @return 内容流
     */
    private static InputStream text(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
