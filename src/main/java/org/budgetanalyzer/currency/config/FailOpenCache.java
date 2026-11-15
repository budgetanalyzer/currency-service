package org.budgetanalyzer.currency.config;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * Cache decorator that turns cache-infrastructure failures into misses and no-ops.
 *
 * <p>Every method delegates to the wrapped cache. A cache failure is a {@link DataAccessException},
 * which Spring Data Redis throws when Redis is unreachable, times out, or rejects a command, or a
 * {@link SerializationException}, thrown for a cached value that cannot be read. An asynchronous
 * failure counts when it is one of these types after unwrapping {@link CompletionException} and
 * {@link ExecutionException}. A cache failure is logged at {@code WARN} with the operation, the
 * cache name, the key (no key for {@code clear} and {@code invalidate}), and the exception class
 * and message, without a stack trace; the same message is logged with the stack trace at {@code
 * DEBUG}. The failure is not rethrown:
 *
 * <ul>
 *   <li>A failed read returns a miss, so the caller loads the value from its source.
 *   <li>A failed write, eviction, or clear does nothing.
 * </ul>
 *
 * <p>Any other exception from the wrapped cache propagates. Exceptions thrown by a value loader
 * passed to {@link #get(Object, Callable)} or {@link #retrieve(Object, Supplier)} also propagate,
 * including a {@link DataAccessException} from the database; only the cache's own failures are
 * absorbed.
 *
 * <p>The cache manager wraps this decorator in a {@code TransactionAwareCacheDecorator}, so the
 * writes and evictions that run after a transaction commits also pass through it.
 */
public class FailOpenCache implements Cache {

  private static final Logger log = LoggerFactory.getLogger(FailOpenCache.class);

  private final Cache targetCache;

  /**
   * Creates a fail-open decorator for the given cache.
   *
   * @param targetCache the cache that performs every operation
   */
  public FailOpenCache(Cache targetCache) {
    this.targetCache = targetCache;
  }

  @Override
  public String getName() {
    return targetCache.getName();
  }

  @Override
  public Object getNativeCache() {
    return targetCache.getNativeCache();
  }

  @Override
  public ValueWrapper get(Object key) {
    try {
      return targetCache.get(key);
    } catch (DataAccessException | SerializationException cacheException) {
      logFailure("get", key, cacheException);
      return null;
    }
  }

  @Override
  public <T> T get(Object key, Class<T> type) {
    try {
      return targetCache.get(key, type);
    } catch (DataAccessException | SerializationException cacheException) {
      logFailure("get", key, cacheException);
      return null;
    }
  }

  /**
   * Returns the cached value, or loads and caches it.
   *
   * <p>If the lookup fails in the cache, the value is loaded without the cache. If only storing the
   * loaded value fails, the loaded value is returned. A failure of the value loader propagates as a
   * {@link Cache.ValueRetrievalException}, as the wrapped cache reports it.
   *
   * @param key the key whose associated value is to be returned
   * @param valueLoader the loader that computes the value on a miss
   * @param <T> the value type
   * @return the cached or loaded value
   */
  @Override
  public <T> T get(Object key, Callable<T> valueLoader) {
    var recordingValueLoader = new RecordingValueLoader<>(valueLoader);
    try {
      return targetCache.get(key, recordingValueLoader);
    } catch (DataAccessException | SerializationException cacheException) {
      if (recordingValueLoader.loaderFailed) {
        throw cacheException;
      }

      if (recordingValueLoader.loaderSucceeded) {
        logFailure("put", key, cacheException);
        return recordingValueLoader.loadedValue;
      }

      logFailure("get", key, cacheException);
      return loadValue(key, valueLoader);
    }
  }

  /**
   * Returns the cached value asynchronously; a lookup that fails in the cache completes as a miss.
   *
   * @param key the key whose associated value is to be returned
   * @return a future of the cached value, completed with {@code null} on a miss or cache failure,
   *     or {@code null} if the wrapped cache immediately reports a miss
   */
  @Override
  public CompletableFuture<?> retrieve(Object key) {
    try {
      var cachedValueFuture = targetCache.retrieve(key);
      if (cachedValueFuture == null) {
        return null;
      }

      return completeAsMissOnCacheFailure(key, cachedValueFuture);
    } catch (DataAccessException | SerializationException cacheException) {
      logFailure("retrieve", key, cacheException);
      return CompletableFuture.completedFuture(null);
    }
  }

  /**
   * Returns the cached value asynchronously, or loads and caches it.
   *
   * <p>If the lookup fails in the cache, the value is loaded without the cache. If only storing the
   * loaded value fails, the loaded value is returned. A failure of the value loader completes the
   * returned future exceptionally.
   *
   * @param key the key whose associated value is to be returned
   * @param valueLoader the loader that computes the value on a miss
   * @param <T> the value type
   * @return a future of the cached or loaded value
   */
  @Override
  public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
    var loadedValueFuture = new AtomicReference<CompletableFuture<T>>();
    Supplier<CompletableFuture<T>> recordingValueLoader =
        () -> {
          CompletableFuture<T> valueFuture;
          try {
            valueFuture = valueLoader.get();
          } catch (RuntimeException runtimeException) {
            valueFuture = CompletableFuture.failedFuture(runtimeException);
          }
          loadedValueFuture.set(valueFuture);
          return valueFuture;
        };

    CompletableFuture<T> cachedValueFuture;
    try {
      cachedValueFuture = targetCache.retrieve(key, recordingValueLoader);
    } catch (DataAccessException | SerializationException cacheException) {
      cachedValueFuture = CompletableFuture.failedFuture(cacheException);
    }

    return cachedValueFuture.exceptionallyCompose(
        throwable -> {
          var valueFuture = loadedValueFuture.get();
          if (valueFuture != null && valueFuture.isCompletedExceptionally()) {
            return valueFuture;
          }

          var failure = unwrapAsyncFailure(throwable);
          if (!isCacheFailure(failure)) {
            return CompletableFuture.failedFuture(throwable);
          }

          if (valueFuture == null) {
            logFailure("retrieve", key, failure);
            return recordingValueLoader.get();
          }

          logFailure("put", key, failure);
          return valueFuture;
        });
  }

  @Override
  public void put(Object key, Object value) {
    try {
      targetCache.put(key, value);
    } catch (DataAccessException | SerializationException cacheException) {
      logFailure("put", key, cacheException);
    }
  }

  @Override
  public ValueWrapper putIfAbsent(Object key, Object value) {
    try {
      return targetCache.putIfAbsent(key, value);
    } catch (DataAccessException | SerializationException cacheException) {
      logFailure("putIfAbsent", key, cacheException);
      return null;
    }
  }

  @Override
  public void evict(Object key) {
    try {
      targetCache.evict(key);
    } catch (DataAccessException | SerializationException cacheException) {
      logFailure("evict", key, cacheException);
    }
  }

  @Override
  public boolean evictIfPresent(Object key) {
    try {
      return targetCache.evictIfPresent(key);
    } catch (DataAccessException | SerializationException cacheException) {
      logFailure("evictIfPresent", key, cacheException);
      return false;
    }
  }

  @Override
  public void clear() {
    try {
      targetCache.clear();
    } catch (DataAccessException | SerializationException cacheException) {
      logFailure("clear", cacheException);
    }
  }

  @Override
  public boolean invalidate() {
    try {
      return targetCache.invalidate();
    } catch (DataAccessException | SerializationException cacheException) {
      logFailure("invalidate", cacheException);
      return false;
    }
  }

  private <T> T loadValue(Object key, Callable<T> valueLoader) {
    try {
      return valueLoader.call();
    } catch (Exception exception) {
      throw new ValueRetrievalException(key, valueLoader, exception);
    }
  }

  private <T> CompletableFuture<T> completeAsMissOnCacheFailure(
      Object key, CompletableFuture<T> cachedValueFuture) {
    return cachedValueFuture.exceptionallyCompose(
        throwable -> {
          var failure = unwrapAsyncFailure(throwable);
          if (!isCacheFailure(failure)) {
            return CompletableFuture.failedFuture(throwable);
          }

          logFailure("retrieve", key, failure);
          return CompletableFuture.completedFuture(null);
        });
  }

  private static Throwable unwrapAsyncFailure(Throwable throwable) {
    var failure = throwable;
    while ((failure instanceof CompletionException || failure instanceof ExecutionException)
        && failure.getCause() != null) {
      failure = failure.getCause();
    }
    return failure;
  }

  private static boolean isCacheFailure(Throwable failure) {
    return failure instanceof DataAccessException || failure instanceof SerializationException;
  }

  private void logFailure(String operation, Object key, Throwable failure) {
    var message =
        "Cache {} failed for cache '{}' and key '{}'; continuing without the cache: {}: {}";
    var failureType = failure.getClass().getName();
    log.warn(message, operation, getName(), key, failureType, failure.getMessage());
    log.debug(message, operation, getName(), key, failureType, failure.getMessage(), failure);
  }

  private void logFailure(String operation, Throwable failure) {
    var message = "Cache {} failed for cache '{}'; continuing without the cache: {}: {}";
    var failureType = failure.getClass().getName();
    log.warn(message, operation, getName(), failureType, failure.getMessage());
    log.debug(message, operation, getName(), failureType, failure.getMessage(), failure);
  }

  /** Value loader that records whether the wrapped cache invoked it and how it finished. */
  private static final class RecordingValueLoader<T> implements Callable<T> {

    private final Callable<T> valueLoader;

    private boolean loaderSucceeded;

    private boolean loaderFailed;

    private T loadedValue;

    private RecordingValueLoader(Callable<T> valueLoader) {
      this.valueLoader = valueLoader;
    }

    @Override
    public T call() throws Exception {
      try {
        loadedValue = valueLoader.call();
        loaderSucceeded = true;
        return loadedValue;
      } catch (Exception exception) {
        loaderFailed = true;
        throw exception;
      }
    }
  }
}
