package com.kaiwu.starter.client;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;

/**
 * 经 Spring Cloud LoadBalancer 解析 {@code lb://}（ADR 0024）。
 *
 * <p>本类是 Starter 中唯一引用 Spring Cloud 类型的地方，只在 classpath 确实具备
 * {@code spring-cloud-commons} 时才会被装配——装配条件写在
 * {@code KaiwuStarterAutoConfiguration} 的内部配置类上，用类名字符串判断，
 * 不会在缺依赖时触发类加载。</p>
 *
 * <p>Starter 不引入任何具体的 {@code DiscoveryClient} 实现：注册中心是使用方的基础设施选择，
 * 平台只规定"逻辑服务名交给 LoadBalancer"这条契约。</p>
 */
public final class LoadBalancedServiceAddressResolver implements ServiceAddressResolver {

    private final LoadBalancerClient loadBalancerClient;

    public LoadBalancedServiceAddressResolver(LoadBalancerClient loadBalancerClient) {
        this.loadBalancerClient = loadBalancerClient;
    }

    @Override
    public String resolve(ServiceAddress address) {
        if (!address.isLoadBalanced()) {
            return address.url();
        }
        ServiceInstance instance = loadBalancerClient.choose(address.serviceName());
        if (instance == null) {
            // 注册中心里没有健康实例。必须报错而不是回退到某个猜测地址：
            // 这类故障要么是服务没起来，要么是注册中心探测不通，两者都需要人看见。
            throw new IllegalStateException("没有可用实例：service=" + address.serviceName() + "；请确认该服务已注册且健康检查通过");
        }
        return instance.getUri().toString().replaceAll("/+$", "");
    }
}
