package org.budgetanalyzer.currency.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.budgetanalyzer.currency.config.CurrencyServiceProperties;
import org.budgetanalyzer.currency.service.ExchangeRateImportService;
import org.budgetanalyzer.currency.service.dto.ExchangeRateImportResult;

/**
 * Unit tests for {@link ExchangeRateImportScheduler}.
 *
 * <p><b>Purpose:</b> Fast unit tests with mocked dependencies to verify scheduler logic.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Successful import execution and metrics recording
 *   <li>Failed import with retry scheduling
 *   <li>Retry attempt success
 *   <li>Retry exhaustion after max attempts
 *   <li>Metrics recording for all scenarios
 *   <li>Exception handling
 * </ul>
 *
 * <p><b>Note:</b> ShedLock coordination is tested in integration tests. These unit tests focus on
 * the core business logic and retry mechanism.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExchangeRateImportScheduler Unit Tests")
class ExchangeRateImportSchedulerTest {

  // ===========================================================================================
  // Test Dependencies
  // ===========================================================================================

  @Mock private TaskScheduler taskScheduler;

  @Mock private ExchangeRateImportService importService;

  private MeterRegistry meterRegistry;

  private CurrencyServiceProperties properties;

  private ExchangeRateImportScheduler scheduler;

  // ===========================================================================================
  // Setup
  // ===========================================================================================

  @BeforeEach
  void setUp() {
    // Use SimpleMeterRegistry for easier verification
    meterRegistry = new SimpleMeterRegistry();

    // Create real properties with test configuration
    properties = new CurrencyServiceProperties();
    var exchangeRateImport = new CurrencyServiceProperties.ExchangeRateImport();
    var retry = new CurrencyServiceProperties.ExchangeRateImport.Retry();
    retry.setMaxAttempts(3);
    retry.setDelayMinutes(1);
    exchangeRateImport.setRetry(retry);
    properties.setExchangeRateImport(exchangeRateImport);

    // Create scheduler with mocked dependencies
    scheduler =
        new ExchangeRateImportScheduler(taskScheduler, meterRegistry, properties, importService);
  }

  // ===========================================================================================
  // Test Cases - Successful Import
  // ===========================================================================================

  @Test
  @DisplayName("importDailyRates - when successful - records metrics and does not retry")
  void importDailyRates_WhenSuccessful_RecordsMetricsAndDoesNotRetry() {
    // Arrange
    var expectedResults =
        List.of(
            new ExchangeRateImportResult("EUR", "DEXUSEU", 100, 0, 0, null, null),
            new ExchangeRateImportResult("GBP", "DEXUSUK", 50, 0, 0, null, null));
    when(importService.importLatestExchangeRates()).thenReturn(expectedResults);

    // Act
    scheduler.importDailyRates();

    // Assert
    verify(importService, times(1)).importLatestExchangeRates();
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));

    // Verify success metrics recorded
    Timer timer =
        meterRegistry
            .find("exchange.rate.import.duration")
            .tag("status", "success")
            .tag("attempt", "1")
            .timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);

    Counter counter =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "success")
            .tag("attempt", "1")
            .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1);

    // Verify no retry scheduled
    Counter retryCounter = meterRegistry.find("exchange.rate.import.retry.scheduled").counter();
    assertThat(retryCounter).isNull();

    // Verify no exhaustion recorded
    Counter exhaustedCounter = meterRegistry.find("exchange.rate.import.exhausted").counter();
    assertThat(exhaustedCounter).isNull();
  }

  // ===========================================================================================
  // Test Cases - Failed Import with Retry
  // ===========================================================================================

  @Test
  @DisplayName("importDailyRates - when fails - schedules retry")
  void importDailyRates_WhenFails_SchedulesRetry() {
    // Arrange
    var exception = new RuntimeException("FRED API unavailable");
    when(importService.importLatestExchangeRates()).thenThrow(exception);

    // Act
    scheduler.importDailyRates();

    // Assert
    verify(importService, times(1)).importLatestExchangeRates();

    // Verify retry scheduled
    ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
    ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(taskScheduler, times(1)).schedule(runnableCaptor.capture(), instantCaptor.capture());

    // Verify retry scheduled approximately 1 minute in future
    Instant scheduledTime = instantCaptor.getValue();
    long delaySeconds = scheduledTime.getEpochSecond() - Instant.now().getEpochSecond();
    assertThat(delaySeconds).isBetween(55L, 65L); // Allow 5 second tolerance

    // Verify failure metrics recorded
    Timer timer =
        meterRegistry
            .find("exchange.rate.import.duration")
            .tag("status", "failure")
            .tag("attempt", "1")
            .tag("error", "RuntimeException")
            .timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);

    Counter counter =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "failure")
            .tag("attempt", "1")
            .tag("error", "RuntimeException")
            .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1);

    // Verify retry counter incremented
    Counter retryCounter =
        meterRegistry.find("exchange.rate.import.retry.scheduled").tag("attempt", "2").counter();
    assertThat(retryCounter).isNotNull();
    assertThat(retryCounter.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("importDailyRates - when fails with different exception - records correct error tag")
  void importDailyRates_WhenFailsWithDifferentException_RecordsCorrectErrorTag() {
    // Arrange
    var exception = new IllegalStateException("Invalid state");
    when(importService.importLatestExchangeRates()).thenThrow(exception);

    // Act
    scheduler.importDailyRates();

    // Assert
    Timer timer =
        meterRegistry
            .find("exchange.rate.import.duration")
            .tag("status", "failure")
            .tag("attempt", "1")
            .tag("error", "IllegalStateException")
            .timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);

    Counter counter =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "failure")
            .tag("attempt", "1")
            .tag("error", "IllegalStateException")
            .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1);
  }

  // ===========================================================================================
  // Test Cases - Retry Success
  // ===========================================================================================

  @Test
  @DisplayName("retry - when successful on second attempt - records metrics with attempt 2")
  void retry_WhenSuccessfulOnSecondAttempt_RecordsMetricsWithAttempt2() {
    // Arrange - first attempt fails, second succeeds
    var exception = new RuntimeException("Temporary failure");
    var expectedResults =
        List.of(new ExchangeRateImportResult("EUR", "DEXUSEU", 100, 0, 0, null, null));
    when(importService.importLatestExchangeRates())
        .thenThrow(exception)
        .thenReturn(expectedResults);

    // Act - execute initial attempt (will fail and schedule retry)
    scheduler.importDailyRates();

    // Get the scheduled retry runnable
    ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));
    Runnable retryRunnable = runnableCaptor.getValue();

    // Act - execute the retry (will succeed)
    retryRunnable.run();

    // Assert
    verify(importService, times(2)).importLatestExchangeRates(); // Called twice: initial + retry

    // Verify success metrics with attempt=2
    Timer timer =
        meterRegistry
            .find("exchange.rate.import.duration")
            .tag("status", "success")
            .tag("attempt", "2")
            .timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);

    Counter counter =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "success")
            .tag("attempt", "2")
            .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1);

    // Verify no further retries scheduled (only 1 retry total)
    verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
  }

  // ===========================================================================================
  // Test Cases - Retry Exhaustion
  // ===========================================================================================

  @Test
  @DisplayName("importDailyRates - when all attempts fail - records exhaustion and stops retrying")
  void importDailyRates_WhenAllAttemptsFail_RecordsExhaustionAndStopsRetrying() {
    // Arrange
    var exception = new RuntimeException("Persistent failure");
    when(importService.importLatestExchangeRates()).thenThrow(exception);

    // Act - execute initial attempt
    scheduler.importDailyRates();

    // Verify first retry scheduled (attempt 2)
    ArgumentCaptor<Runnable> captor1 = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler, times(1)).schedule(captor1.capture(), any(Instant.class));

    // Act - execute second attempt (retry 1)
    captor1.getValue().run();

    // Verify second retry scheduled (attempt 3)
    ArgumentCaptor<Runnable> captor2 = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler, times(2)).schedule(captor2.capture(), any(Instant.class));

    // Act - execute third attempt (retry 2) - final attempt
    captor2.getValue().run();

    // Assert
    verify(importService, times(3)).importLatestExchangeRates(); // 3 total attempts

    // Verify NO more retries scheduled (exactly 2 retries total)
    verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));

    // Verify exhaustion metric recorded
    Counter exhaustedCounter = meterRegistry.find("exchange.rate.import.exhausted").counter();
    assertThat(exhaustedCounter).isNotNull();
    assertThat(exhaustedCounter.count()).isEqualTo(1);

    // Verify all three failure attempts recorded
    Counter counter1 =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "failure")
            .tag("attempt", "1")
            .counter();
    assertThat(counter1).isNotNull();
    assertThat(counter1.count()).isEqualTo(1);

    Counter counter2 =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "failure")
            .tag("attempt", "2")
            .counter();
    assertThat(counter2).isNotNull();
    assertThat(counter2.count()).isEqualTo(1);

    Counter counter3 =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "failure")
            .tag("attempt", "3")
            .counter();
    assertThat(counter3).isNotNull();
    assertThat(counter3.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("importDailyRates - with custom max attempts - respects configuration")
  void importDailyRates_WithCustomMaxAttempts_RespectsConfiguration() {
    // Arrange - set max attempts to 2
    var retry = new CurrencyServiceProperties.ExchangeRateImport.Retry();
    retry.setMaxAttempts(2);
    retry.setDelayMinutes(1);
    properties.getExchangeRateImport().setRetry(retry);

    var exception = new RuntimeException("Persistent failure");
    when(importService.importLatestExchangeRates()).thenThrow(exception);

    // Act - execute initial attempt
    scheduler.importDailyRates();

    // Execute first retry (attempt 2 - final attempt with maxAttempts=2)
    ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler, times(1)).schedule(captor.capture(), any(Instant.class));
    captor.getValue().run();

    // Assert
    verify(importService, times(2)).importLatestExchangeRates(); // 2 total attempts

    // Verify NO more retries scheduled (exactly 1 retry total)
    verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));

    // Verify exhaustion metric recorded
    Counter exhaustedCounter = meterRegistry.find("exchange.rate.import.exhausted").counter();
    assertThat(exhaustedCounter).isNotNull();
    assertThat(exhaustedCounter.count()).isEqualTo(1);
  }

  // ===========================================================================================
  // Test Cases - Metrics Recording
  // ===========================================================================================

  @Test
  @DisplayName("importDailyRates - records all required metrics")
  void importDailyRates_RecordsAllRequiredMetrics() {
    // Arrange
    var expectedResults =
        List.of(new ExchangeRateImportResult("EUR", "DEXUSEU", 100, 0, 0, null, null));
    when(importService.importLatestExchangeRates()).thenReturn(expectedResults);

    // Act
    scheduler.importDailyRates();

    // Assert - verify timer exists and recorded
    Timer timer =
        meterRegistry
            .find("exchange.rate.import.duration")
            .tag("status", "success")
            .tag("attempt", "1")
            .timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);
    assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThan(0);

    // Assert - verify execution counter exists and recorded
    Counter counter =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "success")
            .tag("attempt", "1")
            .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("importDailyRates - when fails - records metrics with error tag")
  void importDailyRates_WhenFails_RecordsMetricsWithErrorTag() {
    // Arrange
    var exception = new RuntimeException("Test failure");
    when(importService.importLatestExchangeRates()).thenThrow(exception);

    // Act
    scheduler.importDailyRates();

    // Assert - verify timer has error tag
    Timer timer =
        meterRegistry
            .find("exchange.rate.import.duration")
            .tag("status", "failure")
            .tag("attempt", "1")
            .tag("error", "RuntimeException")
            .timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);

    // Assert - verify counter has error tag
    Counter counter =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "failure")
            .tag("attempt", "1")
            .tag("error", "RuntimeException")
            .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1);

    // Assert - verify retry scheduled counter
    Counter retryCounter =
        meterRegistry.find("exchange.rate.import.retry.scheduled").tag("attempt", "2").counter();
    assertThat(retryCounter).isNotNull();
    assertThat(retryCounter.count()).isEqualTo(1);
  }

  // ===========================================================================================
  // Test Cases - Exception Handling
  // ===========================================================================================

  @Test
  @DisplayName("importDailyRates - when RuntimeException - handles gracefully")
  void importDailyRates_WhenRuntimeException_HandlesGracefully() {
    // Arrange
    var exception = new RuntimeException("Unexpected error");
    doThrow(exception).when(importService).importLatestExchangeRates();

    // Act - should not throw exception
    scheduler.importDailyRates();

    // Assert - verify exception was caught and metrics recorded
    Timer timer =
        meterRegistry
            .find("exchange.rate.import.duration")
            .tag("status", "failure")
            .tag("error", "RuntimeException")
            .timer();
    assertThat(timer).isNotNull();

    // Verify retry was scheduled (scheduler didn't crash)
    verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  @DisplayName("importDailyRates - when NullPointerException - handles gracefully")
  void importDailyRates_WhenNullPointerException_HandlesGracefully() {
    // Arrange
    var exception = new NullPointerException("Null value encountered");
    doThrow(exception).when(importService).importLatestExchangeRates();

    // Act - should not throw exception
    scheduler.importDailyRates();

    // Assert - verify exception was caught and metrics recorded with correct error tag
    Counter counter =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "failure")
            .tag("error", "NullPointerException")
            .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1);

    // Verify retry was scheduled
    verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
  }

  // ===========================================================================================
  // Test Cases - Retry Delay Configuration
  // ===========================================================================================

  @Test
  @DisplayName("importDailyRates - uses configured delay minutes")
  void importDailyRates_UsesConfiguredDelayMinutes() {
    // Arrange - set custom delay
    var retry = new CurrencyServiceProperties.ExchangeRateImport.Retry();
    retry.setMaxAttempts(3);
    retry.setDelayMinutes(5); // 5 minutes
    properties.getExchangeRateImport().setRetry(retry);

    var exception = new RuntimeException("Failure");
    when(importService.importLatestExchangeRates()).thenThrow(exception);

    // Act
    scheduler.importDailyRates();

    // Assert - verify retry scheduled with 5 minute delay
    ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(taskScheduler).schedule(any(Runnable.class), instantCaptor.capture());

    Instant scheduledTime = instantCaptor.getValue();
    long delaySeconds = scheduledTime.getEpochSecond() - Instant.now().getEpochSecond();
    assertThat(delaySeconds).isBetween(295L, 305L); // 5 minutes ± 5 seconds tolerance
  }
}
