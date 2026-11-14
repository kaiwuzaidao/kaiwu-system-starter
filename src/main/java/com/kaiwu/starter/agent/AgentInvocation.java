package com.kaiwu.starter.agent;

/** 参数以原始 JSON 字符串传递，摘要绑定其 UTF-8 字节，避免跨服务数值序列化漂移。 */
public record AgentInvocation(
        String code, String version, String manifestHash, String paramsJson, String correlationId) {
    @Override
    public String toString() {
        return "AgentInvocation[redacted]";
    }
}
