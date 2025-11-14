package org.budgetanalyzer.currency.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.budgetanalyzer.currency.config.TestContainersConfig;
import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.domain.event.CurrencyCreatedEvent;
import org.budgetanalyzer.currency.domain.event.CurrencyUpdatedEvent;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.service.CurrencyService;
import org.budgetanalyzer.currency.service.provider.ExchangeRateProvider;

/**
 * Integration tests for domain event publishing using Spring Modulith.
 *
 * <p>Tests domain event persistence to event_publication table (transactional outbox pattern):
 *
 * <ol>
 *   <li>CurrencyCreatedEvent publishing for enabled currencies
 *   <li>CurrencyUpdatedEvent publishing for currency state changes
 *   <li>Event publishing for disabled currencies (truthful domain events)
 * </ol>
 *
 * <p><b>Test Infrastructure:</b>
 *
 * <ul>
 *   <li>Uses @SpringBootTest with full application context
 *   <li>@EnableScenarios for Spring Modulith declarative event testing
 *   <li>Reuses TestContainersConfig for infrastructure (PostgreSQL, Redis, RabbitMQ)
 *   <li>Mock ExchangeRateProvider to prevent real FRED API calls
 *   <li>Simple cleanup strategy with DELETE in @BeforeEach
 * </ul>
 *
 * <p><b>Testing Strategy:</b>
 *
 * <p>These tests focus on verifying that domain events are correctly persisted to the
 * event_publication table using Spring Modulith's Scenario API. They do NOT test downstream message
 * processing or exchange rate imports - those are covered in MessagingIntegrationTest.
 *
 * @see org.springframework.boot.test.context.SpringBootTest
 * @see org.springframework.modulith.test.EnableScenarios
 * @see org.springframework.modulith.test.Scenario
 */
@SpringBootTest
@Testcontainers
@Import(TestContainersConfig.class)
@EnableScenarios
@DirtiesContext
class DomainEventPublishingIntegrationTest {

  // ===========================================================================================
  // Dependencies
  // ===========================================================================================

  @Autowired JdbcTemplate jdbcTemplate;

  @Autowired CurrencyService currencyService;

  @MockitoBean private ExchangeRateProvider mockProvider;

  // ===========================================================================================
  // Setup and Cleanup
  // ===========================================================================================

  /** Cleanup database before each test. */
  @BeforeEach
  void cleanup() {
    // Clear database tables
    jdbcTemplate.execute("DELETE FROM exchange_rate");
    jdbcTemplate.execute("DELETE FROM currency_series");
    jdbcTemplate.execute("DELETE FROM event_publication");

    // Clear mock invocations
    clearInvocations(mockProvider);
  }

  // ===========================================================================================
  // Event Publishing Tests (3 tests)
  // ===========================================================================================

  /**
   * Test that CurrencyCreatedEvent is persisted to event_publication table when currency is
   * created.
   *
   * <p>Verifies the transactional outbox pattern: event is stored in same transaction as currency
   * entity.
   */
  @Test
  void shouldPersistCurrencyCreatedEventToDatabase(Scenario scenario) {
    // Arrange
    setupMockProvider(TestConstants.VALID_CURRENCY_EUR, TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().build();

    // Act & Assert - Declarative event verification
    scenario
        .stimulate(() -> currencyService.create(currencySeries))
        .andWaitForEventOfType(CurrencyCreatedEvent.class)
        .matching(event -> event.currencyCode().equals(TestConstants.VALID_CURRENCY_EUR))
        .toArriveAndVerify(
            event -> {
              assertThat(event.currencyCode()).isEqualTo(TestConstants.VALID_CURRENCY_EUR);
              assertThat(event.enabled()).isTrue();
            });
  }

  /**
   * Test that CurrencyUpdatedEvent is persisted to event_publication table when currency is
   * updated.
   */
  @Test
  void shouldPersistCurrencyUpdatedEventToDatabase(Scenario scenario) {
    // Arrange - Create currency first
    setupMockProvider(TestConstants.VALID_CURRENCY_EUR, TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().build();

    var created = currencyService.create(currencySeries);

    // Clear events from creation
    jdbcTemplate.execute("DELETE FROM event_publication");

    // Act & Assert - Update triggers event
    scenario
        .stimulate(() -> currencyService.update(created.getId(), false))
        .andWaitForEventOfType(CurrencyUpdatedEvent.class)
        .matching(event -> event.currencyCode().equals(TestConstants.VALID_CURRENCY_EUR))
        .toArriveAndVerify(
            event -> {
              assertThat(event.currencyCode()).isEqualTo(TestConstants.VALID_CURRENCY_EUR);
              assertThat(event.enabled()).isFalse();
            });
  }

  /**
   * Test that event is published even for disabled currency.
   *
   * <p>Domain layer always publishes truthful events; messaging layer filters based on enabled
   * flag.
   */
  @Test
  void shouldPublishEventEvenForDisabledCurrency(Scenario scenario) {
    // Arrange
    setupMockProvider(TestConstants.VALID_CURRENCY_EUR, TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().enabled(false).build();

    // Act & Assert - Event published even though currency is disabled
    scenario
        .stimulate(() -> currencyService.create(currencySeries))
        .andWaitForEventOfType(CurrencyCreatedEvent.class)
        .matching(
            event ->
                event.currencyCode().equals(TestConstants.VALID_CURRENCY_EUR) && !event.enabled())
        .toArriveAndVerify(
            event -> {
              assertThat(event.currencyCode()).isEqualTo(TestConstants.VALID_CURRENCY_EUR);
              assertThat(event.enabled()).isFalse();
            });
  }

  // ===========================================================================================
  // Helper Methods
  // ===========================================================================================

  /**
   * Setup mock provider to return test exchange rates.
   *
   * @param currencyCode The currency code
   * @param seriesId The provider series ID
   */
  private void setupMockProvider(String currencyCode, String seriesId) {
    when(mockProvider.validateSeriesExists(seriesId)).thenReturn(true);
    var rates =
        Map.of(
            LocalDate.of(2024, 1, 1), new BigDecimal("1.10"),
            LocalDate.of(2024, 1, 2), new BigDecimal("1.11"),
            LocalDate.of(2024, 1, 3), new BigDecimal("1.12"));
    when(mockProvider.getExchangeRates(any(CurrencySeries.class), any())).thenReturn(rates);
  }
}
