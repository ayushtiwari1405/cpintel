package com.cpintel.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

/**
 * A cache that cannot be read or written is skipped, not an error.
 *
 * <p>Redis runs with {@code noeviction}, because it also holds things that must not silently
 * disappear: exam access grants and the list of revoked sign-ins. The price is that a full
 * Redis refuses writes instead of dropping old keys. For the {@code @Cacheable} results that is
 * harmless — the value is computed again — so a failed cache operation is logged and the call
 * goes ahead without it, rather than failing the request that happened to hit it.
 */
@Configuration
@Slf4j
public class CacheErrorConfig implements CachingConfigurer {

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache read failed on {}; computing instead: {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Cache write failed on {}: {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache evict failed on {}: {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("Cache clear failed on {}: {}", cache.getName(), e.getMessage());
            }
        };
    }
}
