package com.kaiwu.starter.agent;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.kaiwu.starter.agent.protocol.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentRegistryLifecycleTest {
    static CapabilityManifest manifest(String code) {
        return new CapabilityManifest(
                "crm",
                code,
                "1",
                "查询记录",
                "record:record:list",
                DataScope.SELF,
                Map.of("pageSize", ParamSpec.pageSize(100, 20)),
                Set.of("id", "title"),
                Map.of(),
                true,
                true,
                ConfirmationMode.NONE,
                5000);
    }

    private static final String CODE = "crm.record.list";

    private static class Fake implements AgentRegistryTransport {
        final AtomicInteger registers = new AtomicInteger();
        final AtomicInteger heartbeats = new AtomicInteger();
        volatile int status;

        @Override
        public Revision register(String code, String version, byte[] body) {
            registers.incrementAndGet();
            if (status != 0) throw new AgentRegistryException(status);
            return new Revision(code, version, "a".repeat(64));
        }

        @Override
        public void heartbeat(Revision revision) {
            heartbeats.incrementAndGet();
        }
    }

    @Test
    void registersThenHeartbeatsAndStopsWithoutRevoking() {
        Fake fake = new Fake();
        try (var lifecycle = new AgentRegistryLifecycle(fake, List.of(manifest(CODE)), "crm", 10)) {
            lifecycle.start();
            lifecycle.start();
            await().atMost(Duration.ofSeconds(18))
                    .untilAsserted(() -> assertThat(fake.heartbeats.get()).isPositive());
            assertThat(fake.registers.get()).isEqualTo(1);
            assertThat(lifecycle.states().get(CODE)).isEqualTo(AgentRegistryLifecycle.Phase.REGISTERED);
            lifecycle.stop();
            assertThat(lifecycle.isRunning()).isFalse();
            assertThat(lifecycle.states().get(CODE)).isEqualTo(AgentRegistryLifecycle.Phase.STOPPED);
        }
    }

    @Test
    void retriesTransientFailureWithBoundedBackoff() {
        Fake fake = new Fake();
        fake.status = 503;
        try (var lifecycle = new AgentRegistryLifecycle(fake, List.of(manifest(CODE)), "crm", 60)) {
            lifecycle.start();
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(() ->
                            assertThat(lifecycle.states().get(CODE)).isEqualTo(AgentRegistryLifecycle.Phase.RETRYING));
            fake.status = 0;
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(lifecycle.states().get(CODE))
                            .isEqualTo(AgentRegistryLifecycle.Phase.REGISTERED));
            assertThat(fake.registers.get()).isEqualTo(2);
            for (int failures = 0; failures < 100; failures++)
                assertThat(AgentRegistryLifecycle.retryDelayMillis(failures)).isBetween(1000L, 60000L);
        }
    }

    @Test
    void conflictIsTerminalUntilExplicitRestart() {
        Fake fake = new Fake();
        fake.status = 409;
        try (var lifecycle = new AgentRegistryLifecycle(fake, List.of(manifest(CODE)), "crm", 60)) {
            lifecycle.start();
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(() ->
                            assertThat(lifecycle.states().get(CODE)).isEqualTo(AgentRegistryLifecycle.Phase.REJECTED));
            fake.status = 0;
            await().during(Duration.ofMillis(1200))
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(fake.registers.get()).isEqualTo(1));
            lifecycle.stop();
            lifecycle.start();
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(lifecycle.states().get(CODE))
                            .isEqualTo(AgentRegistryLifecycle.Phase.REGISTERED));
        }
    }

    @Test
    void invalidCredentialStopsAllCapabilities() {
        Fake fake = new Fake();
        fake.status = 401;
        try (var lifecycle =
                new AgentRegistryLifecycle(fake, List.of(manifest(CODE), manifest("crm.record.other")), "crm", 60)) {
            lifecycle.start();
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(lifecycle.states().values())
                            .containsOnly(AgentRegistryLifecycle.Phase.REJECTED));
        }
    }

    @Test
    void rejectsEmptyDuplicateAndForeignManifests() {
        Fake fake = new Fake();
        assertThatThrownBy(() -> new AgentRegistryLifecycle(fake, List.of(), "crm", 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentRegistryLifecycle(fake, List.of(manifest(CODE), manifest(CODE)), "crm", 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentRegistryLifecycle(fake, List.of(manifest("other.record.list")), "crm", 60))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
