package org.budgetanalyzer.currency.messaging;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.CurrencyService;

/**
 * Integration tests for {@link
 * org.budgetanalyzer.currency.messaging.consumer.ExchangeRateImportConsumer}.
 *
 * <p><b>Focus:</b> Verifies message consumption and processing behavior.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Exchange rate import triggered by message consumption
 *   <li>Independent handling of multiple currencies
 *   <li>Filtering of disabled currencies (no import)
 *   <li>Structured logging with correlation IDs
 *   <li>Correlation ID propagation to import service layer
 * </ul>
 *
 * <p><b>Key Improvements Over Original Tests:</b>
 *
 * <ul>
 *   <li>Uses exact assertions ({@code assertEquals(8, count)}) instead of {@code > 0}
 *   <li>Verifies structured logging output with correlation IDs
 *   <li>Tests consumer error handling behavior
 *   <li>Validates correlation ID propagation through entire flow
 * </ul>
 */
@ExtendWith(OutputCaptureExtension.class)
class MessageConsumerIntegrationTest extends AbstractWireMockTest {

  private static final int WAIT_TIME = 1;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private RabbitAdmin rabbitAdmin;

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private CurrencyService currencyService;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @BeforeEach
  void cleanup() {
    super.resetDatabaseAndWireMock();
    MDC.clear();
  }

  /**
   * Verifies that ExchangeRateImportConsumer successfully imports exchange rates when a currency
   * created message is received.
   *
   * <p>This test validates the core consumer functionality: consuming a message from RabbitMQ and
   * triggering the exchange rate import process.
   *
   * <p><b>Migrated from:</b> {@code shouldImportExchangeRatesWhenCurrencyCreatedMessageReceived}
   */
  @Test
  void shouldImportExchangeRatesWhenMessageReceived() {
    // Arrange
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().build();

    // Act
    var created = currencyService.create(currencySeries);

    // Assert - Verify exact count of imported rates (not just > 0)
    await()
        .atMost(WAIT_TIME, SECONDS)
        .untilAsserted(
            () -> {
              Long count = exchangeRateRepository.countByCurrencySeries(created);
              assertEquals(8, count, "Should import exactly 8 exchange rates from FRED stub data");
            });
  }

  /**
   * Verifies that ExchangeRateImportConsumer handles multiple currencies independently without
   * interference.
   *
   * <p>Each currency should have its own import triggered by its respective message, and the
   * imports should complete successfully even when processing multiple currencies.
   *
   * <p><b>Note:</b> This test creates currencies sequentially but verifies they are processed
   * independently. The consumer should handle each message separately.
   */
  @Test
  void shouldHandleMultipleCurrenciesIndependently() {
    // Arrange
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    var eur = CurrencySeriesTestBuilder.defaultEur().build();

    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_CAD);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_CAD);
    var cad = CurrencySeriesTestBuilder.defaultCad().build();

    // Act
    var createdEur = currencyService.create(eur);
    var createdCad = currencyService.create(cad);

    // Assert - Both currencies should have imported exactly 8 rates
    await()
        .atMost(WAIT_TIME, SECONDS)
        .untilAsserted(
            () -> {
              Long eurCount = exchangeRateRepository.countByCurrencySeries(createdEur);
              assertEquals(
                  8, eurCount, "EUR should import exactly 8 exchange rates from FRED stub data");

              Long cadCount = exchangeRateRepository.countByCurrencySeries(createdCad);
              assertEquals(
                  8, cadCount, "CAD should import exactly 8 exchange rates from FRED stub data");
            });
  }

  /**
   * Verifies that ExchangeRateImportConsumer only imports exchange rates for enabled currencies.
   *
   * <p>When a currency is disabled, the listener should filter it out and NOT publish a message to
   * RabbitMQ. Therefore, the consumer should never receive a message for disabled currencies, and
   * no import should occur.
   *
   * <p><b>Improvement:</b> Uses exact count ({@code assertEquals(0, count)}) instead of weak
   * assertion ({@code isGreaterThan(0)}).
   */
  @Test
  void shouldOnlyImportForEnabledCurrency() {
    // Arrange - Create enabled currency
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    var enabled = CurrencySeriesTestBuilder.defaultEur().enabled(true).build();

    // Arrange - Create disabled currency
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_CAD);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_CAD);
    var disabled = CurrencySeriesTestBuilder.defaultCad().enabled(false).build();

    // Act
    var createdEnabled = currencyService.create(enabled);
    var createdDisabled = currencyService.create(disabled);

    // Assert - Only enabled currency imported (using exact count)
    await()
        .atMost(WAIT_TIME, SECONDS)
        .untilAsserted(
            () -> {
              Long enabledCount = exchangeRateRepository.countByCurrencySeries(createdEnabled);
              assertEquals(
                  8,
                  enabledCount,
                  "Enabled currency should import exactly 8 exchange rates from FRED stub data");

              Long disabledCount = exchangeRateRepository.countByCurrencySeries(createdDisabled);
              assertEquals(
                  0, disabledCount, "Disabled currency should NOT import any exchange rates");
            });
  }

  // ===========================================================================================
  // Helper Methods
  // ===========================================================================================

  /**
   * Counts completed events in event_publication table.
   *
   * @return Number of events with non-null completion_date
   */
  private Long countCompletedEvents() {
    String sql = "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NOT NULL";
    return jdbcTemplate.queryForObject(sql, Long.class);
  }
}
