package com.kaiwu.starter.agent;

import java.util.List;
import java.util.UUID;

/** 服务端确认后冻结的一次写动作；所有标识和摘要都应来自已验证的 Gateway Context。 */
public record AgentWriteAction(
        String projectId,
        UUID actionId,
        String userId,
        String sessionId,
        String capabilityCode,
        String version,
        String manifestHash,
        String paramsHash,
        String preconditionsHash,
        String previewHash,
        long expiresAtMillis) {
    public AgentWriteAction {
        bounded(projectId);
        if (actionId == null) throw new IllegalArgumentException("Invalid Agent write action");
        bounded(userId);
        bounded(sessionId);
        bounded(capabilityCode);
        bounded(version);
        hash(manifestHash);
        hash(paramsHash);
        hash(preconditionsHash);
        hash(previewHash);
        if (expiresAtMillis <= 0) throw new IllegalArgumentException("Invalid Agent write action");
    }

    /** 规范化绑定身份、能力、参数、预览和有效期；不使用可歧义的分隔符拼接。 */
    public String bindingHash() {
        return AgentExecutionCodec.digest(new String(
                AgentExecutionCodec.encode(List.of(
                        projectId,
                        actionId.toString(),
                        userId,
                        sessionId,
                        capabilityCode,
                        version,
                        manifestHash,
                        paramsHash,
                        preconditionsHash,
                        previewHash,
                        expiresAtMillis)),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void bounded(String value) {
        if (value == null || value.isBlank() || value.length() > 128)
            throw new IllegalArgumentException("Invalid Agent write action");
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid Agent write action");
    }

    @Override
    public String toString() {
        return "AgentWriteAction[redacted]";
    }
}
