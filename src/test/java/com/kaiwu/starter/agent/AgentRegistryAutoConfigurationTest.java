package com.kaiwu.starter.agent;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.kaiwu.starter.KaiwuStarterProperties;
import com.kaiwu.starter.agent.protocol.CapabilityManifest;
import com.kaiwu.starter.client.ServiceAddressResolver;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentRegistryAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentRegistryAutoConfiguration.class));

    @Test
    void disabledByDefaultDoesNotRequireCredentialsOrCreateThreads() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AgentRegistryLifecycle.class));
    }

    @Test
    void disablingStarterAlsoDisablesAgent() {
        runner.withPropertyValues("kaiwu.starter.enabled=false", "kaiwu.starter.agent.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(AgentRegistryLifecycle.class));
    }

    @Test
    void enabledWithInvalidCredentialFailsWithoutExposingSecret() {
        runner.withPropertyValues("kaiwu.starter.agent.enabled=true", "kaiwu.starter.agent.credential=invalid-secret")
                .withBean(KaiwuStarterProperties.class, KaiwuStarterProperties::new)
                .withBean(ServiceAddressResolver.class, ServiceAddressResolver::direct)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("独立 Agent 注册凭据格式错误")
                            .hasStackTraceContaining("IllegalArgumentException");
                    assertThat(context.getStartupFailure().getMessage()).doesNotContain("invalid-secret");
                });
    }

    @Test
    void enabledContextRegistersExplicitManifestAndStopsOnClose() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/agent/capabilities/register", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = HttpAgentRegistryTransportTest.reply().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        AtomicReference<AgentRegistryLifecycle> lifecycle = new AtomicReference<>();
        try {
            runner.withPropertyValues(
                            "kaiwu.starter.agent.enabled=true",
                            "kaiwu.starter.agent.credential=kagent_1789248125900000001_" + "a".repeat(43),
                            "kaiwu.starter.agent.system-base-url=http://127.0.0.1:"
                                    + server.getAddress().getPort())
                    .withBean(KaiwuStarterProperties.class, () -> {
                        var properties = new KaiwuStarterProperties();
                        properties.setProjectCode("crm");
                        return properties;
                    })
                    .withBean(ServiceAddressResolver.class, ServiceAddressResolver::direct)
                    .withBean(CapabilityManifest.class, () -> AgentRegistryLifecycleTest.manifest("crm.record.list"))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        lifecycle.set(context.getBean(AgentRegistryLifecycle.class));
                        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                                        lifecycle.get().states().get("crm.record.list"))
                                .isEqualTo(AgentRegistryLifecycle.Phase.REGISTERED));
                    });
            assertThat(lifecycle.get().isRunning()).isFalse();
            assertThat(lifecycle.get().states().get("crm.record.list")).isEqualTo(AgentRegistryLifecycle.Phase.STOPPED);
        } finally {
            server.stop(0);
        }
    }
}
