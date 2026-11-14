package com.kaiwu.starter.agent;

/** 原始 JSON 在 Gateway Context 中按 UTF-8 字节摘要绑定；预览阶段不携带 actionId。 */
public record AgentWriteInvocation(
        String code,
        String version,
        String manifestHash,
        String paramsJson,
        String preconditionsJson,
        String previewHash,
        String actionId,
        Long actionExpiresAt,
        String correlationId) {
    @Override
    public String toString() {
        return "AgentWriteInvocation[redacted]";
    }
}
