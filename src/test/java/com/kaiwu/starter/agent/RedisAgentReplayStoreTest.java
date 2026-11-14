package com.kaiwu.starter.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;

class RedisAgentReplayStoreTest {
    @Test
    void usesOneAtomicProjectBoundClaimAndFailsClosed() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var now = Instant.parse("2026-09-23T00:00:00Z");
        var store = new RedisAgentReplayStore(redis, "123", Clock.fixed(now, ZoneOffset.UTC));
        when(values.setIfAbsent("kaiwu:agent:consumed:123:jti", "1", Duration.ofSeconds(120)))
                .thenReturn(true, false, null);
        assertThat(store.claim("jti", now.plusSeconds(120))).isTrue();
        assertThat(store.claim("jti", now.plusSeconds(120))).isFalse();
        assertThatThrownBy(() -> store.claim("jti", now.plusSeconds(120))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.claim("jti", now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.claim("jti", now.plusSeconds(181))).isInstanceOf(IllegalArgumentException.class);
        verify(redis, never()).delete(anyString());
    }
}
