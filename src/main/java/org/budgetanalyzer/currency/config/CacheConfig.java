package org.budgetanalyzer.currency.config;

import java.time.Duration;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Redis cache configuration for the Currency Service.
 *
 * <p>All caches, currently only {@link #EXCHANGE_RATES_CACHE}, share the single default {@link
 * RedisCacheConfiguration} defined here:
 *
 * <ul>
 *   <li><b>No TTL:</b> Entries never expire. Exchange rates only change during imports, and every
 *       import evicts all entries with {@code @CacheEvict(allEntries = true)}.
 *   <li><b>Eviction after commit:</b> The cache manager is transaction-aware, so evictions run in
 *       the after-commit phase. A rolled-back import leaves the cache unchanged.
 *   <li><b>Synchronous writes:</b> Writes, evictions, and clears complete before the cache call
 *       returns, overriding Spring Data Redis 4's asynchronous Lettuce default.
 *   <li><b>Key structure:</b> {@code currency-service:{cacheName}::{key}}, for example {@code
 *       currency-service:exchangeRates::EUR:2024-01-01:2024-01-31}. The {@code currency-service:}
 *       prefix prevents collisions with other services sharing the Redis instance.
 *   <li><b>Values:</b> JSON with Jackson default typing restricted to an allowlist of types.
 * </ul>
 *
 * <p>The cache manager uses only this configuration, so {@code spring.cache.redis.*} properties
 * other than {@code cache-names} and {@code enable-statistics} have no effect.
 */
@Configuration
@EnableCaching
public class CacheConfig {

  /** Cache name for exchange rate queries. */
  public static final String EXCHANGE_RATES_CACHE = "exchangeRates";

  /**
   * Creates the JSON serializer for cached values with allowlisted polymorphic typing.
   *
   * <p>The serializer writes a type id for every non-final value ({@link DefaultTyping#NON_FINAL})
   * and accepts only subtypes from {@code org.budgetanalyzer}, {@code java.util}, {@code
   * java.time}, and {@code java.math} when reading. Any other type id is rejected.
   *
   * <p>Final types are written without a type id. Final values that Jackson reads back as their
   * natural JSON type, such as {@link String}, round-trip correctly. Cached values must not be JDK
   * immutable collections such as those returned by {@code List.of()} or {@code Stream.toList()}:
   * their classes are final, so they are written without a type id and cannot be read back. Return
   * {@link java.util.ArrayList} or {@link java.util.Collections#emptyList()} from cacheable methods
   * instead.
   *
   * @param jsonMapper application-wide JsonMapper
   * @return configured serializer with type information
   */
  @Bean
  public GenericJacksonJsonRedisSerializer redisSerializer(JsonMapper jsonMapper) {
    var typeValidator =
        BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("org.budgetanalyzer")
            .allowIfSubType("java.util")
            .allowIfSubType("java.time")
            .allowIfSubType("java.math")
            .build();

    // Rebuild a copy of the JsonMapper to avoid modifying the application-wide one
    var cacheJsonMapper =
        jsonMapper.rebuild().activateDefaultTyping(typeValidator, DefaultTyping.NON_FINAL).build();

    return new GenericJacksonJsonRedisSerializer(cacheJsonMapper);
  }

  /**
   * Configures the Redis cache manager's writer and transaction handling.
   *
   * <p>The cache writer performs writes, evictions, and clears synchronously via {@code
   * immediateWrites()}. {@code transactionAware()} defers cache puts and evictions to the
   * after-commit phase of Spring-managed transactions.
   *
   * @param redisConnectionFactory Redis connection factory used by the synchronous cache writer
   * @return customizer for RedisCacheManager
   */
  @Bean
  public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(
      RedisConnectionFactory redisConnectionFactory) {
    return builder ->
        builder
            .cacheWriter(
                RedisCacheWriter.create(
                    redisConnectionFactory, configurer -> configurer.immediateWrites()))
            .transactionAware();
  }

  /**
   * Default Redis cache configuration applied to all caches.
   *
   * <p>Sets no TTL, disables caching null values, serializes keys as strings, serializes values
   * with {@link #redisSerializer(JsonMapper)}, and prefixes every key with {@code
   * currency-service:}.
   *
   * @param redisSerializer configured serializer with type information
   * @return default cache configuration
   */
  @Bean
  public RedisCacheConfiguration cacheConfiguration(
      GenericJacksonJsonRedisSerializer redisSerializer) {
    return RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ZERO)
        .disableCachingNullValues()
        .serializeKeysWith(
            RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
        .serializeValuesWith(
            RedisSerializationContext.SerializationPair.fromSerializer(redisSerializer))
        .prefixCacheNameWith("currency-service:");
  }
}
