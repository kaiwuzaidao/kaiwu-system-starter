package com.kaiwu.starter.auth.model;

import java.util.List;

/** 方法、原始路径模板和精确 scope 构成生命周期消费白名单。 */
public record LifecycleEndpoint(String method, String pathTemplate, String scope) {
    /** ADR 0045 §4a 的固定端点，应用可选择其中子集，不得以路径前缀授权。 */
    public static List<LifecycleEndpoint> standardEndpoints() {
        return List.of(
                new LifecycleEndpoint("GET", "/internal/apps/uninstall-blockers", "app:lifecycle:read"),
                new LifecycleEndpoint("GET", "/internal/apps/projects/{projectCode}/enablement", "app:lifecycle:read"),
                new LifecycleEndpoint("PUT", "/internal/apps/projects/{projectCode}/enablement", "app:lifecycle:write"),
                new LifecycleEndpoint(
                        "DELETE", "/internal/apps/projects/{projectCode}/enablement", "app:lifecycle:write"),
                new LifecycleEndpoint("POST", "/internal/apps/projects/{projectCode}/freeze", "app:lifecycle:write"),
                new LifecycleEndpoint("POST", "/internal/apps/projects/{projectCode}/unfreeze", "app:lifecycle:write"));
    }
}
