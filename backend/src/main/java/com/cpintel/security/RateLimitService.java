package com.cpintel.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * A fixed-window counter in Redis, used to put a ceiling on the few operations where one caller
 * can spend a lot of somebody else's resources.
 *
 * <p>Redis rather than memory because the counter has to mean the same thing on every instance —
 * a per-process limit multiplies by the replica count, which is the same mistake the platform
 * clients made with {@code Thread.sleep}. It is a fixed window rather than a sliding one because
 * the failure mode of a fixed window — up to twice the limit across a boundary — is irrelevant at
 * these thresholds, and a sliding window costs a sorted set per key to fix it.
 *
 * <p><b>It fails open.</b> If Redis is unreachable the call is allowed. That is the same choice
 * {@link JwtService} makes about revocation and for the same reason: a cache outage should not
 * be able to lock every user out of the product. The tradeoff is real and worth stating plainly —
 * during a Redis outage there is no throttling, so the edge limits in nginx are what remain.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private static final String PREFIX = "ratelimit:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final RateLimitProperties props;

    /**
     * Counts this attempt and says whether it is within the limit.
     *
     * @param bucket what is being limited — {@code "login"}, {@code "run"} — so two limits on the
     *               same key do not share a counter
     * @param key    who is being limited: an address, an email, a user id
     */
    public boolean tryAcquire(String bucket, String key, RateLimitProperties.Rule rule) {
        if (!props.isEnabled() || key == null || key.isBlank()) return true;

        String redisKey = PREFIX + bucket + ":" + key;

        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count == null) return true;

            // The window opens with the first request in it. Checked rather than assumed:
            // if the process died between INCR and EXPIRE on some earlier request, the key
            // would have no expiry and would block this caller forever.
            if (count == 1L || redisTemplate.getExpire(redisKey) < 0) {
                redisTemplate.expire(redisKey, rule.getWindow());
            }

            return count <= rule.getLimit();

        } catch (Exception e) {
            // See the class comment: an unreachable Redis must not become an outage.
            log.warn("Rate limit check failed for {}:{} — allowing the request: {}",
                bucket, key, e.getMessage());
            return true;
        }
    }

    /** Seconds until this bucket resets, for the {@code Retry-After} header. Never below 1. */
    public long retryAfterSeconds(String bucket, String key) {
        try {
            Long ttl = redisTemplate.getExpire(PREFIX + bucket + ":" + key);
            return ttl == null || ttl <= 0 ? 1 : ttl;
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Forgets a caller's attempts.
     *
     * Called when a sign-in succeeds, so that someone who mistyped their password four times and
     * then got it right is not left one attempt from being locked out of their own account.
     */
    public void reset(String bucket, String key) {
        if (key == null || key.isBlank()) return;
        try {
            redisTemplate.delete(PREFIX + bucket + ":" + key);
        } catch (Exception e) {
            log.debug("Could not reset rate limit {}:{}: {}", bucket, key, e.getMessage());
        }
    }

    /** Convenience accessor so callers do not each inject the properties too. */
    public RateLimitProperties rules() {
        return props;
    }
}
