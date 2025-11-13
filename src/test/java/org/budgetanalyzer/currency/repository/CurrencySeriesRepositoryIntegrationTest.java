package org.budgetanalyzer.currency.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import jakarta.validation.ConstraintViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
 *   <li>Database constraints (unique, not-null, foreign keys)
 *   <li>Cascade/delete behavior with related entities
 *   <li>Audit timestamp population
 *   <li>Basic CRUD operations
 * </ul>
 *
 * <p>Tests run against real PostgreSQL via TestContainers with automatic transaction rollback for
 * isolation.
 */
@DisplayName("CurrencySeriesRepository Integration Tests")
class CurrencySeriesRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  // ===========================================================================================
  // Query Method Tests - findByEnabledTrue()
  // ===========================================================================================

  @Test
  @DisplayName(
      "findByEnabledTrue() should return only enabled series when mixed enabled/disabled exist")
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
  @DisplayName("findByEnabledTrue() should return empty list when all series are disabled")
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
  @DisplayName("findByEnabledTrue() should return all series when all are enabled")
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
  @DisplayName("findByCurrencyCodeAndEnabledTrue() should find enabled currency by code")
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
  @DisplayName("findByCurrencyCodeAndEnabledTrue() should return empty when currency is disabled")
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
  @DisplayName("findByCurrencyCodeAndEnabledTrue() should return empty when currency doesn't exist")
  void findByCurrencyCodeAndEnabledTrueWithNonExistentCurrencyReturnsEmpty() {
    // Act
    var found = currencySeriesRepository.findByCurrencyCodeAndEnabledTrue("XXX");

    // Assert
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("findByCurrencyCodeAndEnabledTrue() should be case-sensitive")
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
  @DisplayName("findByCurrencyCode() should find currency regardless of enabled status")
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
  @DisplayName("findByCurrencyCode() should return empty when currency doesn't exist")
  void findByCurrencyCodeWithNonExistentCurrencyReturnsEmpty() {
    // Act
    var found = currencySeriesRepository.findByCurrencyCode("ZZZ");

    // Assert
    assertThat(found).isEmpty();
  }

  // ===========================================================================================
  // Database Constraint Tests
  // ===========================================================================================

  @Test
  @DisplayName("Should enforce unique constraint on currencyCode")
  void saveWithDuplicateCurrencyCodeThrowsException() {
    // Arrange: EUR already exists from V6 migration
    // Act & Assert: Try to save duplicate EUR with different provider series ID
    var duplicateEur =
        new CurrencySeriesTestBuilder()
            .withCurrencyCode(TestConstants.VALID_CURRENCY_EUR)
            .withProviderSeriesId("DIFFERENT_SERIES_ID_TEST")
            .build();

    assertThatThrownBy(
            () -> {
              currencySeriesRepository.saveAndFlush(duplicateEur);
            })
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Should enforce unique constraint on providerSeriesId")
  void saveWithDuplicateProviderSeriesIdThrowsException() {
    // Arrange: EUR with providerSeriesId DEXUSEU already exists from V6 migration
    // Act & Assert: Try to save with different currency code but same provider series ID
    var duplicateSeries =
        new CurrencySeriesTestBuilder()
            .withCurrencyCode("XXX")
            .withProviderSeriesId(TestConstants.FRED_SERIES_EUR) // DEXUSEU already exists
            .build();

    assertThatThrownBy(
            () -> {
              currencySeriesRepository.saveAndFlush(duplicateSeries);
            })
        .isInstanceOf(NonTransientDataAccessException.class);
  }

  @Test
  @DisplayName("Should enforce not-null constraint on currencyCode")
  void saveWithNullCurrencyCodeThrowsException() {
    // Arrange
    var seriesWithNullCode =
        new CurrencySeriesTestBuilder()
            .withCurrencyCode(null)
            .withProviderSeriesId("TEST_SERIES")
            .build();

    // Act & Assert
    assertThatThrownBy(
            () -> {
              currencySeriesRepository.saveAndFlush(seriesWithNullCode);
            })
        .isInstanceOf(NonTransientDataAccessException.class);
  }

  @Test
  @DisplayName("Should enforce not-null constraint on providerSeriesId")
  void saveWithNullProviderSeriesIdThrowsException() {
    // Arrange
    var seriesWithNullProvider =
        new CurrencySeriesTestBuilder()
            .withCurrencyCode(TestConstants.VALID_CURRENCY_EUR)
            .withProviderSeriesId(null)
            .build();

    // Act & Assert
    assertThatThrownBy(
            () -> {
              currencySeriesRepository.saveAndFlush(seriesWithNullProvider);
            })
        .isInstanceOf(ConstraintViolationException.class);
  }

  // ===========================================================================================
  // Cascade/Delete Behavior Tests
  // ===========================================================================================

  @Test
  @DisplayName(
      "Should prevent deletion of CurrencySeries when ExchangeRates exist (ON DELETE RESTRICT)")
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
  @DisplayName("Should allow deletion of CurrencySeries when no ExchangeRates exist")
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

  // ===========================================================================================
  // Audit Timestamp Tests
  // ===========================================================================================

  @Test
  @DisplayName("Should auto-populate createdAt timestamp on insert")
  void saveNewEntityPopulatesCreatedAt() {
    // Arrange: Create unique test currency to validate timestamp population
    var testSeries =
        new CurrencySeriesTestBuilder()
            .withCurrencyCode("TS1")
            .withProviderSeriesId("TEST_CREATED_AT_SERIES")
            .build();

    // Act
    var saved = currencySeriesRepository.saveAndFlush(testSeries);

    // Assert
    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("Should auto-populate updatedAt timestamp on insert")
  void saveNewEntityPopulatesUpdatedAt() {
    // Arrange: Create unique test currency to validate timestamp population
    var testSeries =
        new CurrencySeriesTestBuilder()
            .withCurrencyCode("TS2")
            .withProviderSeriesId("TEST_UPDATED_AT_SERIES")
            .build();

    // Act
    var saved = currencySeriesRepository.saveAndFlush(testSeries);

    // Assert
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  @DisplayName("Should update updatedAt timestamp on modification")
  void saveExistingEntityUpdatesUpdatedAt() throws InterruptedException {
    // Arrange: Use existing EUR series from V6 migration
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    final var originalCreatedAt = eurSeries.getCreatedAt();
    var originalUpdatedAt = eurSeries.getUpdatedAt();

    // Wait to ensure timestamp difference
    Thread.sleep(10);

    // Act: Update entity
    eurSeries.setEnabled(!eurSeries.isEnabled());
    var updated = currencySeriesRepository.saveAndFlush(eurSeries);

    // Assert: updatedAt should change, createdAt should remain same
    assertThat(updated.getUpdatedAt()).isAfter(originalUpdatedAt);
    assertThat(updated.getCreatedAt()).isEqualTo(originalCreatedAt);
  }

  // ===========================================================================================
  // Basic CRUD Tests
  // ===========================================================================================

  @Test
  @DisplayName("Should save and retrieve currency series")
  void saveAndRetrieveSuccess() {
    // Arrange: Create unique test currency
    var testSeries =
        new CurrencySeriesTestBuilder()
            .withCurrencyCode("CR1")
            .withProviderSeriesId("TEST_CRUD_SAVE_RETRIEVE")
            .build();

    // Act: Save
    var saved = currencySeriesRepository.saveAndFlush(testSeries);

    // Act: Retrieve
    var retrieved = currencySeriesRepository.findById(saved.getId());

    // Assert
    assertThat(retrieved)
        .isPresent()
        .get()
        .extracting(CurrencySeries::getCurrencyCode)
        .isEqualTo("CR1");
  }

  @Test
  @DisplayName("Should update existing currency series")
  void saveExistingEntityUpdates() {
    // Arrange: Use existing EUR series from V6 migration
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    var originalEnabled = eurSeries.isEnabled();

    // Act: Update enabled status
    eurSeries.setEnabled(!originalEnabled);
    var updated = currencySeriesRepository.saveAndFlush(eurSeries);

    // Assert: Verify update persisted
    var retrieved = currencySeriesRepository.findById(updated.getId());
    assertThat(retrieved)
        .isPresent()
        .get()
        .extracting(CurrencySeries::isEnabled)
        .isEqualTo(!originalEnabled);
  }

  @Test
  @DisplayName("Should delete currency series when no dependencies exist")
  void deleteWithoutDependenciesSuccess() {
    // Arrange: Create unique test currency
    var testSeries =
        new CurrencySeriesTestBuilder()
            .withCurrencyCode("CR2")
            .withProviderSeriesId("TEST_CRUD_DELETE")
            .build();
    var saved = currencySeriesRepository.saveAndFlush(testSeries);
    var savedId = saved.getId();

    // Act
    currencySeriesRepository.delete(saved);
    currencySeriesRepository.flush();

    // Assert
    var found = currencySeriesRepository.findById(savedId);
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("Should handle multiple series with different currency codes")
  void saveMultipleSeriesSuccess() {
    // Arrange: Create unique test currencies
    var testSeries1 =
        new CurrencySeriesTestBuilder()
            .withCurrencyCode("M01")
            .withProviderSeriesId("TEST_MULTI_001")
            .build();
    var testSeries2 =
        new CurrencySeriesTestBuilder()
            .withCurrencyCode("M02")
            .withProviderSeriesId("TEST_MULTI_002")
            .build();
    var testSeries3 =
        new CurrencySeriesTestBuilder()
            .withCurrencyCode("M03")
            .withProviderSeriesId("TEST_MULTI_003")
            .build();

    // Act
    currencySeriesRepository.saveAll(java.util.List.of(testSeries1, testSeries2, testSeries3));
    currencySeriesRepository.flush();

    // Assert
    var allSeries = currencySeriesRepository.findAll();
    assertThat(allSeries)
        .hasSizeGreaterThanOrEqualTo(26) // 23 from V6 migration + 3 test currencies
        .extracting(CurrencySeries::getCurrencyCode)
        .contains("M01", "M02", "M03");
  }
}
