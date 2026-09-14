package com.kaiwu.starter.client;

/**
 * 把 {@link ServiceAddress} 解析成本次请求可直接使用的 {@code http(s)://host:port}（ADR 0024）。
 *
 * <p>每次出站请求都调用一次而不是构造时缓存一次：{@code lb://} 的负载均衡语义就是"每次选一个"，
 * 缓存下来等于把负载均衡退化成固定实例，实例下线后还会一直打到死节点。</p>
 *
 * <p>实现只有两个，且<b>只有它们允许判断 scheme</b>：直连实现与 LoadBalancer 实现。
 * 调用方拿到的永远是一个可直接发请求的地址，不需要也不应该再判断 {@code lb://}。</p>
 */
@FunctionalInterface
public interface ServiceAddressResolver {

    /**
     * @param address 配置的目标地址
     * @return 可直接发起请求的地址，不含尾部斜杠
     * @throws IllegalStateException 需要 LoadBalancer 却不可用，或没有可用实例
     */
    String resolve(ServiceAddress address);

    /**
     * 直连解析：原样返回配置地址。
     *
     * <p>遇到 {@code lb://} 一律失败，并直指需要补的依赖。这条路径必须报错而不是降级——
     * 静默退回直连会把逻辑服务名当作主机名去解析，最终表现为莫名其妙的 DNS 失败。</p>
     */
    static ServiceAddressResolver direct() {
        return address -> {
            if (address.isLoadBalanced()) {
                throw new IllegalStateException("配置了 lb:// 但当前没有 Spring Cloud LoadBalancer："
                        + "请添加 spring-cloud-starter-loadbalancer 以及一个 DiscoveryClient 实现"
                        + "（如 spring-cloud-starter-alibaba-nacos-discovery），"
                        + "或把地址改为 http(s):// 形式");
            }
            return address.url();
        };
    }
}
