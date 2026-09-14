package com.kaiwu.starter.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * 出站目标地址及其解析方式（ADR 0024）。
 *
 * <p>Kaiwu 不提供部署模式开关，寻址方式由 URI scheme 自己表达：</p>
 * <ul>
 *   <li>{@code http(s)://} —— 交给底层网络解析。固定地址、内网 DNS 与
 *       Kubernetes Service DNS 都走这一条。</li>
 *   <li>{@code lb://} —— 目标是逻辑服务名，交给 Spring Cloud LoadBalancer。
 *       背后是 Nacos、Consul 还是 Spring Cloud Kubernetes，由使用方的依赖决定，
 *       Starter 不做选择（ADR 0024 §7：平台规定契约，不规定基础设施）。</li>
 * </ul>
 *
 * <p>本类<b>刻意不引用任何 Spring Cloud 类型</b>。Starter 对
 * {@code spring-cloud-commons} 是 optional 依赖，使用方不引入时这些类根本不在 classpath，
 * 一旦在字段或方法签名上出现就会在类加载期直接失败。真正需要 LoadBalancer 的部分隔离在
 * {@code LoadBalancedServiceAddressResolver} 中，只有确认 classpath 具备时才会被触碰。</p>
 */
public final class ServiceAddress {

    private static final String LOAD_BALANCED_SCHEME = "lb";
    private static final List<String> DIRECT_SCHEMES = List.of("http", "https");

    private final String url;
    private final String scheme;
    private final String serviceName;

    private ServiceAddress(String url, String scheme, String serviceName) {
        this.url = url;
        this.scheme = scheme;
        this.serviceName = serviceName;
    }

    /**
     * 解析配置的服务地址。
     *
     * @param configuredUrl {@code http(s)://host:port} 或 {@code lb://service-name}
     * @return 解析结果
     * @throws IllegalStateException 地址为空、缺少 scheme，或 scheme 不在白名单内
     */
    public static ServiceAddress parse(String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            throw new IllegalStateException("服务地址不能为空");
        }
        String normalized = stripTrailingSlashes(configuredUrl.trim());
        URI uri;
        try {
            uri = new URI(normalized);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("服务地址不是合法 URI：" + normalized, exception);
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalStateException("服务地址缺少 scheme：" + normalized + "；需要 http://、https:// 或 lb:// 前缀");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (LOAD_BALANCED_SCHEME.equals(scheme)) {
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalStateException("lb:// 地址缺少服务名：" + normalized);
            }
            return new ServiceAddress(normalized, scheme, host);
        }
        if (!DIRECT_SCHEMES.contains(scheme)) {
            throw new IllegalStateException("不支持的服务地址 scheme：" + scheme + "；只接受 http、https 或 lb");
        }
        return new ServiceAddress(normalized, scheme, null);
    }

    /**
     * 去掉尾部斜杠，但保留 {@code scheme://} 的双斜杠——否则 {@code lb://} 会被规整成
     * {@code lb:}，错误信息变成「不是合法 URI」而不是真正的原因「缺少服务名」。
     */
    private static String stripTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/") && !result.endsWith("://")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 是否需要经 LoadBalancer 解析。 */
    public boolean isLoadBalanced() {
        return LOAD_BALANCED_SCHEME.equals(scheme);
    }

    /** 原始地址（已去除尾部斜杠）。 */
    public String url() {
        return url;
    }

    /** {@code lb://} 时的逻辑服务名；直连时为 {@code null}。 */
    public String serviceName() {
        return serviceName;
    }

    @Override
    public String toString() {
        return url;
    }
}
