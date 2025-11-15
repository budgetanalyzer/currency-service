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
 * Tests retry max attempts configuration for {@link ExchangeRateImportScheduler}.
 *
 * <p>This test class is separate from the main integration test because it requires different
 * {@code @TestPropertySource} configuration. JUnit 5 only allows {@code @TestPropertySource} at the
 * class level.
 */
@Import(TestTaskSchedulerConfig.class)
@TestPropertySource(
    properties = {
      // Disable automatic scheduling (we invoke manually in tests)
      "spring.task.scheduling.enabled=false",
      // Override max retry attempts for these tests
      "currency-service.exchange-rate-import.retry.max-attempts=3"
    })
@DisplayName("ExchangeRateImportScheduler - Retry Max Attempts Tests")
class ExchangeRateImportSchedulerRetryMaxAttemptsTest extends AbstractWireMockTest {

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
  // Test Cases
  // ===========================================================================================

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
    var attempt1 =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "failure")
            .tag("attempt", "1")
            .counter();
    var attempt2 =
        meterRegistry
            .find("exchange.rate.import.executions")
            .tag("status", "failure")
            .tag("attempt", "2")
            .counter();
    var attempt3 =
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
    var attempt4 =
        meterRegistry.find("exchange.rate.import.executions").tag("attempt", "4").counter();
    assertThat(attempt4).isNull();
  }
}
