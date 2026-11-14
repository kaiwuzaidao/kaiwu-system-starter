package com.kaiwu.starter.storage.client;

import static com.kaiwu.starter.storage.support.FakeStorageSystem.bundle;
import static com.kaiwu.starter.storage.support.FakeStorageSystem.credential;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.client.ServiceAddressResolver;
import com.kaiwu.starter.storage.support.FakeStorageSystem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证凭证客户端的缓存、续签时机、前缀校验与失败降级。 */
class StorageCredentialClientTest {

    private static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");

    private FakeStorageSystem system;
    private MutableClock clock;

    /**
     * 启动模拟 System。
     *
     * @throws Exception 启动失败时抛出
     */
    @BeforeEach
    void start() throws Exception {
        system = new FakeStorageSystem();
        clock = new MutableClock(NOW);
    }

    /** 停止模拟 System。 */
    @AfterEach
    void stop() {
        system.close();
    }

    /**
     * 创建绑定 demo 项目的客户端。
     *
     * @return 客户端
     */
    private StorageCredentialClient client() {
        return new StorageCredentialClient(
                system.baseUrl(),
                FakeStorageSystem.TOKEN,
                "demo",
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                ServiceAddressResolver.direct(),
                clock);
    }

    /** 生效与只读绑定都能按引用取到；按存储名只返回可写的生效绑定。 */
    @Test
    void cachesActiveAndReadOnlyBindings() {
        system.respond(
                200,
                bundle(credential("2", "default", "READ_WRITE", "demo/", "http://s3", "2026-10-29T08:30:00Z", "[]")
                        + ","
                        + credential("1", "default", "READ_ONLY", "demo/", "http://s3", "2026-10-29T08:30:00Z", "[]")));
        StorageCredentialClient client = client();

        client.refresh();

        assertThat(client.active("default")).map(value -> value.storageRef()).contains("2");
        assertThat(client.byRef("1")).map(value -> value.readOnly()).contains(true);
        assertThat(client.active("archive")).isEmpty();
        assertThat(client.byRef("1").orElseThrow().toString()).doesNotContain("session", "test");
    }

    /** 前缀与本项目不一致时整批丢弃，绝不使用别的项目的凭证。 */
    @Test
    void rejectsCredentialsForAnotherPrefix() {
        system.respond(
                200,
                bundle(credential("9", "default", "READ_WRITE", "other/", "http://s3", "2026-10-29T08:30:00Z", "[]")));
        StorageCredentialClient client = client();

        client.refresh();

        assertThat(client.byRef("9")).isEmpty();
    }

    /** System 失败时保留旧快照；凭证过期后不再返回。 */
    @Test
    void keepsPreviousSnapshotOnFailureUntilExpiry() {
        system.respond(
                200,
                bundle(credential("2", "default", "READ_WRITE", "demo/", "http://s3", "2026-10-29T08:30:00Z", "[]")));
        StorageCredentialClient client = client();
        client.refresh();

        system.respond(500, "{\"code\":500}");
        client.refresh();
        assertThat(client.byRef("2")).isPresent();

        clock.set(Instant.parse("2026-10-29T08:30:00Z"));
        assertThat(client.byRef("2")).isEmpty();
        assertThat(client.active("default")).isEmpty();
    }

    /** 剩余有效期充足时不请求 System；不足 20% 或 2 分钟时续签。 */
    @Test
    void renewsOnlyWhenCredentialsAreDue() {
        system.respond(
                200,
                bundle(credential("2", "default", "READ_WRITE", "demo/", "http://s3", "2026-10-29T08:30:00Z", "[]")));
        StorageCredentialClient client = client();
        client.refresh();
        int afterFirst = system.calls();

        clock.set(Instant.parse("2026-10-29T08:20:00Z"));
        client.renewIfNeeded();
        assertThat(system.calls()).isEqualTo(afterFirst);

        clock.set(Instant.parse("2026-10-29T08:25:00Z"));
        client.renewIfNeeded();
        assertThat(system.calls()).isEqualTo(afterFirst + 1);
    }

    /** 缺少地址或凭据属于配置错误，构造即失败。 */
    @Test
    void requiresAddressAndServiceCredential() {
        assertThatThrownBy(() -> new StorageCredentialClient(
                        "",
                        "token",
                        "demo",
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(5),
                        ServiceAddressResolver.direct(),
                        Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 可调的测试时钟。 */
    static final class MutableClock extends Clock {

        private Instant instant;

        /**
         * 创建时钟。
         *
         * @param instant 初始时间
         */
        MutableClock(Instant instant) {
            this.instant = instant;
        }

        /**
         * 调整当前时间。
         *
         * @param value 新时间
         */
        void set(Instant value) {
            instant = value;
        }

        /**
         * 固定为 UTC。
         *
         * @return UTC
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * 本测试不需要换时区。
         *
         * @param zone 时区
         * @return 自身
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /**
         * 返回当前设定的时间。
         *
         * @return 当前时间
         */
        @Override
        public Instant instant() {
            return instant;
        }
    }
}
