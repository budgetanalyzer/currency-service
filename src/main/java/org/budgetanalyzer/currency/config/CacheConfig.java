package org.budgetanalyzer.currency.config;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.budgetanalyzer.currency.service.dto.ExchangeRateSeries;

/**
 * Redis cache configuration for the Currency Service.
 *
 * <p>The cache lives in the dedicated, disposable {@code redis-cache} instance, which every
 * currency-service replica shares. All caches, currently only {@link #EXCHANGE_RATE_SERIES_CACHE},
 * share the single default {@link RedisCacheConfiguration} defined here:
 *
 * <ul>
 *   <li><b>One-hour TTL:</b> Entries expire {@link #EXCHANGE_RATES_TTL} after they are written.
 *       Imports evict all entries with {@code @CacheEvict(allEntries = true)} after they commit.
 *   <li><b>Stale writes bounded by the TTL:</b> A read that queried the database before an import
 *       committed can cache its stale series after that import's eviction, and a failed
 *       after-commit eviction leaves the previous series in place. The TTL bounds how long either
 *       survives.
 *   <li><b>Eviction after commit:</b> Each cache is transaction-aware, so puts and evictions inside
 *       a transaction run in the after-commit phase. A rolled-back import leaves the cache
 *       unchanged.
 *   <li><b>Fail-open:</b> {@link FailOpenCache} logs and absorbs every cache failure, including
 *       after-commit puts and evictions. A failed read is a miss served from the database; a failed
 *       write or eviction is skipped. A Redis outage makes requests slower, by up to the Redis
 *       command timeout per cache call, but never fails them.
 *   <li><b>Synchronous writes:</b> Writes, evictions, and clears complete before the cache call
 *       returns, overriding Spring Data Redis 4's asynchronous Lettuce default.
 *   <li><b>Incremental clears:</b> Clearing a cache walks its keys with {@code SCAN} and deletes
 *       them in batches of {@link #CACHE_CLEAR_BATCH_SIZE}, instead of the default single {@code
 *       KEYS} pass, so the service's Redis user needs no {@code KEYS} permission.
 *   <li><b>Key structure:</b> {@code currency-service:{cacheName}::{key}}, for example {@code
 *       currency-service:exchangeRateSeries::EUR}. The key is the currency code, so the number of
 *       entries is bounded by the number of currencies. The service's Redis ACL user may only
 *       access keys with the {@code currency-service:} prefix.
 *   <li><b>Values:</b> compact JSON bound to {@link ExchangeRateSeries}, without type ids.
 * </ul>
 *
 * <p>The cache manager is defined here, so no {@code spring.cache.redis.*} property has an effect;
 * {@code spring.cache.type} only selects between this Redis cache manager ({@code redis}) and
 * Spring Boot's cache manager for another type, such as {@code none}.
 */
@Configuration
@EnableCaching
public class CacheConfig {

  /** Cache name for the per-currency exchange-rate series, keyed by currency code. */
  public static final String EXCHANGE_RATE_SERIES_CACHE = "exchangeRateSeries";

  /** Time-to-live for exchange rate cache entries, counted from when each entry is written. */
  public static final Duration EXCHANGE_RATES_TTL = Duration.ofHours(1);

  /**
   * Number of keys requested per {@code SCAN} call and deleted per {@code DEL} call when a cache is
   * cleared.
   *
   * <p>Each call stays short, so cache reads from other currency-service replicas run between
   * batches instead of waiting for one full keyspace pass.
   */
  public static final int CACHE_CLEAR_BATCH_SIZE = 1000;

  /**
   * Creates the Redis cache manager when {@code spring.cache.type=redis}.
   *
   * <p>With any other cache type, such as {@code none} in most tests, this bean is absent and
   * Spring Boot configures the cache manager for that type. When present, Spring Boot's cache
   * manager auto-configuration backs off, so no {@code spring.cache.redis.*} property applies.
   *
   * <p>The cache writer performs writes, evictions, and clears synchronously via {@code
   * immediateWrites()}. It clears a cache with {@code SCAN} and {@code DEL} in batches of {@link
   * #CACHE_CLEAR_BATCH_SIZE} rather than with {@code KEYS}. Like {@code KEYS}, this is not a
   * point-in-time delete: a key written while the scan runs may survive until its TTL expires.
   *
   * <p>Each cache is decorated as {@code
   * TransactionAwareCacheDecorator(FailOpenCache(RedisCache))}: puts, evictions, and clears inside
   * a Spring-managed transaction run after it commits, and every Redis failure, including one in
   * those after-commit operations, is logged and absorbed by {@link FailOpenCache}. The decoration
   * does not depend on the order of the transaction and cache interceptors.
   *
   * @param redisConnectionFactory Redis connection factory used by the synchronous cache writer
   * @param redisCacheConfiguration configuration applied to every cache
   * @return fail-open, transaction-aware Redis cache manager
   */
  @Bean
  @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
  public RedisCacheManager cacheManager(
      RedisConnectionFactory redisConnectionFactory,
      RedisCacheConfiguration redisCacheConfiguration) {
    var redisCacheWriter =
        RedisCacheWriter.create(
            redisConnectionFactory,
            configurer ->
                configurer
                    .immediateWrites()
                    .batchStrategy(BatchStrategies.scan(CACHE_CLEAR_BATCH_SIZE)));
    return new FailOpenRedisCacheManager(redisCacheWriter, redisCacheConfiguration);
  }

  /**
   * Default Redis cache configuration applied to all caches.
   *
   * <p>Sets a TTL of {@link #EXCHANGE_RATES_TTL}, disables caching null values, serializes keys as
   * strings, serializes values as JSON bound to {@link ExchangeRateSeries}, and prefixes every key
   * with {@code currency-service:}.
   *
   * <p>The value serializer uses a copy of the application {@link JsonMapper} with pretty printing
   * disabled, because indentation only enlarges cached values. It writes no type ids and reads
   * every value as {@link ExchangeRateSeries}, so a cache may only hold that type.
   *
   * @param jsonMapper application-wide JsonMapper, left unchanged
   * @return default cache configuration
   */
  @Bean
  public RedisCacheConfiguration cacheConfiguration(JsonMapper jsonMapper) {
    var cacheJsonMapper = jsonMapper.rebuild().disable(SerializationFeature.INDENT_OUTPUT).build();
    var exchangeRateSeriesSerializer =
        new JacksonJsonRedisSerializer<>(cacheJsonMapper, ExchangeRateSeries.class);

    return RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(EXCHANGE_RATES_TTL)
        .disableCachingNullValues()
        .serializeKeysWith(
            RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
        .serializeValuesWith(
            RedisSerializationContext.SerializationPair.fromSerializer(
                exchangeRateSeriesSerializer))
        .prefixCacheNameWith("currency-service:");
  }

  /** Redis cache manager that decorates every cache as fail-open and then transaction-aware. */
  private static final class FailOpenRedisCacheManager extends RedisCacheManager {

    private FailOpenRedisCacheManager(
        RedisCacheWriter redisCacheWriter, RedisCacheConfiguration redisCacheConfiguration) {
      super(redisCacheWriter, redisCacheConfiguration);
    }

    @Override
    protected Cache decorateCache(Cache cache) {
      return new TransactionAwareCacheDecorator(new FailOpenCache(cache));
    }
  }
}
