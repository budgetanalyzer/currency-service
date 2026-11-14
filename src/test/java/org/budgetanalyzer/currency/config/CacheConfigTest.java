package org.budgetanalyzer.currency.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Tests which cache manager {@link CacheConfig} and Spring Boot's cache auto-configuration create
 * for each {@code spring.cache.type}.
 *
 * <p>The Lettuce connection factory is never started, so creating the manager and its caches does
 * not connect to Redis.
 */
class CacheConfigTest {

  private final ApplicationContextRunner applicationContextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(CacheAutoConfiguration.class, JacksonAutoConfiguration.class))
          .withUserConfiguration(CacheConfig.class)
          .withBean(RedisConnectionFactory.class, CacheConfigTest::unstartedConnectionFactory);

  @Test
  void shouldCreateFailOpenTransactionAwareManagerWhenCacheTypeIsUnset() {
    applicationContextRunner.run(CacheConfigTest::assertFailOpenTransactionAwareManager);
  }

  @Test
  void shouldCreateFailOpenTransactionAwareManagerWhenCacheTypeIsRedis() {
    applicationContextRunner
        .withPropertyValues("spring.cache.type=redis")
        .run(CacheConfigTest::assertFailOpenTransactionAwareManager);
  }

  @Test
  void shouldCreateNoOpManagerWhenCacheTypeIsNone() {
    applicationContextRunner
        .withPropertyValues("spring.cache.type=none")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(RedisCacheManager.class);
              assertThat(context).getBean(CacheManager.class).isInstanceOf(NoOpCacheManager.class);
            });
  }

  private static void assertFailOpenTransactionAwareManager(AssertableApplicationContext context) {
    assertThat(context).hasNotFailed();
    assertThat(context).hasSingleBean(CacheManager.class);
    var cacheManager = context.getBean(CacheManager.class);
    assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);

    var cache = cacheManager.getCache(CacheConfig.EXCHANGE_RATE_SERIES_CACHE);

    assertThat(cache).isInstanceOf(TransactionAwareCacheDecorator.class);
    var targetCache = ((TransactionAwareCacheDecorator) cache).getTargetCache();
    assertThat(targetCache).isInstanceOf(FailOpenCache.class);
    assertThat(targetCache.getName()).isEqualTo(CacheConfig.EXCHANGE_RATE_SERIES_CACHE);
    assertThat(targetCache.getNativeCache()).isInstanceOf(RedisCacheWriter.class);
  }

  private static LettuceConnectionFactory unstartedConnectionFactory() {
    var lettuceConnectionFactory = new LettuceConnectionFactory();
    lettuceConnectionFactory.setAutoStartup(false);
    return lettuceConnectionFactory;
  }
}
