package org.budgetanalyzer.currency.config;

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
            .allowIfSubType("org.budgetanalyzer")
            .allowIfSubType("java.util")
            .allowIfSubType("java.time")
            .build();

    mapper.activateDefaultTyping(typeValidator, ObjectMapper.DefaultTyping.NON_FINAL);

    return new GenericJackson2JsonRedisSerializer(mapper);
  }

  /**
   * Configures Redis cache manager with custom settings for each cache.
   *
   * <p><b>Exchange Rates Cache Configuration:</b>
   *
   * <ul>
   *   <li><b>TTL:</b> None (Duration.ZERO = infinite) - Exchange rates only change during imports,
   *       so time-based expiration is unnecessary. Cache is explicitly evicted via
   *       {@code @CacheEvict} when rates are imported.
   *   <li><b>Eviction Strategy:</b> Manual only - All import operations use
   *       {@code @CacheEvict(allEntries = true)} to clear the entire cache. This trades targeted
   *       eviction complexity for simplicity and consistency guarantees.
   *   <li><b>Transaction Awareness:</b> Cache operations are synchronized with Spring-managed
   *       transactions via {@code transactionAware()}. Cache eviction is deferred until the
   *       after-commit phase of successful transactions. If a transaction rolls back, cache
   *       eviction does NOT occur, maintaining consistency between cache and database.
   *   <li><b>Key Structure:</b> {@code
   *       currency-service:exchangeRates::{currencyCode}:{startDate}:{endDate}} - Enables
   *       currency-specific cache isolation (THB queries don't collide with EUR queries)
   *   <li><b>Serialization:</b> JSON (GenericJackson2JsonRedisSerializer) for human-readable
   *       debugging and cross-language compatibility
   *   <li><b>Namespace:</b> "currency-service:" prefix prevents key collisions with other
   *       microservices sharing the same Redis instance
   * </ul>
   *
   * <p><b>Multi-Currency Considerations:</b> While the cache key includes currency code for
   * isolation, eviction is global (all currencies evicted simultaneously). This design choice
   * prioritizes simplicity over per-currency optimization, which is acceptable given infrequent
   * import operations (once per day).
   *
   * @param redisSerializer configured serializer with type information
   * @return customizer for RedisCacheManager
   */
  @Bean
  public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(
      GenericJackson2JsonRedisSerializer redisSerializer) {
    return builder ->
        builder
            .transactionAware() // Synchronize cache operations with Spring transactions
            .withCacheConfiguration(
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
