package com.bleurubin.budgetanalyzer.currency.config;

import java.time.Duration;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Redis cache configuration for the Currency Service.
 *
 * <p>Configures caching strategy for exchange rate queries with appropriate TTL and serialization
 * settings.
 */
@Configuration
@EnableCaching
public class CacheConfig {

  /** Cache name for exchange rate queries. */
  public static final String EXCHANGE_RATES_CACHE = "exchangeRates";

  /**
   * Creates a type-safe GenericJackson2JsonRedisSerializer with polymorphic type handling.
   *
   * <p>Configures type validator to allow our DTOs and Java standard types for proper
   * serialization/deserialization.
   *
   * @param objectMapper application-wide ObjectMapper with JavaTimeModule registered
   * @return configured serializer with type information
   */
  @Bean
  public GenericJackson2JsonRedisSerializer redisSerializer(ObjectMapper objectMapper) {
    // Create a copy of the ObjectMapper to avoid modifying the application-wide one
    var mapper = objectMapper.copy();

    // Configure type validator to allow our packages and standard Java types
    var typeValidator =
        BasicPolymorphicTypeValidator.builder()
            .allowIfBaseType(Object.class)
            .allowIfSubType("com.bleurubin")
            .allowIfSubType("java.util")
            .allowIfSubType("java.time")
            .build();

    mapper.activateDefaultTyping(typeValidator, ObjectMapper.DefaultTyping.NON_FINAL);

    return new GenericJackson2JsonRedisSerializer(mapper);
  }

  /**
   * Configures Redis cache manager with custom settings for each cache.
   *
   * <p>Exchange rates cache: - TTL: None (rates only change during imports) - Serialization: JSON
   * for human-readable debugging - Key prefix: "currency-service:" for namespace isolation - Cache
   * invalidation: Explicit via @CacheEvict on import
   *
   * @param redisSerializer configured serializer with type information
   * @return customizer for RedisCacheManager
   */
  @Bean
  public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(
      GenericJackson2JsonRedisSerializer redisSerializer) {
    return builder ->
        builder.withCacheConfiguration(
            EXCHANGE_RATES_CACHE,
            RedisCacheConfiguration.defaultCacheConfig()
                // No TTL - rates only change during imports, explicitly evicted via @CacheEvict
                .entryTtl(Duration.ZERO)
                .serializeKeysWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(redisSerializer))
                .prefixCacheNameWith("currency-service:"));
  }

  /**
   * Default Redis cache configuration applied to all caches unless specifically overridden.
   *
   * <p>Settings: - Disable null value caching (fail fast for missing data) - Use JSON serialization
   * for readability - No TTL (data only changes during imports, explicit eviction)
   *
   * @param redisSerializer configured serializer with type information
   * @return default cache configuration
   */
  @Bean
  public RedisCacheConfiguration cacheConfiguration(
      GenericJackson2JsonRedisSerializer redisSerializer) {
    return RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ZERO)
        .disableCachingNullValues()
        .serializeKeysWith(
            RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
        .serializeValuesWith(
            RedisSerializationContext.SerializationPair.fromSerializer(redisSerializer));
  }
}
