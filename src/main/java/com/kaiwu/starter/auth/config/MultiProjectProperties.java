package com.kaiwu.starter.auth.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 受控部署配置，由平台注入，与普通项目配置分离。 */
@ConfigurationProperties("kaiwu.starter.multi-project")
public record MultiProjectProperties(
        String serviceCode,
        String appCode,
        String deploymentId,
        String releaseDigest,
        String deploymentProof,
        Map<String, String> deploymentPublicKeys,
        Duration registryTimeout) {

    /** 为未配置的登记读取设定默认时限。 */
    public MultiProjectProperties {
        deploymentPublicKeys = deploymentPublicKeys == null ? Map.of() : Map.copyOf(deploymentPublicKeys);
        registryTimeout = registryTimeout == null ? Duration.ofMillis(500) : registryTimeout;
    }

    /** 避免配置打印时回显签名证明。 */
    @Override
    public String toString() {
        return "MultiProjectProperties[受控部署配置]";
    }
}
