package com.kaiwu.starter.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 所有实例必须使用同一 Redis；SET NX + TTL 失败时直接拒绝，Handler 失败也不删除标记。 */
public final class RedisAgentReplayStore implements AgentReplayStore {
    private final StringRedisTemplate redis;
    private final String prefix;
    private final Clock clock;

    public RedisAgentReplayStore(StringRedisTemplate redis, String projectId) {
        this(redis, projectId, Clock.systemUTC());
    }

    public RedisAgentReplayStore(StringRedisTemplate redis, String projectId, Clock clock) {
        if (projectId == null || !projectId.matches("[1-9][0-9]{0,19}"))
            throw new IllegalArgumentException("Invalid Agent project");
        this.redis = java.util.Objects.requireNonNull(redis);
        this.prefix = "kaiwu:agent:consumed:" + projectId + ":";
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    @Override
    public boolean claim(String jti, Instant expiresAt) {
        if (jti == null || !jti.matches("[A-Za-z0-9-]{1,128}")) throw new IllegalArgumentException("Invalid Agent jti");
        Duration ttl = Duration.between(clock.instant(), expiresAt);
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(3)) > 0)
            throw new IllegalArgumentException("Invalid Agent expiry");
        Boolean claimed = redis.opsForValue().setIfAbsent(prefix + jti, "1", ttl);
        if (claimed == null) throw new IllegalStateException("Agent replay storage unavailable");
        return claimed;
    }
}
