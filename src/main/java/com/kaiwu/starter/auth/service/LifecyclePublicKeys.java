package com.kaiwu.starter.auth.service;

import com.kaiwu.starter.auth.config.MultiProjectProperties;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 部署证明与生命周期断言共用的离线信任配置，限制两代独立平台 RSA 公钥。 */
public final class LifecyclePublicKeys {
    /** 只允许通过受控配置加载信任根。 */
    private LifecyclePublicKeys() {}

    /** 启动时校验部署身份及全部公钥，未知、弱键和与 Gateway 共用的键均拒绝。 */
    public static Map<String, PublicKey> load(MultiProjectProperties properties, PublicKey gatewayKey) {
        try {
            if (properties == null
                    || properties.appCode() == null
                    || !properties.appCode().matches("[a-z][a-z0-9-]{1,63}")
                    || properties.deploymentId() == null
                    || !properties.deploymentId().matches("[a-z0-9][a-z0-9-]{7,63}")
                    || properties.deploymentPublicKeys().isEmpty()
                    || properties.deploymentPublicKeys().size() > 2) {
                throw new IllegalArgumentException();
            }
            Map<String, PublicKey> keys = new LinkedHashMap<>();
            for (var entry : properties.deploymentPublicKeys().entrySet()) {
                if (entry.getKey().isBlank()
                        || !entry.getKey().equals(entry.getKey().trim())) {
                    throw new IllegalArgumentException();
                }
                var key = parse(entry.getValue());
                if (key.equals(gatewayKey)) {
                    throw new IllegalArgumentException();
                }
                keys.put(entry.getKey(), key);
            }
            return Map.copyOf(keys);
        } catch (Exception invalid) {
            throw new IllegalStateException("Starter 生命周期信任配置无效");
        }
    }

    /** 独占或共用应用均以同一加载器验证生命周期公钥，并排除 Gateway 信任根复用。 */
    public static Map<String, PublicKey> loadConfiguration(MultiProjectProperties properties, String gatewayPem) {
        try {
            return load(properties, parse(gatewayPem));
        } catch (Exception invalid) {
            throw new IllegalStateException("Starter 生命周期信任配置无效");
        }
    }

    /** 从受控 SPKI 配置解析至少 2048 位的 RSA 公钥，拒绝以请求内容建立信任。 */
    private static PublicKey parse(String pem) throws Exception {
        String encoded = pem.replace("\\n", "\n")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        var key = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        if (!(key instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException();
        }
        return key;
    }
}
