package com.kaiwu.starter.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 注册开关不隐含启用执行；执行还必须配置固定项目 ID 和跨实例重放存储。 */
@ConfigurationProperties(prefix = "kaiwu.starter.agent.execution")
public class AgentExecutionProperties {
    private boolean enabled;
    private String projectId;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String value) {
        projectId = value;
    }
}
