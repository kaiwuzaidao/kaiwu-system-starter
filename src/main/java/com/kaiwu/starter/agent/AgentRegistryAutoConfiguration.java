package com.kaiwu.starter.agent;

import com.kaiwu.starter.AgentReadRuntime;
import com.kaiwu.starter.AgentWriteRuntime;
import com.kaiwu.starter.KaiwuStarterAutoConfiguration;
import com.kaiwu.starter.KaiwuStarterProperties;
import com.kaiwu.starter.agent.protocol.CapabilityManifest;
import com.kaiwu.starter.client.ServiceAddressResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 注册客户端只在显式启用时装配，普通业务请求不依赖注册服务。 */
@AutoConfiguration(
        after = KaiwuStarterAutoConfiguration.class,
        afterName = {
            "com.kaiwu.starter.agent.AgentExecutionAutoConfiguration",
            "com.kaiwu.starter.agent.AgentWriteAutoConfiguration"
        })
@EnableConfigurationProperties(AgentRegistryProperties.class)
@ConditionalOnProperty(prefix = "kaiwu.starter", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentRegistryAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "kaiwu.starter.agent", name = "enabled", havingValue = "true")
    public AgentRegistryLifecycle kaiwuAgentRegistryLifecycle(
            AgentRegistryProperties properties,
            KaiwuStarterProperties starter,
            ServiceAddressResolver resolver,
            ObjectProvider<CapabilityManifest> manifests,
            ObjectProvider<AgentReadRuntime> runtime,
            ObjectProvider<AgentWriteRuntime> writeRuntime) {
        var transport = new HttpAgentRegistryTransport(properties, resolver);
        try {
            return new AgentRegistryLifecycle(
                    transport,
                    java.util.stream.Stream.concat(
                                    java.util.stream.Stream.concat(
                                            manifests.orderedStream(),
                                            runtime.stream().flatMap(value -> value.manifests().stream())),
                                    writeRuntime.stream().flatMap(value -> value.manifests().stream()))
                            .toList(),
                    starter.getProjectCode(),
                    properties.getHeartbeatSeconds());
        } catch (RuntimeException failure) {
            transport.close();
            throw failure;
        }
    }
}
