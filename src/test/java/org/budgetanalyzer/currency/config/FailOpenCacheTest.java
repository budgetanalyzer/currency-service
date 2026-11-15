package org.budgetanalyzer.currency.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.serializer.SerializationException;

import ch.qos.logback.classic.Level;

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
            TestConstants.failOpenCacheWarning(
                "clear", TestConstants.FAIL_OPEN_TEST_CACHE_NAME, redisFailure()),
            TestConstants.failOpenCacheWarning(
                "invalidate", TestConstants.FAIL_OPEN_TEST_CACHE_NAME, redisFailure()));
  }

  @ParameterizedTest
  @MethodSource("cacheFailures")
  void shouldFailOpenOnCacheFailureFromSyncOperations(RuntimeException cacheFailure) {
    var failOpenCache = new FailOpenCache(new FailingCache(() -> cacheFailure, false));

    assertThat(failOpenCache.get(CACHE_KEY)).isNull();
    assertThat(failOpenCache.get(CACHE_KEY, String.class)).isNull();
    assertThat(failOpenCache.get(CACHE_KEY, () -> CACHE_VALUE)).isEqualTo(CACHE_VALUE);
    failOpenCache.put(CACHE_KEY, CACHE_VALUE);
    assertThat(failOpenCache.putIfAbsent(CACHE_KEY, CACHE_VALUE)).isNull();
    failOpenCache.evict(CACHE_KEY);
    assertThat(failOpenCache.evictIfPresent(CACHE_KEY)).isFalse();
    failOpenCache.clear();
    assertThat(failOpenCache.invalidate()).isFalse();

    assertThat(failOpenCacheLogCapture.warnings())
        .containsExactly(
            keyedWarning("get", cacheFailure),
            keyedWarning("get", cacheFailure),
            keyedWarning("get", cacheFailure),
            keyedWarning("put", cacheFailure),
            keyedWarning("putIfAbsent", cacheFailure),
            keyedWarning("evict", cacheFailure),
            keyedWarning("evictIfPresent", cacheFailure),
            TestConstants.failOpenCacheWarning(
                "clear", TestConstants.FAIL_OPEN_TEST_CACHE_NAME, cacheFailure),
            TestConstants.failOpenCacheWarning(
                "invalidate", TestConstants.FAIL_OPEN_TEST_CACHE_NAME, cacheFailure));
  }

  @ParameterizedTest
  @MethodSource("cacheFailures")
  void shouldFailOpenOnCacheFailureFromAsyncOperations(RuntimeException cacheFailure) {
    var lookupFailingCache = new FailOpenCache(new FailingCache(() -> cacheFailure, false));
    var storeFailingCache = new FailOpenCache(new FailingCache(() -> cacheFailure, true));

    assertThat(lookupFailingCache.retrieve(CACHE_KEY).join()).isNull();
    assertThat(
            lookupFailingCache
                .retrieve(CACHE_KEY, () -> CompletableFuture.completedFuture(CACHE_VALUE))
                .join())
        .isEqualTo(CACHE_VALUE);
    assertThat(
            storeFailingCache
                .retrieve(CACHE_KEY, () -> CompletableFuture.completedFuture(CACHE_VALUE))
                .join())
        .isEqualTo(CACHE_VALUE);

    assertThat(failOpenCacheLogCapture.warnings())
        .containsExactly(
            keyedWarning("retrieve", cacheFailure),
            keyedWarning("retrieve", cacheFailure),
            keyedWarning("put", cacheFailure));
  }

  @ParameterizedTest
  @MethodSource("asyncFailureWrappers")
  void shouldUnwrapAsyncCacheFailure(Function<RuntimeException, Throwable> asyncFailureWrapper) {
    var failOpenCache =
        new FailOpenCache(new FailingCache(FailOpenCacheTest::redisFailure, asyncFailureWrapper));

    assertThat(failOpenCache.retrieve(CACHE_KEY).join()).isNull();
    assertThat(
            failOpenCache
                .retrieve(CACHE_KEY, () -> CompletableFuture.completedFuture(CACHE_VALUE))
                .join())
        .isEqualTo(CACHE_VALUE);
    assertThat(failOpenCacheLogCapture.warnings())
        .containsExactly(keyedWarning("retrieve"), keyedWarning("retrieve"));
  }

  @Test
  void shouldPropagateUnexpectedFailureFromSyncOperations() {
    var failOpenCache =
        new FailOpenCache(new FailingCache(FailOpenCacheTest::unexpectedFailure, false));

    assertUnexpectedFailure(() -> failOpenCache.get(CACHE_KEY));
    assertUnexpectedFailure(() -> failOpenCache.get(CACHE_KEY, String.class));
    assertUnexpectedFailure(() -> failOpenCache.get(CACHE_KEY, () -> CACHE_VALUE));
    assertUnexpectedFailure(() -> failOpenCache.put(CACHE_KEY, CACHE_VALUE));
    assertUnexpectedFailure(() -> failOpenCache.putIfAbsent(CACHE_KEY, CACHE_VALUE));
    assertUnexpectedFailure(() -> failOpenCache.evict(CACHE_KEY));
    assertUnexpectedFailure(() -> failOpenCache.evictIfPresent(CACHE_KEY));
    assertUnexpectedFailure(failOpenCache::clear);
    assertUnexpectedFailure(failOpenCache::invalidate);
    assertThat(failOpenCacheLogCapture.warnings()).isEmpty();
  }

  @Test
  void shouldPropagateUnexpectedFailureWhenStoringLoadedValue() {
    var failOpenCache =
        new FailOpenCache(new FailingCache(FailOpenCacheTest::unexpectedFailure, true));

    assertUnexpectedFailure(() -> failOpenCache.get(CACHE_KEY, () -> CACHE_VALUE));
    assertUnexpectedAsyncFailure(
        failOpenCache.retrieve(CACHE_KEY, () -> CompletableFuture.completedFuture(CACHE_VALUE)));
    assertThat(failOpenCacheLogCapture.warnings()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("asyncFailureWrappers")
  void shouldPropagateUnexpectedFailureFromAsyncOperations(
      Function<RuntimeException, Throwable> asyncFailureWrapper) {
    var failOpenCache =
        new FailOpenCache(
            new FailingCache(FailOpenCacheTest::unexpectedFailure, asyncFailureWrapper));
    var loaderInvoked = new AtomicBoolean();

    assertUnexpectedAsyncFailure(failOpenCache.retrieve(CACHE_KEY));
    assertUnexpectedAsyncFailure(
        failOpenCache.retrieve(
            CACHE_KEY,
            () -> {
              loaderInvoked.set(true);
              return CompletableFuture.completedFuture(CACHE_VALUE);
            }));
    assertThat(loaderInvoked).isFalse();
    assertThat(failOpenCacheLogCapture.warnings()).isEmpty();
  }

  @Test
  void shouldPropagateLoaderDataAccessExceptionFromGetWithValueLoader() {
    var workingCache =
        new FailOpenCache(new ConcurrentMapCache(TestConstants.FAIL_OPEN_TEST_CACHE_NAME));
    var storeFailingCache = new FailOpenCache(new FailingCache(true));

    assertThatThrownBy(() -> workingCache.get(CACHE_KEY, FailOpenCacheTest::failingDatabaseLoad))
        .isInstanceOf(Cache.ValueRetrievalException.class)
        .cause()
        .isInstanceOf(DataAccessResourceFailureException.class)
        .hasMessage(TestConstants.SIMULATED_LOADER_FAILURE_MESSAGE);
    assertThatThrownBy(
            () -> storeFailingCache.get(CACHE_KEY, FailOpenCacheTest::failingDatabaseLoad))
        .isInstanceOf(Cache.ValueRetrievalException.class)
        .cause()
        .isInstanceOf(DataAccessResourceFailureException.class)
        .hasMessage(TestConstants.SIMULATED_LOADER_FAILURE_MESSAGE);
    assertThat(failOpenCacheLogCapture.warnings()).isEmpty();
  }

  @Test
  void shouldPropagateLoaderDataAccessExceptionRethrownByCache() {
    var failOpenCache = new FailOpenCache(new LoaderFailureRethrowingCache());

    assertThatThrownBy(() -> failOpenCache.get(CACHE_KEY, FailOpenCacheTest::failingDatabaseLoad))
        .isInstanceOf(DataAccessResourceFailureException.class)
        .hasMessage(TestConstants.SIMULATED_LOADER_FAILURE_MESSAGE);
    assertThat(failOpenCacheLogCapture.warnings()).isEmpty();
  }

  @Test
  void shouldPropagateLoaderDataAccessExceptionFromRetrieveWithValueLoader() {
    var workingCache =
        new FailOpenCache(new ConcurrentMapCache(TestConstants.FAIL_OPEN_TEST_CACHE_NAME));
    var storeFailingCache = new FailOpenCache(new FailingCache(true));

    assertLoaderDataAccessFailure(
        workingCache.retrieve(CACHE_KEY, FailOpenCacheTest::failingAsyncDatabaseLoad));
    assertLoaderDataAccessFailure(
        storeFailingCache.retrieve(CACHE_KEY, FailOpenCacheTest::failingAsyncDatabaseLoad));
    assertThat(failOpenCacheLogCapture.warnings()).isEmpty();
  }

  @Test
  void shouldLogStackTraceOnlyAtDebug() {
    var failOpenCache = new FailOpenCache(new FailingCache(false));

    failOpenCache.get(CACHE_KEY);
    failOpenCache.clear();

    assertThat(failOpenCacheLogCapture.throwableClassNames(Level.WARN)).isEmpty();
    assertThat(failOpenCacheLogCapture.messages(Level.DEBUG))
        .isEqualTo(failOpenCacheLogCapture.warnings())
        .hasSize(2);
    assertThat(failOpenCacheLogCapture.throwableClassNames(Level.DEBUG))
        .containsExactly(
            RedisConnectionFailureException.class.getName(),
            RedisConnectionFailureException.class.getName());
  }

  private static Stream<Arguments> cacheFailures() {
    return Stream.of(
        Arguments.of(
            named(
                "connection failure",
                new RedisConnectionFailureException(
                    TestConstants.SIMULATED_REDIS_FAILURE_MESSAGE))),
        Arguments.of(
            named(
                "command timeout",
                new QueryTimeoutException(TestConstants.SIMULATED_REDIS_FAILURE_MESSAGE))),
        Arguments.of(
            named(
                "rejected command",
                new RedisSystemException(TestConstants.SIMULATED_REDIS_FAILURE_MESSAGE, null))),
        Arguments.of(
            named(
                "undeserializable value",
                new SerializationException(TestConstants.SIMULATED_REDIS_FAILURE_MESSAGE))));
  }

  private static Stream<Arguments> asyncFailureWrappers() {
    return Stream.of(
        Arguments.of(
            named("unwrapped", (Function<RuntimeException, Throwable>) failure -> failure)),
        Arguments.of(
            named(
                "CompletionException",
                (Function<RuntimeException, Throwable>) CompletionException::new)),
        Arguments.of(
            named(
                "ExecutionException",
                (Function<RuntimeException, Throwable>) ExecutionException::new)));
  }

  private static void assertUnexpectedFailure(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(TestConstants.SIMULATED_UNEXPECTED_FAILURE_MESSAGE);
  }

  private static void assertUnexpectedAsyncFailure(CompletableFuture<?> valueFuture) {
    assertThatThrownBy(valueFuture::join)
        .isInstanceOf(CompletionException.class)
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(TestConstants.SIMULATED_UNEXPECTED_FAILURE_MESSAGE);
  }

  private static void assertLoaderDataAccessFailure(CompletableFuture<?> valueFuture) {
    assertThatThrownBy(valueFuture::join)
        .isInstanceOf(CompletionException.class)
        .rootCause()
        .isInstanceOf(DataAccessResourceFailureException.class)
        .hasMessage(TestConstants.SIMULATED_LOADER_FAILURE_MESSAGE);
  }

  private static String keyedWarning(String operation) {
    return keyedWarning(operation, redisFailure());
  }

  private static String keyedWarning(String operation, Throwable failure) {
    return TestConstants.failOpenCacheWarning(
        operation, TestConstants.FAIL_OPEN_TEST_CACHE_NAME, CACHE_KEY, failure);
  }

  private static String failingLoad() {
    throw new IllegalStateException(TestConstants.SIMULATED_LOADER_FAILURE_MESSAGE);
  }

  private static String failingDatabaseLoad() {
    throw new DataAccessResourceFailureException(TestConstants.SIMULATED_LOADER_FAILURE_MESSAGE);
  }

  private static CompletableFuture<String> failingAsyncDatabaseLoad() {
    return CompletableFuture.failedFuture(
        new DataAccessResourceFailureException(TestConstants.SIMULATED_LOADER_FAILURE_MESSAGE));
  }

  private static RedisConnectionFailureException redisFailure() {
    return new RedisConnectionFailureException(TestConstants.SIMULATED_REDIS_FAILURE_MESSAGE);
  }

  private static IllegalStateException unexpectedFailure() {
    return new IllegalStateException(TestConstants.SIMULATED_UNEXPECTED_FAILURE_MESSAGE);
  }

  /**
   * Cache whose every operation fails, by default like a Redis cache that cannot reach the server.
   *
   * <p>When {@code loadsBeforeFailing} is set, the value-loader methods first find a miss and
   * invoke the loader, as a Redis cache does when only storing the loaded value fails. Otherwise
   * every operation fails before any value is loaded. Asynchronous operations complete their
   * futures with the failure passed through {@code asyncFailureWrapper}.
   */
  private static final class FailingCache implements Cache {

    private final Supplier<RuntimeException> failureSupplier;

    private final Function<RuntimeException, Throwable> asyncFailureWrapper;

    private final boolean loadsBeforeFailing;

    private FailingCache(boolean loadsBeforeFailing) {
      this(FailOpenCacheTest::redisFailure, loadsBeforeFailing);
    }

    private FailingCache(Supplier<RuntimeException> failureSupplier, boolean loadsBeforeFailing) {
      this(failureSupplier, failure -> failure, loadsBeforeFailing);
    }

    private FailingCache(
        Supplier<RuntimeException> failureSupplier,
        Function<RuntimeException, Throwable> asyncFailureWrapper) {
      this(failureSupplier, asyncFailureWrapper, false);
    }

    private FailingCache(
        Supplier<RuntimeException> failureSupplier,
        Function<RuntimeException, Throwable> asyncFailureWrapper,
        boolean loadsBeforeFailing) {
      this.failureSupplier = failureSupplier;
      this.asyncFailureWrapper = asyncFailureWrapper;
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
      throw failureSupplier.get();
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
      throw failureSupplier.get();
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
      if (!loadsBeforeFailing) {
        throw failureSupplier.get();
      }

      try {
        valueLoader.call();
      } catch (Exception exception) {
        throw new ValueRetrievalException(key, valueLoader, exception);
      }
      throw failureSupplier.get();
    }

    @Override
    public CompletableFuture<?> retrieve(Object key) {
      return failedFuture();
    }

    @Override
    public <T> CompletableFuture<T> retrieve(
        Object key, Supplier<CompletableFuture<T>> valueLoader) {
      if (!loadsBeforeFailing) {
        return failedFuture();
      }

      return valueLoader.get().thenCompose(value -> failedFuture());
    }

    @Override
    public void put(Object key, Object value) {
      throw failureSupplier.get();
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
      throw failureSupplier.get();
    }

    @Override
    public void evict(Object key) {
      throw failureSupplier.get();
    }

    @Override
    public boolean evictIfPresent(Object key) {
      throw failureSupplier.get();
    }

    @Override
    public void clear() {
      throw failureSupplier.get();
    }

    @Override
    public boolean invalidate() {
      throw failureSupplier.get();
    }

    private <T> CompletableFuture<T> failedFuture() {
      return CompletableFuture.failedFuture(asyncFailureWrapper.apply(failureSupplier.get()));
    }
  }

  /** Working cache that rethrows a value loader's unchecked exception unchanged. */
  private static final class LoaderFailureRethrowingCache extends ConcurrentMapCache {

    private LoaderFailureRethrowingCache() {
      super(TestConstants.FAIL_OPEN_TEST_CACHE_NAME);
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
      try {
        return valueLoader.call();
      } catch (RuntimeException runtimeException) {
        throw runtimeException;
      } catch (Exception exception) {
        throw new ValueRetrievalException(key, valueLoader, exception);
      }
    }
  }
}
