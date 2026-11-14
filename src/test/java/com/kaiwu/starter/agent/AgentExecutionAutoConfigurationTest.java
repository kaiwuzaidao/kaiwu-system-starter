package com.kaiwu.starter.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kaiwu.starter.*;
import com.kaiwu.starter.agent.controller.AgentCapabilityController;
import com.kaiwu.starter.agent.protocol.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

class AgentExecutionAutoConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentExecutionAutoConfiguration.class));

    @Test
    void disabledDefaultHasNoHandlerEntryPoint() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AgentCapabilityController.class));
    }

    @Test
    void missingReplayStoreFailsAndRedisBackedConfigurationStarts() {
        var configured = runner.withPropertyValues(
                        "kaiwu.starter.agent.execution.enabled=true", "kaiwu.starter.agent.execution.project-id=123")
                .withBean(KaiwuStarterProperties.class, () -> {
                    var p = new KaiwuStarterProperties();
                    p.setProjectCode("crm");
                    return p;
                })
                .withBean(GatewayContextVerifier.class, () -> mock(GatewayContextVerifier.class))
                .withBean(PermissionResolver.class, ContextPermissionResolver::new)
                .withBean(AgentReadCapabilityHandler.class, () -> new AgentReadCapabilityHandler() {
                    public CapabilityManifest manifest() {
                        return AgentRegistryLifecycleTest.manifest("crm.record.list");
                    }

                    public List<Map<String, Object>> query(Map<String, Object> values, KaiwuContext actor) {
                        return List.of();
                    }
                });
        configured.run(context -> assertThat(context).hasFailed());
        configured
                .withUserConfiguration(BroadScan.class)
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgentCapabilityController.class);
                });
        configured
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgentCapabilityController.class);
                    assertThat(context.getBean(AgentReplayStore.class)).isInstanceOf(RedisAgentReplayStore.class);
                    assertThat(context.getBean(AgentReadRuntime.class).manifests())
                            .hasSize(1);
                });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan(
            basePackageClasses = AgentCapabilityController.class,
            excludeFilters =
                    @org.springframework.context.annotation.ComponentScan.Filter(
                            type = org.springframework.context.annotation.FilterType.ANNOTATION,
                            classes = org.springframework.boot.autoconfigure.AutoConfiguration.class))
    static class BroadScan {}

    @Test
    void broadApplicationScanStillKeepsExecutionDisabled() {
        runner.withUserConfiguration(BroadScan.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AgentCapabilityController.class);
        });
    }
}
