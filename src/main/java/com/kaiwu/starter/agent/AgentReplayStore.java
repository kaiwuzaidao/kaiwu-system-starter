package com.kaiwu.starter.agent;

import java.time.Instant;

/**
 * 跨所有业务实例的单次消费契约，通常用 Redis SET NX + TTL 实现。
 * 原子地保留 jti 至 expiresAt；不可用必须抛异常，禁止本地内存回退或提前删除。
 */
@FunctionalInterface
public interface AgentReplayStore {
    boolean claim(String jti, Instant expiresAt);
}
