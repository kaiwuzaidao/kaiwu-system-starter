package com.kaiwu.starter.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 服务寻址契约（ADR 0024）。
 *
 * <p>Starter 只规定「逻辑服务名交给 LoadBalancer」，不规定注册中心是谁。
 * 这些用例守的是契约本身：scheme 白名单、fail-fast，以及缺依赖时的错误必须可自解释。</p>
 */
class ServiceAddressTest {

    @Test
    void treatsHttpSchemesAsDirectlyResolvable() {
        assertThat(ServiceAddress.parse("http://kaiwu-system:8080").isLoadBalanced())
                .isFalse();
        assertThat(ServiceAddress.parse("https://system.internal").isLoadBalanced())
                .isFalse();
    }

    @Test
    void treatsLbSchemeAsLoadBalanced() {
        ServiceAddress address = ServiceAddress.parse("lb://kaiwu-system-service");
        assertThat(address.isLoadBalanced()).isTrue();
        assertThat(address.serviceName()).isEqualTo("kaiwu-system-service");
    }

    /** 尾部斜杠会和后续拼接的路径凑出双斜杠，解析时就该规整掉。 */
    @Test
    void stripsTrailingSlashes() {
        assertThat(ServiceAddress.parse("http://kaiwu-system:8080///").url()).isEqualTo("http://kaiwu-system:8080");
    }

    @Test
    void rejectsUnsupportedScheme() {
        assertThatThrownBy(() -> ServiceAddress.parse("ftp://system"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不支持的服务地址 scheme");
    }

    @Test
    void rejectsMissingSchemeAndBlank() {
        assertThatThrownBy(() -> ServiceAddress.parse("kaiwu-system:8080")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ServiceAddress.parse("  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能为空");
    }

    /**
     * 只写 scheme 不写服务名一律拒绝。错误信息带上原始地址即可自解释，
     * 不为这个边界单独加特判——{@code lb://} 连合法 URI 都不是，{@code lb:///} 才走到服务名检查。
     */
    @Test
    void rejectsLbWithoutServiceName() {
        assertThatThrownBy(() -> ServiceAddress.parse("lb://"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lb://");
        assertThatThrownBy(() -> ServiceAddress.parse("lb:kaiwu-system"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少服务名");
    }

    /** 直连解析器原样返回地址。 */
    @Test
    void directResolverReturnsConfiguredUrl() {
        ServiceAddressResolver resolver = ServiceAddressResolver.direct();
        assertThat(resolver.resolve(ServiceAddress.parse("http://kaiwu-system:8080")))
                .isEqualTo("http://kaiwu-system:8080");
    }

    /**
     * 没有 LoadBalancer 却配了 lb://：必须报错而不是把服务名当主机名去解析。
     * 静默降级的话，最终症状会是一个和配置毫无关联的 DNS 失败。
     */
    @Test
    void directResolverFailsFastOnLbScheme() {
        ServiceAddressResolver resolver = ServiceAddressResolver.direct();
        assertThatThrownBy(() -> resolver.resolve(ServiceAddress.parse("lb://kaiwu-system-service")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring-cloud-starter-loadbalancer")
                .hasMessageContaining("DiscoveryClient");
    }
}
