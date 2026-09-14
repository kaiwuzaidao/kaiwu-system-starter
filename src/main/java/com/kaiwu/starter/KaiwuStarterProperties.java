package com.kaiwu.starter;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kaiwu Starter 配置。
 */
@ConfigurationProperties(prefix = "kaiwu.starter")
public class KaiwuStarterProperties {

    private boolean enabled = true;
    private String contextPublicKey;
    private String issuer = "kaiwu-gateway-service";
    private String audience;
    private String projectCode;
    private int maxContextBytes = 16 * 1024;
    private long clockSkewSeconds = 5;
    private List<String> includePaths = new ArrayList<>(List.of("/api/**"));
    private List<String> excludePaths = new ArrayList<>(List.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/public/i18n/catalog",
            "/actuator/health",
            "/actuator/health/**"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getContextPublicKey() {
        return contextPublicKey;
    }

    public void setContextPublicKey(String contextPublicKey) {
        this.contextPublicKey = contextPublicKey;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public void setProjectCode(String projectCode) {
        this.projectCode = projectCode;
    }

    public int getMaxContextBytes() {
        return maxContextBytes;
    }

    public void setMaxContextBytes(int maxContextBytes) {
        this.maxContextBytes = maxContextBytes;
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public List<String> getIncludePaths() {
        return List.copyOf(includePaths);
    }

    public void setIncludePaths(List<String> includePaths) {
        this.includePaths = includePaths == null ? List.of() : List.copyOf(includePaths);
    }

    public List<String> getExcludePaths() {
        return List.copyOf(excludePaths);
    }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths == null ? List.of() : List.copyOf(excludePaths);
    }
}
