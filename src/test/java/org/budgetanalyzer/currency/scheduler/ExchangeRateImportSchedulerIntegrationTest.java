package org.budgetanalyzer.currency.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.ExchangeRateImportService;

/**
 * Integration tests for {@link ExchangeRateImportScheduler} with ShedLock coordination.
 *
 * <p><b>Purpose:</b> Test scheduler with real ShedLock coordination using PostgreSQL TestContainer.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Basic scheduled execution with real database persistence
 *   <li>ShedLock acquisition and release behavior
 *   <li>Concurrent execution prevention (multi-thread simulation)
 *   <li>Lock behavior on exceptions
 *   <li>Lock expiration and reclamation
 * </ul>
 *
 * <p><b>Infrastructure:</b>
 *
 * <ul>
 *   <li>PostgreSQL TestContainer (includes shedlock table via Flyway migration V2)
 *   <li>Real Spring Boot context with scheduling enabled
 *   <li>WireMock server to stub FRED API responses (no external calls)
 *   <li>Test TaskScheduler that executes retries immediately (no real time delays)
 * </ul>
 *
 * <p><b>Note:</b> Retry mechanism and metrics are tested in unit tests. These integration tests
 * focus on ShedLock distributed coordination behavior and end-to-end flow.
 */
@Import(TestTaskSchedulerConfig.class)
class ExchangeRateImportSchedulerIntegrationTest extends AbstractWireMockTest {

  // ===========================================================================================
  // Test Dependencies
  // ===========================================================================================

  @Autowired private ExchangeRateImportScheduler scheduler;

  @Autowired private ExchangeRateImportService importService;

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private WireMockServer wireMockServer;

  private SchedulerTestHelper lockHelper;

  // ===========================================================================================
  // Setup
  // ===========================================================================================

