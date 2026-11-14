package com.kaiwu.starter.agent;

import com.kaiwu.starter.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 写端点需单独启用，并由业务方显式提供同事务账本及固定 Handler。 */
@AutoConfiguration(
        after = {KaiwuStarterAutoConfiguration.class, AgentExecutionAutoConfiguration.class},
        before = AgentRegistryAutoConfiguration.class,
        afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@EnableConfigurationProperties(AgentWriteProperties.class)
@ConditionalOnProperty(prefix = "kaiwu.starter.agent.write", name = "enabled", havingValue = "true")
public class AgentWriteAutoConfiguration {
    @Bean(destroyMethod = "close")
    public AgentWriteRuntime kaiwuAgentWriteRuntime(
            ObjectProvider<AgentWriteCapabilityHandler> handlers,
            KaiwuStarterProperties starter,
            AgentWriteProperties write,
            ObjectProvider<AgentExecutionProperties> read,
            GatewayContextVerifier verifier,
            AgentReplayStore replay,
            AgentWriteLedger ledger,
            PermissionResolver permissions) {
        AgentExecutionProperties readProperties = read.getIfAvailable();
        if (readProperties != null
                && readProperties.isEnabled()
                && !java.util.Objects.equals(readProperties.getProjectId(), write.getProjectId()))
            throw new IllegalStateException("Agent read and write project bindings differ");
        return new AgentWriteRuntime(
                handlers.orderedStream().toList(),
                starter.getProjectCode(),
                write.getProjectId(),
                verifier,
                replay,
                ledger,
                permissions);
    }

    @Bean
    @ConditionalOnMissingBean(com.kaiwu.starter.agent.controller.AgentWriteCapabilityController.class)
    public AgentWriteCapabilityController kaiwuAgentWriteCapabilityController(AgentWriteRuntime runtime) {
        return new AgentWriteCapabilityController(runtime);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    static class RedisConfiguration {
        @Bean
        @ConditionalOnMissingBean(AgentReplayStore.class)
        @ConditionalOnBean(org.springframework.data.redis.core.StringRedisTemplate.class)
        AgentReplayStore kaiwuAgentWriteReplayStore(
                org.springframework.data.redis.core.StringRedisTemplate redis, AgentWriteProperties properties) {
            return new RedisAgentReplayStore(redis, properties.getProjectId());
        }
    }
}
