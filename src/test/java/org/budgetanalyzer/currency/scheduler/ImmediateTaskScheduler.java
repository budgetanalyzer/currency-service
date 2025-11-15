package org.budgetanalyzer.currency.scheduler;

import java.time.Instant;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * TaskScheduler implementation that executes scheduled tasks immediately instead of waiting for the
 * scheduled time.
 *
 * <p><b>Purpose:</b> Eliminate real time delays in integration tests that involve async retries.
 *
 * <p><b>Problem:</b> The production scheduler uses {@code TaskScheduler.schedule(task, instant)} to
 * schedule retries 1-2 minutes in the future. Integration tests using {@code @SpringBootTest} would
 * need to wait real time for these scheduled tasks to execute, causing tests to hang or timeout.
 *
 * <p><b>Solution:</b> This test scheduler executes scheduled tasks immediately instead of waiting
 * for the scheduled time. This allows tests to verify retry logic without real time delays.
 *
 * <p><b>Trade-offs:</b>
 *
 * <ul>
 *   <li>Pro: Tests run in seconds instead of minutes
 *   <li>Pro: No Awaitility timeouts or flaky timing issues
 *   <li>Con: Cannot verify exact retry delay timing (test separately at unit level)
 *   <li>Con: Tasks execute synchronously instead of async (acceptable for integration tests)
 * </ul>
 *
 * <p>For methods not used by ExchangeRateImportScheduler, throws UnsupportedOperationException to
 * prevent accidental usage.
 *
 * @see org.springframework.scheduling.TaskScheduler
 * @see ExchangeRateImportScheduler#scheduleRetry(int)
 */
class ImmediateTaskScheduler implements TaskScheduler {

  @Override
  public java.util.concurrent.ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
    // Not used by ExchangeRateImportScheduler - throw to detect misuse
    throw new UnsupportedOperationException(
        "schedule(Runnable, Trigger) not supported in test scheduler");
  }

  @Override
  public java.util.concurrent.ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
    // THIS IS THE KEY METHOD: Execute immediately instead of waiting for startTime
    // The production code calls: taskScheduler.schedule(task, Instant.now().plus(delay))
    // We execute the task immediately to avoid real time delays in tests
    task.run();
    // Return null since task already executed (callers don't check return value)
    return null;
  }

  @Override
  public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(
      Runnable task, Instant startTime, java.time.Duration period) {
    // Not used by ExchangeRateImportScheduler - throw to detect misuse
    throw new UnsupportedOperationException(
        "scheduleAtFixedRate(Runnable, Instant, Duration) not supported in test scheduler");
  }

  @Override
  public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(
      Runnable task, java.time.Duration period) {
    // Not used by ExchangeRateImportScheduler - throw to detect misuse
    throw new UnsupportedOperationException(
        "scheduleAtFixedRate(Runnable, Duration) not supported in test scheduler");
  }

  @Override
  public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable task, Instant startTime, java.time.Duration delay) {
    // Not used by ExchangeRateImportScheduler - throw to detect misuse
    throw new UnsupportedOperationException(
        "scheduleWithFixedDelay(Runnable, Instant, Duration) not supported in test scheduler");
  }

  @Override
  public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable task, java.time.Duration delay) {
    // Not used by ExchangeRateImportScheduler - throw to detect misuse
    throw new UnsupportedOperationException(
        "scheduleWithFixedDelay(Runnable, Duration) not supported in test scheduler");
  }
}
