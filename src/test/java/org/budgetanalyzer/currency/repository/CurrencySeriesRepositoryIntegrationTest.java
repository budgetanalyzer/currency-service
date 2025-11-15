package org.budgetanalyzer.currency.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.NonTransientDataAccessException;

import org.budgetanalyzer.currency.base.AbstractIntegrationTest;
import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.ExchangeRateTestBuilder;
import org.budgetanalyzer.currency.fixture.TestConstants;

/**
 * Integration tests for {@link CurrencySeriesRepository}.
 *
 * <p>Tests validate:
 *
 * <ul>
 *   <li>Query methods (derived and custom queries)
 *   <li>Cascade/delete behavior with related entities
 * </ul>
 *
 * <p>Tests run against real PostgreSQL via TestContainers with automatic transaction rollback for
 * isolation.
 */
class CurrencySeriesRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  // ===========================================================================================
  // Query Method Tests - findByEnabledTrue()
  // ===========================================================================================

  @Test
  void findByEnabledTrueWithMixedSeriesReturnsOnlyEnabled() {
    // Arrange: Use existing seed data and update enabled status
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    var thbSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_THB).orElseThrow();
    var gbpSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_GBP).orElseThrow();

    // Set up test scenario: EUR and THB enabled, GBP and JPY disabled
    eurSeries.setEnabled(true);
    thbSeries.setEnabled(true);
    gbpSeries.setEnabled(false);

    var jpySeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_JPY).orElseThrow();
    jpySeries.setEnabled(false);

    currencySeriesRepository.saveAll(java.util.List.of(eurSeries, thbSeries, gbpSeries, jpySeries));
    currencySeriesRepository.flush();

    // Act
    var enabledSeries = currencySeriesRepository.findByEnabledTrue();

    // Assert
    assertThat(enabledSeries)
        .hasSizeGreaterThanOrEqualTo(2)
        .extracting(CurrencySeries::getCurrencyCode)
        .contains(TestConstants.VALID_CURRENCY_EUR, TestConstants.VALID_CURRENCY_THB);
  }

  @Test
  void findByEnabledTrueWithAllDisabledReturnsEmptyList() {
    // Arrange: Disable all existing series
    var allSeries = currencySeriesRepository.findAll();
    allSeries.forEach(series -> series.setEnabled(false));
    currencySeriesRepository.saveAll(allSeries);
    currencySeriesRepository.flush();

    // Act
    var enabledSeries = currencySeriesRepository.findByEnabledTrue();

    // Assert
    assertThat(enabledSeries).isEmpty();
  }

  @Test
  void findByEnabledTrueWithAllEnabledReturnsAll() {
    // Arrange: Enable all existing series (23 from V6 migration)
    var allSeries = currencySeriesRepository.findAll();
    allSeries.forEach(series -> series.setEnabled(true));
    currencySeriesRepository.saveAll(allSeries);
    currencySeriesRepository.flush();

    // Act
    var enabledSeries = currencySeriesRepository.findByEnabledTrue();

    // Assert: All 23 seed currencies should be enabled
    assertThat(enabledSeries)
        .hasSize(23)
        .extracting(CurrencySeries::getCurrencyCode)
        .contains(
            TestConstants.VALID_CURRENCY_EUR,
            TestConstants.VALID_CURRENCY_THB,
            TestConstants.VALID_CURRENCY_GBP);
  }

  // ===========================================================================================
  // Query Method Tests - findByCurrencyCodeAndEnabledTrue()
  // ===========================================================================================

  @Test
  void findByCurrencyCodeAndEnabledTrueWithEnabledCurrencyFindsCurrency() {
    // Arrange: Enable existing EUR currency
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    eurSeries.setEnabled(true);
    currencySeriesRepository.saveAndFlush(eurSeries);

    // Act
    var found =
        currencySeriesRepository.findByCurrencyCodeAndEnabledTrue(TestConstants.VALID_CURRENCY_EUR);

    // Assert
    assertThat(found)
        .isPresent()
        .get()
        .extracting(CurrencySeries::getCurrencyCode)
        .isEqualTo(TestConstants.VALID_CURRENCY_EUR);
  }

  @Test
  void findByCurrencyCodeAndEnabledTrueWithDisabledCurrencyReturnsEmpty() {
    // Arrange: Ensure EUR is disabled (default state from V6 migration)
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    eurSeries.setEnabled(false);
    currencySeriesRepository.saveAndFlush(eurSeries);

    // Act
    var found =
        currencySeriesRepository.findByCurrencyCodeAndEnabledTrue(TestConstants.VALID_CURRENCY_EUR);

    // Assert
    assertThat(found).isEmpty();
  }

  @Test
  void findByCurrencyCodeAndEnabledTrueWithNonExistentCurrencyReturnsEmpty() {
    // Act
    var found = currencySeriesRepository.findByCurrencyCodeAndEnabledTrue("XXX");

    // Assert
    assertThat(found).isEmpty();
  }

  @Test
  void findByCurrencyCodeAndEnabledTrueIsCaseSensitive() {
    // Arrange: Enable existing EUR currency
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    eurSeries.setEnabled(true);
    currencySeriesRepository.saveAndFlush(eurSeries);

    // Act: Query with lowercase (should not find uppercase EUR)
    var found = currencySeriesRepository.findByCurrencyCodeAndEnabledTrue("eur");

    // Assert
    assertThat(found).isEmpty();
  }

  // ===========================================================================================
  // Query Method Tests - findByCurrencyCode()
  // ===========================================================================================

  @Test
  void findByCurrencyCodeFindsCurrencyRegardlessOfEnabledStatus() {
    // Arrange: EUR exists as disabled from V6 migration, ensure it's disabled
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    eurSeries.setEnabled(false);
    currencySeriesRepository.saveAndFlush(eurSeries);

    // Act
    var found = currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR);

    // Assert
    assertThat(found).isPresent().get().extracting(CurrencySeries::isEnabled).isEqualTo(false);
  }

  @Test
  void findByCurrencyCodeWithNonExistentCurrencyReturnsEmpty() {
    // Act
    var found = currencySeriesRepository.findByCurrencyCode("ZZZ");

    // Assert
    assertThat(found).isEmpty();
  }

  // ===========================================================================================
  // Cascade/Delete Behavior Tests
  // ===========================================================================================

  @Test
  void deleteWithExistingExchangeRatesThrowsException() {
    // Arrange: Use existing EUR series from V6 migration and add exchange rates
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var exchangeRate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(TestConstants.RATE_EUR_USD)
            .build();
    exchangeRateRepository.saveAndFlush(exchangeRate);

    // Act & Assert: Try to delete series with related exchange rates
    assertThatThrownBy(
            () -> {
              currencySeriesRepository.delete(eurSeries);
              currencySeriesRepository.flush();
            })
        .isInstanceOf(NonTransientDataAccessException.class);
  }

  @Test
  void deleteWithoutExchangeRatesSucceeds() {
    // Arrange: Create unique test currency series WITHOUT exchange rates
    var testSeries =
        new CurrencySeriesTestBuilder()
            .withCurrencyCode("XXX")
            .withProviderSeriesId("TEST_DELETE_SERIES")
            .build();
    var saved = currencySeriesRepository.saveAndFlush(testSeries);

    // Act: Delete series
    currencySeriesRepository.delete(saved);
    currencySeriesRepository.flush();

    // Assert: Verify deletion succeeded
    var found = currencySeriesRepository.findById(saved.getId());
    assertThat(found).isEmpty();
  }
}
