package org.budgetanalyzer.currency.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.redis.RedisConnectionFailureException;

import org.budgetanalyzer.currency.fixture.FailOpenCacheLogCapture;
import org.budgetanalyzer.currency.fixture.TestConstants;

/** Unit tests for {@link FailOpenCache}. */
class FailOpenCacheTest {

  private static final String CACHE_KEY = TestConstants.VALID_CURRENCY_EUR;

  private static final String CACHE_VALUE = TestConstants.FAIL_OPEN_TEST_VALUE;

  private FailOpenCacheLogCapture failOpenCacheLogCapture;

  @BeforeEach
  void startLogCapture() {
    failOpenCacheLogCapture = FailOpenCacheLogCapture.start();
  }

  @AfterEach
  void stopLogCapture() {
    failOpenCacheLogCapture.close();
  }

  @Test
  void shouldDelegateToWorkingCache() {
    var concurrentMapCache = new ConcurrentMapCache(TestConstants.FAIL_OPEN_TEST_CACHE_NAME);
    var failOpenCache = new FailOpenCache(concurrentMapCache);

    failOpenCache.put(CACHE_KEY, CACHE_VALUE);

    assertThat(failOpenCache.getName()).isEqualTo(TestConstants.FAIL_OPEN_TEST_CACHE_NAME);
    assertThat(failOpenCache.getNativeCache()).isSameAs(concurrentMapCache.getNativeCache());
    assertThat(failOpenCache.get(CACHE_KEY, String.class)).isEqualTo(CACHE_VALUE);
    assertThat(concurrentMapCache.get(CACHE_KEY, String.class)).isEqualTo(CACHE_VALUE);
    assertThat(failOpenCache.evictIfPresent(CACHE_KEY)).isTrue();
    assertThat(concurrentMapCache.get(CACHE_KEY)).isNull();
    assertThat(failOpenCacheLogCapture.warnings()).isEmpty();
  }

  @Test
  void shouldReturnMissWhenGetFails() {
    var failOpenCache = new FailOpenCache(new FailingCache(false));

    assertThat(failOpenCache.get(CACHE_KEY)).isNull();
    assertThat(failOpenCache.get(CACHE_KEY, String.class)).isNull();
    assertThat(failOpenCacheLogCapture.warnings())
        .containsExactly(keyedWarning("get"), keyedWarning("get"));
  }

  @Test
  void shouldLoadValueWhenLookupWithValueLoaderFails() {
    var failOpenCache = new FailOpenCache(new FailingCache(false));

    assertThat(failOpenCache.get(CACHE_KEY, () -> CACHE_VALUE)).isEqualTo(CACHE_VALUE);
    assertThat(failOpenCacheLogCapture.warnings()).containsExactly(keyedWarning("get"));
  }

  @Test
  void shouldReturnLoadedValueWhenStoringLoadedValueFails() {
    var failOpenCache = new FailOpenCache(new FailingCache(true));

    assertThat(failOpenCache.get(CACHE_KEY, () -> CACHE_VALUE)).isEqualTo(CACHE_VALUE);
    assertThat(failOpenCacheLogCapture.warnings()).containsExactly(keyedWarning("put"));
  }

  @Test
  void shouldPropagateValueLoaderFailureWhenLookupFails() {
    var failOpenCache = new FailOpenCache(new FailingCache(false));

    assertThatThrownBy(() -> failOpenCache.get(CACHE_KEY, FailOpenCacheTest::failingLoad))
        .isInstanceOf(Cache.ValueRetrievalException.class)
        .rootCause()
        .hasMessage(TestConstants.SIMULATED_LOADER_FAILURE_MESSAGE);
    assertThat(failOpenCacheLogCapture.warnings()).containsExactly(keyedWarning("get"));
  }

  @Test
  void shouldPropagateValueLoaderFailureFromCacheThatInvokesLoader() {
    var failOpenCache = new FailOpenCache(new FailingCache(true));

    assertThatThrownBy(() -> failOpenCache.get(CACHE_KEY, FailOpenCacheTest::failingLoad))
        .isInstanceOf(Cache.ValueRetrievalException.class)
        .rootCause()
        .hasMessage(TestConstants.SIMULATED_LOADER_FAILURE_MESSAGE);
    assertThat(failOpenCacheLogCapture.warnings()).isEmpty();
  }

  @Test
  void shouldPropagateValueLoaderFailureFromWorkingCache() {
    var failOpenCache =
        new FailOpenCache(new ConcurrentMapCache(TestConstants.FAIL_OPEN_TEST_CACHE_NAME));

    assertThatThrownBy(() -> failOpenCache.get(CACHE_KEY, FailOpenCacheTest::failingLoad))
        .isInstanceOf(Cache.ValueRetrievalException.class)
        .rootCause()
        .hasMessage(TestConstants.SIMULATED_LOADER_FAILURE_MESSAGE);
    assertThat(failOpenCacheLogCapture.warnings()).isEmpty();
  }

  @Test
  void shouldCompleteRetrieveAsMissWhenRetrieveFails() {
    var failOpenCache = new FailOpenCache(new FailingCache(false));

    assertThat(failOpenCache.retrieve(CACHE_KEY).join()).isNull();
    assertThat(failOpenCacheLogCapture.warnings()).containsExactly(keyedWarning("retrieve"));
  }

