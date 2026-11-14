package com.kaiwu.starter.auth.service;

import com.kaiwu.starter.auth.config.MultiProjectProperties;
import io.jsonwebtoken.Jwts;
import java.security.PublicKey;
import org.springframework.util.StringUtils;

/** 离线验证平台签发的应用部署资格，使用独立于 Gateway 的受控公钥。 */
public final class DeploymentProofVerifier {

    /** 静态验证入口，不创建实例。 */
    private DeploymentProofVerifier() {}

    /** 验证证明与本地部署及发行物绑定，所有失败都拒绝启动。 */
    public static void verify(MultiProjectProperties deployment, PublicKey gatewayKey) {
        try {
            require(deployment != null
                    && StringUtils.hasText(deployment.deploymentId())
                    && StringUtils.hasText(deployment.serviceCode())
                    && StringUtils.hasText(deployment.deploymentProof())
                    && deployment.releaseDigest() != null
                    && deployment.releaseDigest().matches("[0-9a-fA-F]{64}"));
            var keys = LifecyclePublicKeys.load(deployment, gatewayKey);
            var proof = Jwts.parser()
                    .keyLocator(header -> {
                        require("kaiwu-app-deployment+jwt".equals(header.get("typ"))
                                && "RS256".equals(header.get("alg"))
                                && header.get("kid") instanceof String kid
                                && StringUtils.hasText(kid));
                        PublicKey key = keys.get(header.get("kid"));
                        require(key != null);
                        return key;
                    })
                    .requireIssuer("kaiwu-system")
                    .require("appCode", deployment.appCode())
                    .require("deploymentId", deployment.deploymentId())
                    .require("releaseDigest", deployment.releaseDigest())
                    .require("tenancy", "multi-project")
                    .build()
                    .parseSignedClaims(deployment.deploymentProof())
                    .getPayload();
            require(StringUtils.hasText(proof.get("appCode", String.class))
                    && StringUtils.hasText(proof.get("appProjectId", String.class))
                    && proof.getIssuedAt() != null);
        } catch (RuntimeException invalid) {
            // 不透出解析器携带的证明或声明。
            throw new IllegalStateException("Starter 多项目部署证明无效");
        }
    }

    /** 无法证明资格时立即拒绝。 */
    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException();
        }
    }
}
