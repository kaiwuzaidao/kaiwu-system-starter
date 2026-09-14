package com.kaiwu.starter.scheduler;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kaiwu.starter.scheduler")
public class ProjectSchedulerProperties {

    private boolean enabled;
    private String systemBaseUrl;
    private String projectId;
    private String credential;
    private String instanceId = "instance-" + UUID.randomUUID();
    private long syncIntervalSeconds = 30;
    /**
     * 调度线程池大小。
     *
     * <p>该池同时承担 Cron 触发与 Handler 执行：Handler 在触发线程内同步运行，
     * 因此长任务会占用线程。池满时其它任务到点不会被触发，而是排队等线程，
     * 表现为任务延迟而非报错。默认值按“少量任务 + 偶发长任务”取 4；
     * 任务较多或存在长任务时，应调大到并发任务数加余量。
     */
    private int poolSize = 4;

    private long connectTimeoutSeconds = 3;
    private long readTimeoutSeconds = 10;
    private long leaseRenewIntervalSeconds = 60;
    private int maxConsecutiveRenewFailures = 3;
    private long claimRetryDelaySeconds = 360;
    private int maxClaimRetryAttempts = 2;

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

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getCredential() {
        return credential;
    }

    public void setCredential(String credential) {
        this.credential = credential;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public long getSyncIntervalSeconds() {
        return syncIntervalSeconds;
    }

    public void setSyncIntervalSeconds(long syncIntervalSeconds) {
        this.syncIntervalSeconds = syncIntervalSeconds;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public long getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(long connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public long getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(long readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public long getLeaseRenewIntervalSeconds() {
        return leaseRenewIntervalSeconds;
    }

    public void setLeaseRenewIntervalSeconds(long leaseRenewIntervalSeconds) {
        this.leaseRenewIntervalSeconds = leaseRenewIntervalSeconds;
    }

    public int getMaxConsecutiveRenewFailures() {
        return maxConsecutiveRenewFailures;
    }

    public void setMaxConsecutiveRenewFailures(int maxConsecutiveRenewFailures) {
        this.maxConsecutiveRenewFailures = maxConsecutiveRenewFailures;
    }

    public long getClaimRetryDelaySeconds() {
        return claimRetryDelaySeconds;
    }

    public void setClaimRetryDelaySeconds(long claimRetryDelaySeconds) {
        this.claimRetryDelaySeconds = claimRetryDelaySeconds;
    }

    public int getMaxClaimRetryAttempts() {
        return maxClaimRetryAttempts;
    }

    public void setMaxClaimRetryAttempts(int maxClaimRetryAttempts) {
        this.maxClaimRetryAttempts = maxClaimRetryAttempts;
    }
}
