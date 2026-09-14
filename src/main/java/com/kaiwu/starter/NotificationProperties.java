package com.kaiwu.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 站内信投递配置（ADR 0007）。
 *
 * <p>业务服务通过 {@link NotificationClient} 主动向 System 投递站内信。
 * 这是一条只写、单向、失败即降级的窄链路，不得用于查询身份、权限或任何授权判断；
 * ADR 0003 的「Starter 不回调 System 做鉴权」保持不变。
 *
 * <p>{@code deliveryToken} 只从环境变量注入，未配置时客户端保持禁用，
 * 发送调用直接跳过并记录日志，不会阻断业务。
 */
@ConfigurationProperties(prefix = "kaiwu.starter.notification")
public class NotificationProperties {

    private boolean enabled = true;

    /** System 服务基地址，例如 http://kaiwu-system-service:8080；不经 Gateway。 */
    private String systemBaseUrl;

    private String deliveryToken;

    private long timeoutSeconds = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSystemBaseUrl() {
        return systemBaseUrl;
    }

    public void setSystemBaseUrl(String systemBaseUrl) {
        this.systemBaseUrl = systemBaseUrl;
    }

    public String getDeliveryToken() {
        return deliveryToken;
    }

    public void setDeliveryToken(String deliveryToken) {
        this.deliveryToken = deliveryToken;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
