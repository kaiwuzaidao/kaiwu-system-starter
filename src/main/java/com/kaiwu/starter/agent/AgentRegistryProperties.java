package com.kaiwu.starter.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 独立 Agent 注册配置；绝不回退到调度凭据。启用后配置错误应在启动时暴露。 */
@ConfigurationProperties(prefix = "kaiwu.starter.agent")
public class AgentRegistryProperties {
    private boolean enabled;
    private String systemBaseUrl;
    private String credential;
    private int heartbeatSeconds = 60;
    private int timeoutMillis = 5000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public String getSystemBaseUrl() {
        return systemBaseUrl;
    }

    public void setSystemBaseUrl(String value) {
        systemBaseUrl = value;
    }

    public String getCredential() {
        return credential;
    }

    public void setCredential(String value) {
        credential = value;
    }

    public int getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(int value) {
        heartbeatSeconds = value;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int value) {
        timeoutMillis = value;
    }
}
