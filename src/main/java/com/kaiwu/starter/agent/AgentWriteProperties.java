package com.kaiwu.starter.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 写入口单独显式启用；不能继承只读执行开关。 */
@ConfigurationProperties(prefix = "kaiwu.starter.agent.write")
public class AgentWriteProperties {
    private boolean enabled;
    private String projectId;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }
}
