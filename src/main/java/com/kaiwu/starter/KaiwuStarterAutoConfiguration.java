package com.kaiwu.starter;

import com.kaiwu.starter.client.ServiceAddressResolver;
import com.kaiwu.starter.config.ProjectConfigClient;
import com.kaiwu.starter.config.ProjectConfigProperties;
import com.kaiwu.starter.logging.HttpAccessLogFilter;
import com.kaiwu.starter.scheduler.HttpSchedulerControlPlane;
import com.kaiwu.starter.scheduler.ProjectScheduledTaskHandler;
import com.kaiwu.starter.scheduler.ProjectSchedulerCoordinator;
import com.kaiwu.starter.scheduler.ProjectSchedulerLifecycle;
import com.kaiwu.starter.scheduler.ProjectSchedulerProperties;
import com.kaiwu.starter.scheduler.ProjectSchedulerRuntime;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Kaiwu Starter 自动装配。
 */
@AutoConfiguration
@EnableConfigurationProperties({
    KaiwuStarterProperties.class,
    NotificationProperties.class,
    ProjectSchedulerProperties.class,
    ProjectConfigProperties.class
})
@ConditionalOnProperty(prefix = "kaiwu.starter", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KaiwuStarterAutoConfiguration {

    /**
     * 统一记录目标服务的安全请求摘要，并在业务处理期间建立 traceId MDC。
     */
    @Bean
    @ConditionalOnMissingBean(HttpAccessLogFilter.class)
    @ConditionalOnProperty(
            prefix = "kaiwu.starter.access-log",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public FilterRegistrationBean<HttpAccessLogFilter> kaiwuHttpAccessLogFilter(Environment environment) {
        String serviceName = environment.getProperty("spring.application.name", "unknown-service");
        FilterRegistrationBean<HttpAccessLogFilter> registration =
                new FilterRegistrationBean<>(new HttpAccessLogFilter(serviceName));
        registration.setName("kaiwuHttpAccessLogFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "kaiwu.starter.scheduler", name = "enabled", havingValue = "true")
    public ProjectSchedulerRuntime kaiwuProjectSchedulerRuntime(
            ProjectSchedulerProperties properties,
            ObjectProvider<ProjectScheduledTaskHandler> handlers,
            ServiceAddressResolver addressResolver) {
        return new ProjectSchedulerRuntime(
                properties.getProjectId(),
                properties.getInstanceId(),
                new HttpSchedulerControlPlane(
                        properties.getSystemBaseUrl(),
                        properties.getCredential(),
                        Duration.ofSeconds(properties.getConnectTimeoutSeconds()),
                        Duration.ofSeconds(properties.getReadTimeoutSeconds()),
                        addressResolver),
                handlers.orderedStream().toList(),
                Duration.ofSeconds(properties.getLeaseRenewIntervalSeconds()),
                properties.getMaxConsecutiveRenewFailures());
    }

    @Bean(name = "kaiwuProjectTaskScheduler", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "kaiwu.starter.scheduler", name = "enabled", havingValue = "true")
    public ThreadPoolTaskScheduler kaiwuProjectTaskScheduler(ProjectSchedulerProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, properties.getPoolSize()));
        scheduler.setThreadNamePrefix("kaiwu-project-task-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "kaiwu.starter.scheduler", name = "enabled", havingValue = "true")
    public ProjectSchedulerCoordinator kaiwuProjectSchedulerCoordinator(
            ProjectSchedulerRuntime runtime,
            @Qualifier("kaiwuProjectTaskScheduler") ThreadPoolTaskScheduler taskScheduler,
            ProjectSchedulerProperties properties) {
        return new ProjectSchedulerCoordinator(
                runtime,
                taskScheduler,
                Clock.systemUTC(),
                Duration.ofSeconds(properties.getClaimRetryDelaySeconds()),
                properties.getMaxClaimRetryAttempts());
    }

    @Bean
    @ConditionalOnProperty(prefix = "kaiwu.starter.scheduler", name = "enabled", havingValue = "true")
    public ProjectSchedulerLifecycle kaiwuProjectSchedulerLifecycle(
            ProjectSchedulerRuntime runtime,
            ProjectSchedulerCoordinator coordinator,
            ProjectSchedulerProperties properties) {
        return new ProjectSchedulerLifecycle(
                runtime, coordinator, Duration.ofSeconds(Math.max(5, properties.getSyncIntervalSeconds())));
    }

    @Bean
    public GatewayContextVerifier kaiwuGatewayContextVerifier(KaiwuStarterProperties properties) {
        return new GatewayContextVerifier(properties);
    }

    /**
     * 站内信投递客户端。未配置 System 地址或投递凭据时客户端保持静默，
     * 业务代码无需做存在性判断（ADR 0007）。
     */
    @Bean
    @ConditionalOnMissingBean(NotificationClient.class)
    public NotificationClient kaiwuNotificationClient(
            NotificationProperties notificationProperties,
            KaiwuStarterProperties starterProperties,
            ServiceAddressResolver addressResolver) {
        return new NotificationClient(notificationProperties, starterProperties.getProjectCode(), addressResolver);
    }

    /**
     * 项目配置读取客户端。
     *
     * <p>项目服务凭据是项目级的，调度与配置共用同一个值，因此未单独配置
     * `kaiwu.starter.config.credential` / `system-base-url` 时回退到调度的同名配置，
     * 免去为同一凭据配置两遍。两者都缺失时客户端整体降级，读取一律返回 fallback。</p>
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ProjectConfigClient.class)
    public ProjectConfigClient kaiwuProjectConfigClient(
            ProjectConfigProperties configProperties,
            ProjectSchedulerProperties schedulerProperties,
            ServiceAddressResolver addressResolver) {
        if (isBlank(configProperties.getCredential())) {
            configProperties.setCredential(schedulerProperties.getCredential());
        }
        if (isBlank(configProperties.getSystemBaseUrl())) {
            configProperties.setSystemBaseUrl(schedulerProperties.getSystemBaseUrl());
        }
        ProjectConfigClient client = new ProjectConfigClient(configProperties, addressResolver);
        client.start();
        return client;
    }

    /**
     * 出站寻址解析器（ADR 0024）。默认直连：地址由底层网络解析，配 {@code lb://} 直接报错并
     * 指出要补的依赖。使用方引入 Spring Cloud LoadBalancer 后由下面的内部配置覆盖为 LB 解析。
     *
     * <p>Starter 只规定「逻辑服务名交给 LoadBalancer」这条契约，不引入任何具体的
     * DiscoveryClient 实现——注册中心是使用方的基础设施选择。</p>
     */
    @Bean
    @ConditionalOnMissingBean(ServiceAddressResolver.class)
    public ServiceAddressResolver kaiwuServiceAddressResolver() {
        return ServiceAddressResolver.direct();
    }

    /**
     * 仅当 classpath 具备 spring-cloud-commons 时装配。条件用类名字符串判断，
     * 避免在缺少 optional 依赖的工程里触发类加载失败。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.cloud.client.loadbalancer.LoadBalancerClient")
    static class LoadBalancedAddressConfiguration {

        @Bean
        @ConditionalOnBean(org.springframework.cloud.client.loadbalancer.LoadBalancerClient.class)
        public ServiceAddressResolver kaiwuLoadBalancedServiceAddressResolver(
                org.springframework.cloud.client.loadbalancer.LoadBalancerClient loadBalancerClient) {
            return new com.kaiwu.starter.client.LoadBalancedServiceAddressResolver(loadBalancerClient);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Bean
    @ConditionalOnMissingBean(PermissionResolver.class)
    public PermissionResolver kaiwuPermissionResolver() {
        return new ContextPermissionResolver();
    }

    @Bean
    public WebMvcConfigurer kaiwuStarterWebMvcConfigurer(
            KaiwuStarterProperties properties, GatewayContextVerifier verifier, PermissionResolver permissionResolver) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new StarterAuthInterceptor(verifier, permissionResolver))
                        .addPathPatterns(properties.getIncludePaths())
                        .excludePathPatterns(properties.getExcludePaths());
            }
        };
    }
}
