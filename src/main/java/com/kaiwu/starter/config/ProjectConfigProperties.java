package com.kaiwu.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 项目配置读取的接入参数。与调度共用同一份项目服务凭据，未配置时客户端整体降级。
 */
@ConfigurationProperties(prefix = "kaiwu.starter.config")
public class ProjectConfigProperties {

    private boolean enabled = true;

    /** Kaiwu System 地址；业务服务直连 System，不经 Gateway（`/internal` 被网关阻断）。 */
    private String systemBaseUrl;

    /** 项目服务凭据，与 `kaiwu.starter.scheduler.credential` 是同一个值。 */
    private String credential;

    /** 后台刷新间隔；配置读取不在请求路径上发网络请求，全部走内存快照。 */
    private long refreshSeconds = 300;

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

    public String getCredential() {
        return credential;
    }

    public void setCredential(String credential) {
        this.credential = credential;
    }

    public long getRefreshSeconds() {
        return refreshSeconds;
    }

    public void setRefreshSeconds(long refreshSeconds) {
        this.refreshSeconds = refreshSeconds;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
