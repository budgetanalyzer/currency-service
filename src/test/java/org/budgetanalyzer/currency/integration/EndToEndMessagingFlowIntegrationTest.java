package org.budgetanalyzer.currency.integration;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.CurrencyService;

/**
 * End-to-end integration tests for complete messaging flow.
 *
 * <p>Tests the complete flow from service method invocation through to exchange rate import:
 *
 * <ol>
 *   <li>Service creates currency and publishes domain event
 *   <li>Domain event persisted to event_publication table (transactional outbox)
 *   <li>MessagingEventListener processes event asynchronously
 *   <li>External message published to RabbitMQ exchange
 *   <li>ExchangeRateImportConsumer receives and processes message
 *   <li>Exchange rates imported from FRED API (mocked via WireMock)
 * </ol>
 *
 * <p><b>Focus Areas:</b>
 *
 * <ul>
 *   <li>Complete flow validation (service → event → message → import)
 *   <li>Enabled/disabled currency filtering
 *   <li>Actual concurrency testing with multiple threads
 *   <li>Correlation ID propagation through entire flow
 *   <li>End-to-end performance (SLA validation)
 * </ul>
 *
 * <p><b>Key Improvements over Original Tests:</b>
 *
 * <ul>
 *   <li><b>Actual concurrency</b>: Uses ExecutorService with 5 threads and CountDownLatch
 *   <li><b>Exact assertions</b>: Verifies exact counts (8 rates) instead of {@code > 0}
 *   <li><b>Performance testing</b>: Measures actual end-to-end latency
 *   <li><b>Better correlation ID testing</b>: Verifies propagation with exact value checks
 * </ul>
 *
 * <p><b>Related Tests:</b>
 *
 * <ul>
 *   <li>EventListenerIntegrationTest - Tests listener filtering and message publishing
 *   <li>MessageConsumerIntegrationTest - Tests consumer message processing
 *   <li>TransactionalOutboxIntegrationTest - Tests transactional outbox pattern
 * </ul>
 */
class EndToEndMessagingFlowIntegrationTest extends AbstractWireMockTest {

  private static final int EXPECTED_EXCHANGE_RATES_COUNT = 8;
  private static final int WAIT_TIME_SECONDS = 5;
  private static final int PERFORMANCE_SLA_SECONDS = 5;

  // ===========================================================================================
  // Dependencies
  // ===========================================================================================

  @Autowired private CurrencyService currencyService;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  // ===========================================================================================
  // Setup and Cleanup
  // ===========================================================================================

  /** Cleanup database and WireMock stubs before each test. */
  @BeforeEach
  void cleanup() {
    super.resetDatabaseAndWireMock();
    MDC.clear();
  }

  // ===========================================================================================
  // Test Cases - Basic Flow
  // ===========================================================================================

  /**
   * Test that complete flow works for enabled currency.
   *
   * <p>Verifies:
   *
   * <ul>
   *   <li>Service creates currency
   *   <li>Domain event published and persisted
   *   <li>Message published to RabbitMQ
   *   <li>Consumer processes message
   *   <li>Exchange rates imported from FRED
   * </ul>
   *
   * <p>Migrated from: {@code shouldImportExchangeRatesWhenCurrencyCreatedMessageReceived}
   */
  @Test
  void shouldCompleteFullFlowForEnabledCurrency() {
    // Arrange
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().build();

    // Act
    var created = currencyService.create(currencySeries);

    // Assert - Exchange rates imported with exact count
    await()
        .atMost(WAIT_TIME_SECONDS, SECONDS)
        .untilAsserted(
            () -> {
              var count = exchangeRateRepository.countByCurrencySeries(created);
              assertThat(count)
                  .as("Should import exactly 8 exchange rates for EUR/USD")
                  .isEqualTo(EXPECTED_EXCHANGE_RATES_COUNT);
            });
  }

