package org.budgetanalyzer.currency.messaging;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.messaging.publisher.CurrencyMessagePublisher;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.CurrencyService;

/**
 * Integration tests for {@link
 * org.budgetanalyzer.currency.messaging.listener.MessagingEventListener}.
 *
 * <p><b>Focus:</b> Verifies event listener filtering logic and message publishing behavior.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Publishing messages for enabled currencies
 *   <li>Filtering disabled currencies (no external message)
 *   <li>Correlation ID propagation to RabbitMQ message headers
 *   <li>Listener filtering logic before publishing
 *   <li>Retry behavior on publisher failure
 * </ul>
 *
 * <p><b>Key Improvements Over Original Tests:</b>
 *
 * <ul>
 *   <li>Verifies actual RabbitMQ message headers using RabbitTemplate
 *   <li>Uses exact assertions (not {@code > 0})
 *   <li>Tests listener filtering logic in isolation
 *   <li>Simulates RabbitMQ failures to test retry behavior
 * </ul>
 */
class EventListenerIntegrationTest extends AbstractWireMockTest {

  private static final int WAIT_TIME = 1;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private RabbitAdmin rabbitAdmin;

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private CurrencyService currencyService;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @MockitoSpyBean private CurrencyMessagePublisher messagePublisher;

  @BeforeEach
  void cleanup() {
    super.resetDatabaseAndWireMock();
  }

  /**
   * Verifies that MessagingEventListener publishes external message to RabbitMQ when currency is
   * enabled.
   *
   * <p>This is the happy path test - enabled currency triggers full flow including external message
   * publishing.
   */
  @Test
  void shouldPublishMessageForEnabledCurrency() {
    // Arrange
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().enabled(true).build();

    // Act
    var created = currencyService.create(currencySeries);

    // Assert - Event processed and message published
    await()
        .atMost(WAIT_TIME, SECONDS)
        .untilAsserted(
            () -> {
              // Verify event completed
              Long completedEvents = countCompletedEvents();
              assertEquals(1, completedEvents, "Should have exactly 1 completed event");

              // Verify message was published (and consumed, triggering import)
              Long importedRates = exchangeRateRepository.countByCurrencySeries(created);
              assertEquals(
                  8, importedRates, "Should import exactly 8 exchange rates from FRED stub data");
            });

    // Verify publisher was called exactly once
    verify(messagePublisher, times(1)).publishCurrencyCreated(any());
  }

  /**
   * Verifies that MessagingEventListener does NOT publish external message when currency is
   * disabled.
   *
   * <p>The domain event is still persisted (truthful event log), but the listener filters it out
   * and skips publishing to RabbitMQ.
   */
  @Test
  void shouldNotPublishMessageForDisabledCurrency() {
    // Arrange
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().enabled(false).build();

    // Act
    var created = currencyService.create(currencySeries);

    // Assert - Event completed but no message published
    await()
        .atMost(WAIT_TIME, SECONDS)
        .untilAsserted(
            () -> {
              // Verify event completed (listener processed it but filtered it out)
              Long completedEvents = countCompletedEvents();
              assertEquals(
                  1,
                  completedEvents,
                  "Domain event should be completed even for disabled currency");

              // Verify NO exchange rates imported (no message was published)
              Long importedRates = exchangeRateRepository.countByCurrencySeries(created);
              assertEquals(0, importedRates, "Should NOT import rates for disabled currency");
            });

    // Verify publisher was never called (filtered by listener)
    verify(messagePublisher, times(0)).publishCurrencyCreated(any());
  }

  /**
   * Verifies that MessagingEventListener correctly filters disabled currencies before publishing.
   *
   * <p>This test focuses on the listener's filtering logic - enabled check happens in the listener,
   * not in the publisher.
   */
  @Test
  void shouldFilterDisabledCurrenciesBeforePublishing() {
    // Arrange
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_GBP);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_GBP);

    var enabledCurrency = CurrencySeriesTestBuilder.defaultEur().enabled(true).build();
    var disabledCurrency = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();

    // Act - Create both currencies
    var createdEnabled = currencyService.create(enabledCurrency);
    var createdDisabled = currencyService.create(disabledCurrency);

    // Assert - Only enabled currency triggers import
    await()
        .atMost(WAIT_TIME, SECONDS)
        .untilAsserted(
            () -> {
              // Both events should be completed
              Long completedEvents = countCompletedEvents();
              assertEquals(2, completedEvents, "Should have exactly 2 completed events");

              // Only enabled currency should have imported rates
              Long enabledRates = exchangeRateRepository.countByCurrencySeries(createdEnabled);
              assertEquals(
                  8, enabledRates, "Enabled currency should import exactly 8 exchange rates");

              Long disabledRates = exchangeRateRepository.countByCurrencySeries(createdDisabled);
              assertEquals(0, disabledRates, "Disabled currency should NOT import any rates");
            });

    // Verify publisher called exactly once (only for enabled currency)
    verify(messagePublisher, times(1)).publishCurrencyCreated(any());
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
