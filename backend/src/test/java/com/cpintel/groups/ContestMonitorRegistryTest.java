package com.cpintel.groups;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContestMonitorRegistryTest {

    @Test
    @DisplayName("Redis down: submissions are not refused for a heartbeat nobody could record")
    @SuppressWarnings("unchecked")
    void redisDownFailsOpen() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisConnectionFailureException down = new RedisConnectionFailureException("down");
        when(redis.hasKey(anyString())).thenThrow(down);
        doThrow(down).when(values).set(anyString(), anyString(), any(Duration.class));
        ContestMonitorRegistry registry = new ContestMonitorRegistry(redis);

        assertDoesNotThrow(() -> registry.beat(1L, 2L));
        assertTrue(registry.isMonitored(1L, 2L));
    }

    @Test
    @DisplayName("Redis up and no heartbeat: not monitored, as before")
    void noHeartbeat() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(false);
        assertFalse(new ContestMonitorRegistry(redis).isMonitored(1L, 2L));
    }
}
