package com.kaiwu.starter.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kaiwu.starter.*;
import com.kaiwu.starter.agent.controller.AgentWriteCapabilityController;
import com.kaiwu.starter.agent.protocol.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentWriteAutoConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AgentWriteAutoConfiguration.class));

    @Test
    void disabledDefaultAndBroadScanHaveNoWriteController() {
        runner.withUserConfiguration(BroadScan.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AgentWriteCapabilityController.class);
        });
    }

    @Test
    void enabledWithoutLedgerFailsClosedAndExplicitDependenciesStart() {
        var configured = runner.withPropertyValues(
                        "kaiwu.starter.agent.write.enabled=true", "kaiwu.starter.agent.write.project-id=123")
                .withBean(KaiwuStarterProperties.class, () -> {
                    var properties = new KaiwuStarterProperties();
                    properties.setProjectCode("demo");
                    return properties;
                })
                .withBean(GatewayContextVerifier.class, () -> mock(GatewayContextVerifier.class))
                .withBean(PermissionResolver.class, ContextPermissionResolver::new)
                .withBean(AgentReplayStore.class, () -> (jti, expiry) -> true)
                .withBean(AgentWriteCapabilityHandler.class, () -> new AgentWriteCapabilityHandler() {
                    public CapabilityManifest manifest() {
                        return new CapabilityManifest(
                                "demo",
                                "demo.record.rename",
                                "1",
                                "重命名",
                                "demo:record:update",
                                DataScope.SELF,
                                Map.of(),
                                Set.of("name"),
                                Map.of(),
                                false,
                                true,
                                ConfirmationMode.REQUIRED,
                                1000);
                    }

                    public AgentWritePreview preview(Map<String, Object> params, KaiwuContext actor) {
                        return new AgentWritePreview(Map.of(), 0, "{}");
                    }

                    public String execute(Map<String, Object> params, String preconditions, KaiwuContext actor) {
                        return "{}";
                    }
                });
        configured.run(context -> assertThat(context).hasFailed());
        configured
                .withBean(AgentWriteLedger.class, () -> mock(AgentWriteLedger.class))
                .withUserConfiguration(BroadScan.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgentWriteCapabilityController.class);
                    assertThat(context.getBean(AgentWriteRuntime.class).manifests())
                            .hasSize(1);
                });
        configured
                .withBean(AgentWriteLedger.class, () -> mock(AgentWriteLedger.class))
                .withBean(AgentExecutionProperties.class, () -> {
                    var read = new AgentExecutionProperties();
                    read.setEnabled(true);
                    read.setProjectId("124");
                    return read;
                })
                .run(context -> assertThat(context).hasFailed());
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan(
            basePackageClasses = AgentWriteCapabilityController.class,
            excludeFilters =
                    @org.springframework.context.annotation.ComponentScan.Filter(
                            type = org.springframework.context.annotation.FilterType.ANNOTATION,
                            classes = org.springframework.boot.autoconfigure.AutoConfiguration.class))
    static class BroadScan {}
}
