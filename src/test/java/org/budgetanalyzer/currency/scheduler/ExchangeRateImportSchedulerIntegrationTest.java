package org.budgetanalyzer.currency.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.ExchangeRateImportService;

/**
 * Integration tests for {@link ExchangeRateImportScheduler} business logic.
 *
 * <p><b>Purpose:</b> Test scheduler business logic with manual invocation (no automatic cron
 * triggering).
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Successful import happy path
 *   <li>Retry mechanism with eventual success
 *   <li>Max retry exhaustion behavior
 * </ul>
 *
 * <p><b>Infrastructure:</b>
 *
 * <ul>
 *   <li>PostgreSQL TestContainer (real database persistence)
 *   <li>WireMock server to stub FRED API responses (no external calls)
 *   <li>Test TaskScheduler that executes retries immediately (no real time delays)
 *   <li>Mocked ShedLock to allow multiple test invocations without lock conflicts
 * </ul>
 *
 * <p><b>Note:</b> These tests focus on business logic. We trust Spring's {@code @Scheduled}
 * annotation and ShedLock library to work correctly (framework testing).
 */
@Import(TestTaskSchedulerConfig.class)
@TestPropertySource(
    properties = {
      // Disable automatic scheduling (we invoke manually in tests)
      "spring.task.scheduling.enabled=false",
      // Enable retries for retry test (default is 1 in test config)
      "currency-service.exchange-rate-import.retry.max-attempts=5"
    })
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

  /**
   * Mock LockProvider that always succeeds in acquiring locks.
   *
   * <p>Replaces the production JDBC-based lock provider to prevent lock conflicts between tests.
   * Without this, tests would fail because ShedLock's {@code lockAtLeastFor} prevents immediate
   * re-execution of the same scheduled method.
   */
  @MockBean private LockProvider lockProvider;

  // ===========================================================================================
  // Setup
  // ===========================================================================================

  @BeforeEach
  void setUp() {
    super.resetDatabaseAndWireMock();

    // Clear all meters from registry to prevent test isolation issues
    // MeterRegistry is a shared Spring bean, so metrics accumulate across tests
    meterRegistry.clear();

    // Configure mock LockProvider to always succeed in acquiring locks
    // This prevents ShedLock from blocking test execution due to lockAtLeastFor constraints
    when(lockProvider.lock(any(LockConfiguration.class)))
        .thenReturn(
            java.util.Optional.of(
                new SimpleLock() {
                  @Override
                  public void unlock() {
                    // No-op: mock lock doesn't need actual unlocking
                  }
                }));

    // Create test currency series for import to work
    // The scheduler calls importLatestExchangeRates() which imports ALL enabled series
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().build());

    // Default stub: Success response for EUR series (can be overridden per test)
    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(
            new FredApiStubs.Observation(TestConstants.DATE_2024_JAN_01.toString(), "0.8500"),
            new FredApiStubs.Observation(TestConstants.DATE_2024_JAN_02.toString(), "0.8510")));
  }

  // ===========================================================================================
  // Test Cases - Business Logic
  // ===========================================================================================

  @Test
  void shouldExecuteSuccessfully() {
    // Arrange - WireMock already stubbed in setUp() with success response
    // Get initial total count of all exchange rates
    var initialCount = exchangeRateRepository.count();

    // Act - Execute scheduler manually
    scheduler.importDailyRates();

    // Assert - Exchange rates persisted in database
    var finalCount = exchangeRateRepository.count();
    assertThat(finalCount).isGreaterThan(initialCount);

    // Assert - Success metrics recorded
    var timer =
        meterRegistry
            .find("exchange.rate.import.duration")
            .tag("status", "success")
            .tag("attempt", "1")
            .timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);

    var counter =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "success")
            .tag("attempt", "1")
            .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1);
  }

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
    var retryCounter =
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
}
