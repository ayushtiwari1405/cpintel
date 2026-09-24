package com.cpintel.roadmap;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** Attempts in Redis, expiring after two hours — ten minutes' work with a long margin. */
@Component
@RequiredArgsConstructor
public class RedisGauntletAttempts implements GauntletAttempts {

    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    private static String key(String attemptId) {
        return "cpintel:gauntlet:attempt:" + attemptId;
    }

    @Override
    public void save(String attemptId, Attempt attempt) {
        try {
            redis.opsForValue().set(key(attemptId), json.writeValueAsString(attempt), TTL);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not store a gauntlet attempt", e);
        }
    }

    @Override
    public Optional<Attempt> find(String attemptId) {
        String stored = redis.opsForValue().get(key(attemptId));
        if (stored == null) return Optional.empty();
        try {
            return Optional.of(json.readValue(stored, Attempt.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String attemptId) {
        redis.delete(key(attemptId));
    }
}
