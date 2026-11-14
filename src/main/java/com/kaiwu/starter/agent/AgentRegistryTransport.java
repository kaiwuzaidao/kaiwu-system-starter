package com.kaiwu.starter.agent;

/** 注册专用传输，不具备远程执行方法。 */
public interface AgentRegistryTransport extends AutoCloseable {
    record Revision(String capabilityCode, String version, String manifestHash) {}

    Revision register(String code, String version, byte[] manifest);

    void heartbeat(Revision revision);

    @Override
    default void close() {}
}
