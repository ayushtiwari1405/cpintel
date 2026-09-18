package com.cpintel.config;

import com.cpintel.service.RecommendationService;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * @param appMapper Spring Boot's auto-configured ObjectMapper. Copied rather than
     *                  built fresh so the cache inherits the modules Boot registers —
     *                  in particular ParameterNamesModule, without which record-shaped
     *                  payloads like RecommendationPayload have no usable creator.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory, ObjectMapper appMapper) {
        ObjectMapper mapper = appMapper.copy();
        mapper.registerModule(new JavaTimeModule());

        // Default typing is what writes the "@class" hint into each cached value, and
        // without it every cache HIT deserializes to a LinkedHashMap and blows up with a
        // ClassCastException at the @Cacheable call site. GenericJackson2JsonRedisSerializer
        // turns this on itself in its no-arg constructor, but NOT when handed a custom
        // ObjectMapper — so supplying one silently opts out of it.
        //
        // NON_FINAL rather than JAVA_LANG_OBJECT: the latter only tags Object-declared
        // slots and leaves the ROOT value untagged, so nothing could be resolved on read.
        //
        // The validator is an allowlist because default typing will instantiate whatever
        // class the payload names: anything able to write to this Redis could otherwise
        // choose the type. Only our own DTOs and the collection types they nest in.
        mapper.activateDefaultTyping(
            BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.cpintel.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.time.")
                .build(),
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(serializer))
            .disableCachingNullValues();

        // The recommendations cache holds exactly one type, so it can be serialized
        // against that type directly and skip default typing altogether. That matters
        // because RecommendationPayload carries List<Map<String, Object>>, whose values
        // are final types (String, Integer): NON_FINAL writes no hint for them, but the
        // reader demands one for an Object-declared slot, so the round-trip fails. Naming
        // the type removes the question. Caches holding several types (analytics) still
        // need the generic serializer above.
        RedisCacheConfiguration recommendationsConfig = defaultConfig
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new Jackson2JsonRedisSerializer<>(
                    appMapper.copy().registerModule(new JavaTimeModule()),
                    RecommendationService.RecommendationPayload.class)));

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put("analytics",       defaultConfig.entryTtl(Duration.ofHours(6)));
        cacheConfigs.put("recommendations", recommendationsConfig.entryTtl(Duration.ofHours(24)));
        cacheConfigs.put("unified_score",   defaultConfig.entryTtl(Duration.ofHours(12)));
        cacheConfigs.put("user_profile",    defaultConfig.entryTtl(Duration.ofMinutes(30)));

        return RedisCacheManager.builder(factory)
            .cacheDefaults(defaultConfig.entryTtl(Duration.ofHours(1)))
            .withInitialCacheConfigurations(cacheConfigs)
            .build();
    }
}
