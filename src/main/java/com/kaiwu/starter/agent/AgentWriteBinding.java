package com.kaiwu.starter.agent;

/** SDK 期望的写用途凭证绑定值；由固定路径和冻结请求构造，不接受客户端身份字段。 */
public record AgentWriteBinding(
        String operation,
        String code,
        String version,
        String manifestHash,
        String paramsHash,
        String preconditionsHash,
        String previewHash,
        String actionId,
        Long actionExpiresAt,
        String correlationId) {
    public AgentWriteBinding {
        if (!("PREVIEW".equals(operation) || "EXECUTE".equals(operation) || "RESULT".equals(operation))
                || code == null
                || version == null
                || correlationId == null
                || !correlationId.matches("[A-Za-z0-9-]{1,64}")) throw AgentExecutionCodec.invalid();
        hash(manifestHash);
        hash(paramsHash);
        if ("PREVIEW".equals(operation)) {
            if (preconditionsHash != null || previewHash != null || actionId != null || actionExpiresAt != null)
                throw AgentExecutionCodec.invalid();
        } else {
            hash(preconditionsHash);
            hash(previewHash);
            if (actionId == null
                    || !actionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || actionExpiresAt == null
                    || actionExpiresAt <= 0) throw AgentExecutionCodec.invalid();
        }
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw AgentExecutionCodec.invalid();
    }

    @Override
    public String toString() {
        return "AgentWriteBinding[redacted]";
    }
}