  @BeforeEach
  void setUp() {
    super.resetDatabaseAndWireMock();
    lockHelper = new SchedulerTestHelper(jdbcTemplate);
    lockHelper.clearShedLock();

    // Clear all meters from registry to prevent test isolation issues
    // MeterRegistry is a shared Spring bean, so metrics accumulate across tests
    meterRegistry.clear();

    // Create test currency series for import to work
    // The scheduler calls importLatestExchangeRates() which imports ALL enabled series
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    // Default stub: Success response for EUR series (can be overridden per test)
    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(
            new FredApiStubs.Observation(TestConstants.DATE_2024_JAN_01.toString(), "0.8500"),
            new FredApiStubs.Observation(TestConstants.DATE_2024_JAN_02.toString(), "0.8510")));
  }

  // ===========================================================================================
  // Test Cases - Basic Execution
  // ===========================================================================================

  @Test
  void shouldExecuteSuccessfully() {
    // Arrange - WireMock already stubbed in setUp() with success response
    // Get initial total count of all exchange rates
    long initialCount = exchangeRateRepository.count();

    // Act - Execute scheduler manually
    scheduler.importDailyRates();

    // Assert - Exchange rates persisted in database
    long finalCount = exchangeRateRepository.count();
    assertThat(finalCount).isGreaterThan(initialCount);

    // Assert - Success metrics recorded
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
  }

  // ===========================================================================================
  // Test Cases - Lock Acquisition
  // ===========================================================================================

  @Test
  void shouldAcquireShedLock() throws Exception {
    // Arrange - Stub FRED with delayed response to ensure lock is held during execution
    wireMockServer.resetAll();
    FredApiStubs.stubTimeout(TestConstants.FRED_SERIES_EUR); // 5 second delay

    // Act - Execute scheduler in separate thread so we can check lock while running
    CountDownLatch executionStarted = new CountDownLatch(1);
    CountDownLatch executionComplete = new CountDownLatch(1);

    new Thread(
            () -> {
              executionStarted.countDown();
              scheduler.importDailyRates();
              executionComplete.countDown();
            })
        .start();

    // Wait for execution to start
    executionStarted.await(5, TimeUnit.SECONDS);

    // Give scheduler time to acquire lock
    Thread.sleep(100);

    // Assert - Lock acquired in shedlock table
    lockHelper.waitForLockAcquisition("exchangeRateImport", Duration.ofSeconds(5));
    var lock = lockHelper.getLockInfo("exchangeRateImport");
    assertThat(lock).isNotNull();
    assertThat(lock.name()).isEqualTo("exchangeRateImport");
    assertThat(lock.lockUntil()).isAfter(Instant.now());
    assertThat(lock.lockedBy()).isNotNull();

    // Verify lock duration is approximately 15 minutes (lockAtMostFor)
    long lockDurationMinutes = Duration.between(lock.lockedAt(), lock.lockUntil()).toMinutes();
    assertThat(lockDurationMinutes).isBetween(14L, 16L); // Allow 1 minute tolerance

    // Wait for execution to complete
    executionComplete.await(10, TimeUnit.SECONDS);
  }

  // ===========================================================================================
  // Test Cases - Lock Release
  // ===========================================================================================

  @Test
  void shouldReleaseLockAfterCompletion() {
    // Arrange - WireMock already stubbed in setUp() with success response

    // Act - Execute scheduler
    scheduler.importDailyRates();

    // Assert - Lock released (lock_until is in the past)
    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> lockHelper.verifyLockReleased("exchangeRateImport"));
  }

  // ===========================================================================================
  // Test Cases - Concurrent Execution Prevention
  // ===========================================================================================

  @Test
  void shouldPreventConcurrentExecution() throws Exception {
    // Arrange - Stub FRED with delay to ensure threads overlap
    wireMockServer.resetAll();
    FredApiStubs.stubTimeout(TestConstants.FRED_SERIES_EUR); // 5 second delay

    // Track how many times import actually runs (check database changes)
    long initialCount = exchangeRateRepository.count();

    // Act - Create 2 threads attempting to execute simultaneously
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(2);

    Runnable task =
        () -> {
          try {
            startLatch.await(); // Wait for signal to start
            scheduler.importDailyRates();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            completionLatch.countDown();
          }
        };

    new Thread(task).start();
    new Thread(task).start();

    // Release both threads simultaneously
    startLatch.countDown();

    // Wait for both threads to complete
    completionLatch.await(15, TimeUnit.SECONDS);

    // Assert - Only one execution completed (ShedLock prevented concurrent execution)
    // Since both fail due to timeout, check metrics instead
    Counter successCounter =
        meterRegistry.find("exchange.rate.import.executions").tag("status", "success").counter();
    Counter failureCounter =
        meterRegistry.find("exchange.rate.import.executions").tag("status", "failure").counter();

    // At most one should have executed (other was blocked by lock)
    long totalExecutions =
        (successCounter != null ? (long) successCounter.count() : 0)
            + (failureCounter != null ? (long) failureCounter.count() : 0);
    assertThat(totalExecutions)
        .as("Only one thread should execute due to ShedLock")
        .isLessThanOrEqualTo(1);

    // Assert - shedlock table shows single lock holder
    var lock = lockHelper.getLockInfo("exchangeRateImport");
    if (lock != null) {
      assertThat(lock.lockedBy()).isNotNull();
    }
  }

  // ===========================================================================================
  // Test Cases - Lock on Exception
  // ===========================================================================================

  @Test
  void shouldReleaseLockOnException() {
    // Arrange - Stub FRED to return server error
    wireMockServer.resetAll();
    FredApiStubs.stubServerError(TestConstants.FRED_SERIES_EUR);

    // Act - Execute scheduler (will fail)
    scheduler.importDailyRates();

    // Assert - Lock released despite exception
    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> lockHelper.verifyLockReleased("exchangeRateImport"));

    // Assert - Subsequent execution can acquire lock
    wireMockServer.resetAll();
    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(new FredApiStubs.Observation(TestConstants.DATE_2024_JAN_01.toString(), "0.8500")));

    scheduler.importDailyRates();

    // Verify success metrics recorded for second attempt
    Timer timer =
        meterRegistry
            .find("exchange.rate.import.duration")
            .tag("status", "success")
            .tag("attempt", "1")
            .timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);
  }

  // ===========================================================================================
  // Test Cases - Lock Expiration
  // ===========================================================================================

  @Test
  void shouldAcquireExpiredLock() {
    // Arrange - Insert expired lock from previous "instance"
    lockHelper.insertExpiredLock("exchangeRateImport", "previous-instance-12345");

    // Verify lock exists but is expired
    var expiredLock = lockHelper.getLockInfo("exchangeRateImport");
    assertThat(expiredLock).isNotNull();
    assertThat(expiredLock.lockUntil()).isBefore(Instant.now());
    assertThat(expiredLock.lockedBy()).isEqualTo("previous-instance-12345");

    // Arrange - WireMock already stubbed in setUp() with success response

    // Act - Execute scheduler
    scheduler.importDailyRates();

    // Assert - New instance acquired expired lock
    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              var newLock = lockHelper.getLockInfo("exchangeRateImport");
              if (newLock != null) {
                // Lock may still exist if not cleaned up
                assertThat(newLock.lockedBy()).isNotEqualTo("previous-instance-12345");
              }
            });

    // Assert - Import succeeded
    Timer timer =
        meterRegistry
            .find("exchange.rate.import.duration")
            .tag("status", "success")
            .tag("attempt", "1")
            .timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);
  }

  // ===========================================================================================
  // Test Cases - Failure Recovery
  // ===========================================================================================

  @Test
  void shouldRecoverFromServiceException() {
    // Arrange - First call fails with server error
    wireMockServer.resetAll();
    FredApiStubs.stubServerError(TestConstants.FRED_SERIES_EUR);

    // Act - Execute scheduler (will fail)
    scheduler.importDailyRates();

    // Assert - Failure metrics recorded
    Counter failureCounter =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "failure")
            .tag("attempt", "1")
            .counter();
    assertThat(failureCounter).isNotNull();
    assertThat(failureCounter.count()).isEqualTo(1);

    // Arrange - Second call succeeds
    wireMockServer.resetAll();
    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(new FredApiStubs.Observation(TestConstants.DATE_2024_JAN_01.toString(), "0.8500")));

    // Get exchange rate count before successful import
    long countBeforeSuccess = exchangeRateRepository.count();

    // Act - Execute scheduler again (will succeed)
    scheduler.importDailyRates();

    // Assert - Success metrics recorded
    Counter successCounter =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "success")
            .tag("attempt", "1")
            .counter();
    assertThat(successCounter).isNotNull();
    assertThat(successCounter.count()).isEqualTo(1);

    // Assert - No data corruption (only successful import persisted)
    long finalCount = exchangeRateRepository.count();
    assertThat(finalCount).isGreaterThan(countBeforeSuccess);
  }

  // ===========================================================================================
  // Test Cases - Lock Duration Validation
  // ===========================================================================================

  @Test
  @DisplayName("ImportDailyRates - lock duration approximately 15 minutes (lockAtMostFor)")
  void shouldHaveLockDurationOf15Minutes() throws Exception {
    // Arrange - Stub FRED with delay to ensure we can inspect lock during execution
    wireMockServer.resetAll();
    FredApiStubs.stubTimeout(TestConstants.FRED_SERIES_EUR); // 5 second delay

    // Act - Execute scheduler in separate thread
    CountDownLatch executionStarted = new CountDownLatch(1);
    CountDownLatch executionComplete = new CountDownLatch(1);

    new Thread(
            () -> {
              executionStarted.countDown();
              scheduler.importDailyRates();
              executionComplete.countDown();
            })
        .start();

    // Wait for execution to start
    executionStarted.await(5, TimeUnit.SECONDS);

    // Wait for lock acquisition
    lockHelper.waitForLockAcquisition("exchangeRateImport", Duration.ofSeconds(5));

    // Assert - Verify lock duration
    var lock = lockHelper.getLockInfo("exchangeRateImport");
    assertThat(lock).isNotNull();

    // lockAtMostFor = 15 minutes
    long lockDurationMinutes = Duration.between(lock.lockedAt(), lock.lockUntil()).toMinutes();
    assertThat(lockDurationMinutes)
        .as("Lock duration should be approximately 15 minutes (lockAtMostFor)")
        .isBetween(14L, 16L); // Allow 1 minute tolerance for clock precision

    // lockAtLeastFor = 1 minute (verify execution doesn't complete before 1 minute)
    // Note: This is hard to test without actually waiting 1 minute, so we verify the config instead
    assertThat(lock.lockUntil())
        .as("Lock should extend at least 1 minute into the future")
        .isAfter(lock.lockedAt().plus(Duration.ofMinutes(1)));

    // Wait for execution to complete
    executionComplete.await(15, TimeUnit.SECONDS);
  }

  // ===========================================================================================
  // Test Cases - Multi-Instance Simulation
  // ===========================================================================================

  @Test
  void shouldAllowOnlyOneExecutionWhenMultipleInstances() throws Exception {
    // Arrange - Stub FRED with delay to ensure instances overlap
    wireMockServer.resetAll();
    FredApiStubs.stubTimeout(TestConstants.FRED_SERIES_EUR); // 5 second delay

    // Track execution attempts via database
    long initialCount = exchangeRateRepository.count();

    // Act - Simulate 3 "pods" (threads) trying to execute simultaneously
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(3);

    Runnable task =
        () -> {
          try {
            startLatch.await(); // Wait for signal to start
            scheduler.importDailyRates();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            completionLatch.countDown();
          }
        };

    // Launch 3 "instances"
    new Thread(task, "pod-1").start();
    new Thread(task, "pod-2").start();
    new Thread(task, "pod-3").start();

    // Release all threads simultaneously
    startLatch.countDown();

    // Wait for all threads to complete
    completionLatch.await(20, TimeUnit.SECONDS);

    // Assert - Only one execution occurred
    Counter successCounter =
        meterRegistry.find("exchange.rate.import.executions").tag("status", "success").counter();
    Counter failureCounter =
        meterRegistry.find("exchange.rate.import.executions").tag("status", "failure").counter();

    // Total executions should be at most 1 (ShedLock prevents concurrent execution)
    long totalExecutions =
        (successCounter != null ? (long) successCounter.count() : 0)
            + (failureCounter != null ? (long) failureCounter.count() : 0);
    assertThat(totalExecutions)
        .as("Only one pod should execute due to ShedLock coordination")
        .isLessThanOrEqualTo(1);

    // Verify only one lock holder ever existed
    var lock = lockHelper.getLockInfo("exchangeRateImport");
    if (lock != null) {
      assertThat(lock.lockedBy()).isNotNull();
    }
  }

  // ===========================================================================================
  // Test Cases - Retry Mechanism Integration
  // ===========================================================================================

  @Test
  @DisplayName("ImportDailyRates - retries automatically on failure and eventually succeeds")
  void shouldRetryOnFailureAndEventuallySucceed() throws Exception {
    // Arrange - Stub FRED to fail first, then succeed on retry
    // Use WireMock scenario for stateful behavior
    wireMockServer.resetAll();
    FredApiStubs.stubRecoveryScenario(
        TestConstants.FRED_SERIES_EUR); // Fails twice, succeeds third time

    // Act - Execute scheduler (will fail initially)
    scheduler.importDailyRates();

    // Assert - Failure metrics recorded for attempt 1
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              Counter failureCounter =
                  meterRegistry
                      .find("exchange.rate.import.executions")
                      .tag("status", "failure")
                      .tag("attempt", "1")
                      .counter();
              assertThat(failureCounter).isNotNull();
              assertThat(failureCounter.count()).isEqualTo(1);
            });

    // Assert - Retry scheduled
    Counter retryCounter =
        meterRegistry.find("exchange.rate.import.retry.scheduled").tag("attempt", "2").counter();
    assertThat(retryCounter).isNotNull();
    assertThat(retryCounter.count()).isEqualTo(1);

    // Wait for retry to execute (TestTaskScheduler executes immediately, no real delays)
    // The retry happens asynchronously via TaskScheduler
    await()
        .atMost(Duration.ofSeconds(5)) // Fast execution with immediate task scheduler
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              // Check for attempt 2 failure
              Counter attempt2Failure =
                  meterRegistry
                      .find("exchange.rate.import.executions")
                      .tag("status", "failure")
                      .tag("attempt", "2")
                      .counter();
              assertThat(attempt2Failure).isNotNull();
              assertThat(attempt2Failure.count()).isEqualTo(1);
            });

    // Wait for third attempt to succeed
    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              Counter successCounter =
                  meterRegistry
                      .find("exchange.rate.import.executions")
                      .tag("status", "success")
                      .tag("attempt", "3")
                      .counter();
              assertThat(successCounter).isNotNull();
              assertThat(successCounter.count()).isEqualTo(1);
            });

    // Verify data was imported successfully
    assertThat(exchangeRateRepository.count()).isGreaterThan(0);
  }

  // ===========================================================================================
  // Nested Test Classes for Property Overrides
  // ===========================================================================================

  /**
   * Tests retry max attempts configuration.
   *
   * <p>Nested class required because @TestPropertySource can only be used at class level in JUnit
   * 5.
   */
  @Nested
  @DisplayName("Retry Max Attempts Tests")
  @TestPropertySource(properties = "currency-service.exchange-rate-import.retry.max-attempts=3")
  @Import(TestTaskSchedulerConfig.class)
  class RetryMaxAttemptsTests {

    @Test
    void shouldRespectMaxRetries() throws Exception {
      // Arrange - Stub FRED to always fail
      wireMockServer.resetAll();
      FredApiStubs.stubServerErrorForAll(); // All requests fail

      // Act - Execute scheduler (will fail all attempts)
      scheduler.importDailyRates();

      // Assert - Wait for all retry attempts to complete (immediate execution with test scheduler)
      await()
          .atMost(Duration.ofSeconds(5)) // Fast execution with immediate task scheduler
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(
              () -> {
                // Verify exhaustion metric incremented
                Counter exhaustedCounter =
                    meterRegistry.find("exchange.rate.import.exhausted").counter();
                assertThat(exhaustedCounter).isNotNull();
                assertThat(exhaustedCounter.count()).isEqualTo(1);
              });

      // Assert - Exactly 3 attempts made (1 initial + 2 retries)
      Counter attempt1 =
          meterRegistry
              .find("exchange.rate.import.executions")
              .tag("status", "failure")
              .tag("attempt", "1")
              .counter();
      Counter attempt2 =
          meterRegistry
              .find("exchange.rate.import.executions")
              .tag("status", "failure")
              .tag("attempt", "2")
              .counter();
      Counter attempt3 =
          meterRegistry
              .find("exchange.rate.import.executions")
              .tag("status", "failure")
              .tag("attempt", "3")
              .counter();

      assertThat(attempt1).isNotNull();
      assertThat(attempt1.count()).isEqualTo(1);
      assertThat(attempt2).isNotNull();
      assertThat(attempt2.count()).isEqualTo(1);
      assertThat(attempt3).isNotNull();
      assertThat(attempt3.count()).isEqualTo(1);

      // Assert - No 4th attempt
      Counter attempt4 =
          meterRegistry.find("exchange.rate.import.executions").tag("attempt", "4").counter();
      assertThat(attempt4).isNull();
    }
  }

  /**
   * Tests configurable retry delay.
   *
   * <p>Nested class required because @TestPropertySource can only be used at class level in JUnit
   * 5.
   *
   * <p><b>Note:</b> This test verifies retry configuration is respected, but uses immediate task
   * scheduler so we cannot verify exact timing. The retry delay configuration is unit tested
   * separately. This integration test focuses on end-to-end retry flow.
   */
  @Nested
  @DisplayName("Configurable Retry Delay Tests")
  @TestPropertySource(
      properties = {
        "currency-service.exchange-rate-import.retry.max-attempts=2",
        "currency-service.exchange-rate-import.retry.delay-minutes=2"
      })
  @Import(TestTaskSchedulerConfig.class)
  class ConfigurableRetryDelayTests {

    @Test
    void shouldRespectRetryConfiguration() throws Exception {
      // Arrange - Stub FRED to always fail
      wireMockServer.resetAll();
      FredApiStubs.stubServerErrorForAll();

      // Act - Execute scheduler (will fail and schedule retry)
      scheduler.importDailyRates();

      // Assert - First attempt failed
      await()
          .atMost(Duration.ofSeconds(5))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(
              () -> {
                Counter failureCounter =
                    meterRegistry
                        .find("exchange.rate.import.executions")
                        .tag("status", "failure")
                        .tag("attempt", "1")
                        .counter();
                assertThat(failureCounter).isNotNull();
                assertThat(failureCounter.count()).isEqualTo(1);
              });

      // Assert - Wait for retry to execute (immediate with test scheduler)
      await()
          .atMost(Duration.ofSeconds(5)) // Fast execution with immediate task scheduler
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(
              () -> {
                Counter attempt2 =
                    meterRegistry
                        .find("exchange.rate.import.executions")
                        .tag("status", "failure")
                        .tag("attempt", "2")
                        .counter();
                assertThat(attempt2).isNotNull();
                assertThat(attempt2.count()).isEqualTo(1);
              });

      // Assert - Exactly 2 attempts made (max-attempts=2)
      Counter attempt3 =
          meterRegistry.find("exchange.rate.import.executions").tag("attempt", "3").counter();
      assertThat(attempt3).as("Should not exceed max-attempts configuration (2)").isNull();
    }
  }
}