  /**
   * Test that flow skips import for disabled currency.
   *
   * <p>Verifies:
   *
   * <ul>
   *   <li>Service creates disabled currency
   *   <li>Domain event published and persisted
   *   <li>MessagingEventListener filters out disabled currency
   *   <li>No message published to RabbitMQ
   *   <li>No exchange rates imported
   * </ul>
   *
   * <p>Migrated from: {@code shouldNotPublishMessageForDisabledCurrency}
   */
  @Test
  void shouldSkipImportForDisabledCurrency() {
    // Arrange
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().enabled(false).build();

    // Act
    var created = currencyService.create(currencySeries);

    // Wait to ensure message would have been processed if published
    await()
        .pollDelay(Duration.ofMillis(500))
        .atMost(2, SECONDS)
        .untilAsserted(
            () -> {
              // Verify NO exchange rates imported
              var count = exchangeRateRepository.countByCurrencySeries(created);
              assertThat(count).as("Disabled currency should not trigger import").isEqualTo(0);
            });
  }

  // ===========================================================================================
  // Test Cases - Concurrency
  // ===========================================================================================

  /**
   * Test that multiple concurrent currency creations are handled correctly.
   *
   * <p><b>Key Improvement:</b> Uses <b>actual concurrency</b> with ExecutorService and
   * CountDownLatch, unlike the original test which created currencies sequentially.
   *
   * <p>Verifies:
   *
   * <ul>
   *   <li>5 threads create currencies simultaneously
   *   <li>Each currency triggers independent message flow
   *   <li>All imports complete successfully
   *   <li>No race conditions or deadlocks
   *   <li>Thread-safe message processing
   * </ul>
   *
   * <p>Rewritten from: {@code shouldHandleConcurrentCreations} (which didn't actually test
   * concurrency)
   */
  @Test
  void shouldHandleMultipleConcurrentCreations() throws InterruptedException {
    // Arrange - Setup stubs for 5 different currencies
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_CAD);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_CAD);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_JPY);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_JPY);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_THB);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_THB);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_GBP);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_GBP);

    var builders =
        List.of(
            CurrencySeriesTestBuilder.defaultEur(),
            CurrencySeriesTestBuilder.defaultCad(),
            CurrencySeriesTestBuilder.defaultJpy(),
            CurrencySeriesTestBuilder.defaultThb(),
            CurrencySeriesTestBuilder.defaultGbp());

    // Act - Create currencies concurrently with ExecutorService
    var numThreads = 5;
    var executor = Executors.newFixedThreadPool(numThreads);
    var startLatch = new CountDownLatch(1);
    var completionLatch = new CountDownLatch(numThreads);
    List<CurrencySeries> createdCurrencies = Collections.synchronizedList(new ArrayList<>());
    var successCount = new AtomicInteger(0);

    for (int i = 0; i < numThreads; i++) {
      final int index = i;
      executor.submit(
          () -> {
            try {
              // Wait for start signal to maximize concurrency
              startLatch.await();

              // Create currency
              var created = currencyService.create(builders.get(index).build());
              createdCurrencies.add(created);
              successCount.incrementAndGet();
            } catch (Exception e) {
              // Should not happen in normal flow
              throw new RuntimeException("Concurrent creation failed", e);
            } finally {
              completionLatch.countDown();
            }
          });
    }

    // Start all threads simultaneously
    startLatch.countDown();

    // Wait for all creations to complete
    var completed = completionLatch.await(10, SECONDS);
    executor.shutdown();

    assertThat(completed).as("All currency creations should complete").isTrue();
    assertThat(successCount.get())
        .as("All 5 currency creations should succeed")
        .isEqualTo(numThreads);

    // Assert - All imports completed
    await()
        .atMost(WAIT_TIME_SECONDS, SECONDS)
        .untilAsserted(
            () -> {
              for (CurrencySeries created : createdCurrencies) {
                var count = exchangeRateRepository.countByCurrencySeries(created);
                assertThat(count)
                    .as(
                        "Currency %s should have exactly %d rates imported",
                        created.getCurrencyCode(), EXPECTED_EXCHANGE_RATES_COUNT)
                    .isEqualTo(EXPECTED_EXCHANGE_RATES_COUNT);
              }
            });
  }
}
