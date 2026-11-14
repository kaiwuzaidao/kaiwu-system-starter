package com.kaiwu.starter.agent;

import com.kaiwu.starter.*;
import com.kaiwu.starter.AgentReadRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 执行默认关闭；缺少共享重放存储或项目绑定时装配失败，绝不回退成无认证调用。 */
@AutoConfiguration(
        after = KaiwuStarterAutoConfiguration.class,
        before = AgentRegistryAutoConfiguration.class,
        afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@EnableConfigurationProperties(AgentExecutionProperties.class)
@ConditionalOnProperty(prefix = "kaiwu.starter.agent.execution", name = "enabled", havingValue = "true")
public class AgentExecutionAutoConfiguration {
    @Bean(destroyMethod = "close")
    public AgentReadRuntime kaiwuAgentReadRuntime(
            ObjectProvider<AgentReadCapabilityHandler> handlers,
            KaiwuStarterProperties starter,
            AgentExecutionProperties execution,
            GatewayContextVerifier verifier,
            AgentReplayStore replay,
            PermissionResolver permissions) {
        return new AgentReadRuntime(
                handlers.orderedStream().toList(),
                starter.getProjectCode(),
                execution.getProjectId(),
                verifier,
                replay,
                permissions);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            com.kaiwu.starter.agent.controller.AgentCapabilityController.class)
    public AgentCapabilityController kaiwuAgentCapabilityController(AgentReadRuntime runtime) {
        return new AgentCapabilityController(runtime);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(
            name = "org.springframework.data.redis.core.StringRedisTemplate")
    static class RedisConfiguration {
        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(AgentReplayStore.class)
        @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
                org.springframework.data.redis.core.StringRedisTemplate.class)
        AgentReplayStore kaiwuAgentReplayStore(
                org.springframework.data.redis.core.StringRedisTemplate redis, AgentExecutionProperties properties) {
            return new RedisAgentReplayStore(redis, properties.getProjectId());
        }
    }
}