  @Test
  void shouldLoadValueWhenRetrieveWithValueLoaderFails() {
    var failOpenCache = new FailOpenCache(new FailingCache(false));

    var valueFuture =
        failOpenCache.retrieve(CACHE_KEY, () -> CompletableFuture.completedFuture(CACHE_VALUE));

    assertThat(valueFuture.join()).isEqualTo(CACHE_VALUE);
    assertThat(failOpenCacheLogCapture.warnings()).containsExactly(keyedWarning("retrieve"));
  }

  @Test
  void shouldReturnLoadedValueWhenStoringRetrievedValueFails() {
    var failOpenCache = new FailOpenCache(new FailingCache(true));

    var valueFuture =
        failOpenCache.retrieve(CACHE_KEY, () -> CompletableFuture.completedFuture(CACHE_VALUE));

    assertThat(valueFuture.join()).isEqualTo(CACHE_VALUE);
    assertThat(failOpenCacheLogCapture.warnings()).containsExactly(keyedWarning("put"));
  }

  @Test
  void shouldPropagateAsyncValueLoaderFailure() {
    var failOpenCache = new FailOpenCache(new FailingCache(true));

    var valueFuture =
        failOpenCache.retrieve(
            CACHE_KEY,
            () ->
                CompletableFuture.failedFuture(
                    new IllegalStateException(TestConstants.SIMULATED_LOADER_FAILURE_MESSAGE)));

    assertThatThrownBy(valueFuture::join)
        .isInstanceOf(CompletionException.class)
        .rootCause()
        .hasMessage(TestConstants.SIMULATED_LOADER_FAILURE_MESSAGE);
    assertThat(failOpenCacheLogCapture.warnings()).isEmpty();
  }

  @Test
  void shouldIgnoreFailedWrites() {
    var failOpenCache = new FailOpenCache(new FailingCache(false));

    failOpenCache.put(CACHE_KEY, CACHE_VALUE);

    assertThat(failOpenCache.putIfAbsent(CACHE_KEY, CACHE_VALUE)).isNull();
    assertThat(failOpenCacheLogCapture.warnings())
        .containsExactly(keyedWarning("put"), keyedWarning("putIfAbsent"));
  }

  @Test
  void shouldIgnoreFailedEvictions() {
    var failOpenCache = new FailOpenCache(new FailingCache(false));

    failOpenCache.evict(CACHE_KEY);

    assertThat(failOpenCache.evictIfPresent(CACHE_KEY)).isFalse();
    assertThat(failOpenCacheLogCapture.warnings())
        .containsExactly(keyedWarning("evict"), keyedWarning("evictIfPresent"));
  }

  @Test
  void shouldIgnoreFailedClearAndInvalidateWithoutLoggingKey() {
    var failOpenCache = new FailOpenCache(new FailingCache(false));

    failOpenCache.clear();

    assertThat(failOpenCache.invalidate()).isFalse();
    assertThat(failOpenCacheLogCapture.warnings())
        .containsExactly(
            TestConstants.failOpenCacheWarning("clear", TestConstants.FAIL_OPEN_TEST_CACHE_NAME),
            TestConstants.failOpenCacheWarning(
                "invalidate", TestConstants.FAIL_OPEN_TEST_CACHE_NAME));
  }

  private static String keyedWarning(String operation) {
    return TestConstants.failOpenCacheWarning(
        operation, TestConstants.FAIL_OPEN_TEST_CACHE_NAME, CACHE_KEY);
  }

  private static String failingLoad() {
    throw new IllegalStateException(TestConstants.SIMULATED_LOADER_FAILURE_MESSAGE);
  }

  private static RedisConnectionFailureException redisFailure() {
    return new RedisConnectionFailureException(TestConstants.SIMULATED_REDIS_FAILURE_MESSAGE);
  }

  /**
   * Cache whose storage is unavailable, failing like a Redis cache that cannot reach the server.
   *
   * <p>When {@code loadsBeforeFailing} is set, the value-loader methods first find a miss and
   * invoke the loader, as a Redis cache does when only storing the loaded value fails. Otherwise
   * every operation fails before any value is loaded.
   */
  private static final class FailingCache implements Cache {

    private final boolean loadsBeforeFailing;

    private FailingCache(boolean loadsBeforeFailing) {
      this.loadsBeforeFailing = loadsBeforeFailing;
    }

    @Override
    public String getName() {
      return TestConstants.FAIL_OPEN_TEST_CACHE_NAME;
    }

    @Override
    public Object getNativeCache() {
      return this;
    }

    @Override
    public ValueWrapper get(Object key) {
      throw redisFailure();
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
      throw redisFailure();
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
      if (!loadsBeforeFailing) {
        throw redisFailure();
      }

      try {
        valueLoader.call();
      } catch (Exception exception) {
        throw new ValueRetrievalException(key, valueLoader, exception);
      }
      throw redisFailure();
    }

    @Override
    public CompletableFuture<?> retrieve(Object key) {
      return CompletableFuture.failedFuture(redisFailure());
    }

    @Override
    public <T> CompletableFuture<T> retrieve(
        Object key, Supplier<CompletableFuture<T>> valueLoader) {
      if (!loadsBeforeFailing) {
        return CompletableFuture.failedFuture(redisFailure());
      }

      return valueLoader.get().thenCompose(value -> CompletableFuture.failedFuture(redisFailure()));
    }

    @Override
    public void put(Object key, Object value) {
      throw redisFailure();
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
      throw redisFailure();
    }

    @Override
    public void evict(Object key) {
      throw redisFailure();
    }

    @Override
    public boolean evictIfPresent(Object key) {
      throw redisFailure();
    }

    @Override
    public void clear() {
      throw redisFailure();
    }

    @Override
    public boolean invalidate() {
      throw redisFailure();
    }
  }
}
