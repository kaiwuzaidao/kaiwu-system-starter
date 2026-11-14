package com.kaiwu.starter.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.ContextVerificationException;
import com.kaiwu.starter.auth.model.ProjectAdmission;
import com.kaiwu.starter.auth.service.TrustedProjectRegistry.Registration;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 用可控时间和并发屏障验证 T6 停用时限。 */
class ProjectRegistrationCacheTest {
    private static final long TTL = Duration.ofSeconds(30).toNanos();
    private static final Registration ACTIVE = new Registration("1", "app", 1, ProjectAdmission.FULL);
    private static final Registration DENIED = new Registration("1", "app", 2, ProjectAdmission.DENY);

    /** T6 通知丢失时，读取耗时计入 TTL，后续命中不续期。 */
    @Test
    void t6PositiveCacheExpiresFromReadStartWithoutNotification() {
        var clock = new AtomicLong();
        var current = new AtomicReference<>(ACTIVE);
        var reads = new AtomicInteger();
        var cache = new ProjectRegistrationCache(
                code -> {
                    if (reads.getAndIncrement() == 0) {
                        clock.addAndGet(Duration.ofMillis(100).toNanos());
                    }
                    return Optional.of(current.get());
                },
                Duration.ofSeconds(2),
                clock::get,
                2);

        assertThat(cache.find("alpha")).contains(ACTIVE);
        current.set(DENIED);
        clock.set(TTL - 1);
        assertThat(cache.find("alpha")).contains(ACTIVE);
        clock.set(TTL);
        assertThat(cache.find("alpha")).contains(DENIED);
        assertThat(reads).hasValue(2);
    }

    /** T6 新启用后，旧负缓存也在 30 秒内过期或可立即主动清除。 */
    @Test
    void t6NegativeCacheExpiresAndSupportsInvalidation() {
        var clock = new AtomicLong();
        var current = new AtomicReference<>(Optional.<Registration>empty());
        var cache = new ProjectRegistrationCache(code -> current.get(), Duration.ofSeconds(2), clock::get, 2);
        assertThat(cache.find("alpha")).isEmpty();
        assertThat(cache.find("beta")).isEmpty();
        current.set(Optional.of(ACTIVE));
        cache.invalidate("alpha");
        assertThat(cache.find("alpha")).contains(ACTIVE);
        clock.set(TTL - 1);
        assertThat(cache.find("beta")).isEmpty();
        clock.set(TTL);
        assertThat(cache.find("beta")).contains(ACTIVE);
    }

    /** T6 项目和部署失效后都立即重新读取权威状态。 */
    @Test
    void t6InvalidatesProjectAndDeployment() {
        var current = new AtomicReference<>(ACTIVE);
        var cache = new ProjectRegistrationCache(code -> Optional.of(current.get()), Duration.ofSeconds(2));
        assertThat(cache.find("alpha")).contains(ACTIVE);
        assertThat(cache.find("beta")).contains(ACTIVE);
        current.set(DENIED);
        cache.invalidate("alpha");
        assertThat(cache.find("alpha")).contains(DENIED);
        assertThat(cache.find("beta")).contains(ACTIVE);
        cache.invalidateAll();
        assertThat(cache.find("beta")).contains(DENIED);
    }

    /** T6 并发旧读取不能为当前请求授权或覆盖新登记。 */
    @Test
    void t6DiscardsInFlightReadAfterInvalidation() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var current = new AtomicReference<>(ACTIVE);
        var cache = new ProjectRegistrationCache(
                code -> {
                    Registration captured = current.get();
                    if (ACTIVE.equals(captured)) {
                        started.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test barrier");
                        }
                    }
                    return Optional.of(captured);
                },
                Duration.ofSeconds(5));

        var pending = new FutureTask<>(() -> cache.find("alpha"));
        Thread.startVirtualThread(pending);
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            current.set(DENIED);
            cache.invalidate("alpha");
            assertThat(cache.find("alpha")).contains(DENIED);
        } finally {
            release.countDown();
        }
        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ContextVerificationException.class);
        assertThat(cache.find("alpha")).contains(DENIED);
    }

    /** 超时查询失败关闭，且不会覆盖随后正常读取的结果。 */
    @Test
    void timeoutFailsClosed() {
        var release = new CountDownLatch(1);
        var cache = new ProjectRegistrationCache(
                code -> {
                    release.await();
                    return Optional.of(ACTIVE);
                },
                Duration.ofMillis(30));
        try {
            assertThatThrownBy(() -> cache.find("alpha"))
                    .isInstanceOf(ContextVerificationException.class)
                    .hasNoCause();
        } finally {
            release.countDown();
        }
    }

    /** 否定项也占容量，淘汰后会再查主库。 */
    @Test
    void boundsPositiveAndNegativeEntries() {
        var reads = new AtomicInteger();
        var cache = new ProjectRegistrationCache(
                code -> {
                    reads.incrementAndGet();
                    return "alpha".equals(code) ? Optional.empty() : Optional.of(ACTIVE);
                },
                Duration.ofSeconds(2),
                System::nanoTime,
                1);
        assertThat(cache.find("alpha")).isEmpty();
        assertThat(cache.find("beta")).contains(ACTIVE);
        assertThat(cache.find("alpha")).isEmpty();
        assertThat(reads).hasValue(3);
    }
}
