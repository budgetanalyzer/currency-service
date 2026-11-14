package org.budgetanalyzer.currency.config;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

/**
 * Cache decorator that turns cache failures into misses and no-ops.
 *
 * <p>Every method delegates to the wrapped cache. When the wrapped cache throws, for example
 * because Redis is unreachable, slow, or rejects a command, the failure is logged at {@code WARN}
 * with the operation, the cache name, and the key (no key for {@code clear} and {@code
 * invalidate}), and is not rethrown:
 *
 * <ul>
 *   <li>A failed read returns a miss, so the caller loads the value from its source.
 *   <li>A failed write, eviction, or clear does nothing.
 * </ul>
 *
 * <p>Exceptions thrown by a value loader passed to {@link #get(Object, Callable)} or {@link
 * #retrieve(Object, Supplier)} still propagate; only the cache's own failures are absorbed.
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
    } catch (RuntimeException runtimeException) {
      logFailure("get", key, runtimeException);
      return null;
    }
  }

  @Override
  public <T> T get(Object key, Class<T> type) {
    try {
      return targetCache.get(key, type);
    } catch (RuntimeException runtimeException) {
      logFailure("get", key, runtimeException);
      return null;
    }
  }

  /**
   * Returns the cached value, or loads and caches it.
   *
   * <p>If the lookup fails, the value is loaded without the cache. If only storing the loaded value
   * fails, the loaded value is returned. A failure of the value loader propagates as a {@link
   * Cache.ValueRetrievalException}, as the wrapped cache reports it.
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
    } catch (RuntimeException runtimeException) {
      if (recordingValueLoader.loaderFailed) {
        throw runtimeException;
      }

      if (recordingValueLoader.loaderSucceeded) {
        logFailure("put", key, runtimeException);
        return recordingValueLoader.loadedValue;
      }

      logFailure("get", key, runtimeException);
      return loadValue(key, valueLoader);
    }
  }

  /**
   * Returns the cached value asynchronously; a failed lookup completes as a miss.
   *
   * @param key the key whose associated value is to be returned
   * @return a future of the cached value, completed with {@code null} on a miss or failure, or
   *     {@code null} if the wrapped cache immediately reports a miss
   */
  @Override
  public CompletableFuture<?> retrieve(Object key) {
    try {
      var cachedValueFuture = targetCache.retrieve(key);
      if (cachedValueFuture == null) {
        return null;
      }

      return cachedValueFuture.exceptionally(
          throwable -> {
            logFailure("retrieve", key, throwable);
            return null;
          });
    } catch (RuntimeException runtimeException) {
      logFailure("retrieve", key, runtimeException);
      return CompletableFuture.completedFuture(null);
    }
  }

  /**
   * Returns the cached value asynchronously, or loads and caches it.
   *
   * <p>If the lookup fails, the value is loaded without the cache. If only storing the loaded value
   * fails, the loaded value is returned. A failure of the value loader completes the returned
   * future exceptionally.
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
    } catch (RuntimeException runtimeException) {
      cachedValueFuture = CompletableFuture.failedFuture(runtimeException);
    }

    return cachedValueFuture.exceptionallyCompose(
        throwable -> {
          var valueFuture = loadedValueFuture.get();
          if (valueFuture == null) {
            logFailure("retrieve", key, throwable);
            return recordingValueLoader.get();
          }

          if (!valueFuture.isCompletedExceptionally()) {
            logFailure("put", key, throwable);
          }

          return valueFuture;
        });
  }

  @Override
  public void put(Object key, Object value) {
    try {
      targetCache.put(key, value);
    } catch (RuntimeException runtimeException) {
      logFailure("put", key, runtimeException);
    }
  }

  @Override
  public ValueWrapper putIfAbsent(Object key, Object value) {
    try {
      return targetCache.putIfAbsent(key, value);
    } catch (RuntimeException runtimeException) {
      logFailure("putIfAbsent", key, runtimeException);
      return null;
    }
  }

  @Override
  public void evict(Object key) {
    try {
      targetCache.evict(key);
    } catch (RuntimeException runtimeException) {
      logFailure("evict", key, runtimeException);
    }
  }

  @Override
  public boolean evictIfPresent(Object key) {
    try {
      return targetCache.evictIfPresent(key);
    } catch (RuntimeException runtimeException) {
      logFailure("evictIfPresent", key, runtimeException);
      return false;
    }
  }

  @Override
  public void clear() {
    try {
      targetCache.clear();
    } catch (RuntimeException runtimeException) {
      logFailure("clear", runtimeException);
    }
  }

  @Override
  public boolean invalidate() {
    try {
      return targetCache.invalidate();
    } catch (RuntimeException runtimeException) {
      logFailure("invalidate", runtimeException);
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

  private void logFailure(String operation, Object key, Throwable throwable) {
    log.warn(
        "Cache {} failed for cache '{}' and key '{}'; continuing without the cache",
        operation,
        getName(),
        key,
        throwable);
  }

  private void logFailure(String operation, Throwable throwable) {
    log.warn(
        "Cache {} failed for cache '{}'; continuing without the cache",
        operation,
        getName(),
        throwable);
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
