package org.budgetanalyzer.currency.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.budgetanalyzer.currency.domain.CurrencySeries;

/**
 * Unit tests for {@link CurrencySeriesRepository} using {@code @DataJpaTest} with H2 in-memory
 * database.
 *
 * <p>Tests validate:
 *
 * <ul>
 *   <li>Query method correctness (returns right data for given parameters)
 *   <li>Basic CRUD operations
 *   <li>Edge cases (empty results, case sensitivity)
 * </ul>
 *
 * <p>These unit tests focus on JPA query logic and run fast (< 1 second per test) using H2. They
 * complement the existing integration tests which validate database constraints, cascade behavior,
 * and PostgreSQL-specific features.
 *
 * <p><b>Test Data Strategy:</b>
 *
 * <p>Each test creates its own {@link CurrencySeries} entities inline without relying on Flyway
 * seed data. This ensures tests are self-contained and independent.
 *
 * @see CurrencySeriesRepositoryIntegrationTest for integration tests with PostgreSQL
 */
class CurrencySeriesRepositoryTest extends AbstractRepositoryUnitTest {

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  // ===========================================================================================
  // Query Method Tests - findByEnabledTrue()
  // ===========================================================================================

  @Test
  void findByEnabledTrueWithMixedDataReturnsOnlyEnabled() {
    // Arrange: Create mixed enabled/disabled test data
    var enabled1 = new CurrencySeries();
    enabled1.setCurrencyCode("EUR");
    enabled1.setProviderSeriesId("DEXUSEU");
    enabled1.setEnabled(true);

    var enabled2 = new CurrencySeries();
    enabled2.setCurrencyCode("GBP");
    enabled2.setProviderSeriesId("DEXUSUK");
    enabled2.setEnabled(true);

    var disabled1 = new CurrencySeries();
    disabled1.setCurrencyCode("THB");
    disabled1.setProviderSeriesId("DEXTHUS");
    disabled1.setEnabled(false);

    var disabled2 = new CurrencySeries();
    disabled2.setCurrencyCode("JPY");
    disabled2.setProviderSeriesId("DEXJPUS");
    disabled2.setEnabled(false);

    currencySeriesRepository.saveAll(List.of(enabled1, enabled2, disabled1, disabled2));

    // Act
    var result = currencySeriesRepository.findByEnabledTrue();

    // Assert
    assertThat(result)
        .hasSize(2)
        .extracting(CurrencySeries::getCurrencyCode)
        .containsExactlyInAnyOrder("EUR", "GBP");
  }

  @Test
  void findByEnabledTrueAllDisabledReturnsEmpty() {
    // Arrange: Create only disabled series
    var disabled1 = new CurrencySeries();
    disabled1.setCurrencyCode("THB");
    disabled1.setProviderSeriesId("DEXTHUS");
    disabled1.setEnabled(false);

    var disabled2 = new CurrencySeries();
    disabled2.setCurrencyCode("JPY");
    disabled2.setProviderSeriesId("DEXJPUS");
    disabled2.setEnabled(false);

    currencySeriesRepository.saveAll(List.of(disabled1, disabled2));

    // Act
    var result = currencySeriesRepository.findByEnabledTrue();

    // Assert
    assertThat(result).isEmpty();
  }

  @Test
  void findByEnabledTrueAllEnabledReturnsAll() {
    // Arrange: Create only enabled series
    var enabled1 = new CurrencySeries();
    enabled1.setCurrencyCode("EUR");
    enabled1.setProviderSeriesId("DEXUSEU");
    enabled1.setEnabled(true);

    var enabled2 = new CurrencySeries();
    enabled2.setCurrencyCode("GBP");
    enabled2.setProviderSeriesId("DEXUSUK");
    enabled2.setEnabled(true);

    var enabled3 = new CurrencySeries();
    enabled3.setCurrencyCode("JPY");
    enabled3.setProviderSeriesId("DEXJPUS");
    enabled3.setEnabled(true);

    currencySeriesRepository.saveAll(List.of(enabled1, enabled2, enabled3));

    // Act
    var result = currencySeriesRepository.findByEnabledTrue();

    // Assert
    assertThat(result)
        .hasSize(3)
        .extracting(CurrencySeries::getCurrencyCode)
        .containsExactlyInAnyOrder("EUR", "GBP", "JPY");
  }

  // ===========================================================================================
  // Query Method Tests - findByCurrencyCodeAndEnabledTrue()
  // ===========================================================================================

  @Test
  void findByCurrencyCodeAndEnabledTrueEnabledCurrencyFound() {
    // Arrange: Create enabled EUR series
    var eurSeries = new CurrencySeries();
    eurSeries.setCurrencyCode("EUR");
    eurSeries.setProviderSeriesId("DEXUSEU");
    eurSeries.setEnabled(true);
    currencySeriesRepository.save(eurSeries);

    // Act
    var result = currencySeriesRepository.findByCurrencyCodeAndEnabledTrue("EUR");

    // Assert
    assertThat(result)
        .isPresent()
        .get()
        .extracting(CurrencySeries::getCurrencyCode)
        .isEqualTo("EUR");
  }

  @Test
  void findByCurrencyCodeAndEnabledTrueDisabledCurrencyReturnsEmpty() {
    // Arrange: Create disabled EUR series
    var eurSeries = new CurrencySeries();
    eurSeries.setCurrencyCode("EUR");
    eurSeries.setProviderSeriesId("DEXUSEU");
    eurSeries.setEnabled(false);
    currencySeriesRepository.save(eurSeries);

    // Act
    var result = currencySeriesRepository.findByCurrencyCodeAndEnabledTrue("EUR");

    // Assert
    assertThat(result).isEmpty();
  }

  @Test
  void findByCurrencyCodeAndEnabledTrueNonExistentReturnsEmpty() {
    // Act: Query for non-existent currency
    var result = currencySeriesRepository.findByCurrencyCodeAndEnabledTrue("XXX");

    // Assert
    assertThat(result).isEmpty();
  }

  @Test
  void findByCurrencyCodeAndEnabledTrueIsCaseSensitive() {
    // Arrange: Create enabled EUR series (uppercase)
    var eurSeries = new CurrencySeries();
    eurSeries.setCurrencyCode("EUR");
    eurSeries.setProviderSeriesId("DEXUSEU");
    eurSeries.setEnabled(true);
    currencySeriesRepository.save(eurSeries);

    // Act: Query with lowercase
    var result = currencySeriesRepository.findByCurrencyCodeAndEnabledTrue("eur");

    // Assert: Should not find uppercase EUR
    assertThat(result).isEmpty();
  }

  // ===========================================================================================
  // Query Method Tests - findByCurrencyCode()
  // ===========================================================================================

  @Test
  void findByCurrencyCodeRegardlessOfEnabledStatus() {
    // Arrange: Create disabled EUR series
    var eurSeries = new CurrencySeries();
    eurSeries.setCurrencyCode("EUR");
    eurSeries.setProviderSeriesId("DEXUSEU");
    eurSeries.setEnabled(false);
    currencySeriesRepository.save(eurSeries);

    // Act
    var result = currencySeriesRepository.findByCurrencyCode("EUR");

    // Assert: Should find disabled currency
    assertThat(result)
        .isPresent()
        .get()
        .satisfies(
            series -> {
              assertThat(series.getCurrencyCode()).isEqualTo("EUR");
              assertThat(series.isEnabled()).isFalse();
            });
  }

  @Test
  void findByCurrencyCodeNonExistentReturnsEmpty() {
    // Act: Query for non-existent currency
    var result = currencySeriesRepository.findByCurrencyCode("ZZZ");

    // Assert
    assertThat(result).isEmpty();
  }

  // ===========================================================================================
  // Basic CRUD Tests
  // ===========================================================================================

  @Test
  void saveAndRetrieve() {
    // Arrange: Create new currency series
    var newSeries = new CurrencySeries();
    newSeries.setCurrencyCode("CAD");
    newSeries.setProviderSeriesId("DEXCAUS");
    newSeries.setEnabled(true);

    // Act: Save
    var saved = currencySeriesRepository.save(newSeries);

    // Assert: Verify ID was generated
    assertThat(saved.getId()).isNotNull();

    // Act: Retrieve
    var retrieved = currencySeriesRepository.findById(saved.getId());

    // Assert: Verify retrieval
    assertThat(retrieved)
        .isPresent()
        .get()
        .satisfies(
            series -> {
              assertThat(series.getCurrencyCode()).isEqualTo("CAD");
              assertThat(series.getProviderSeriesId()).isEqualTo("DEXCAUS");
              assertThat(series.isEnabled()).isTrue();
              assertThat(series.getCreatedAt()).isNotNull();
              assertThat(series.getUpdatedAt()).isNotNull();
            });
  }

  @Test
  void updateExisting() {
    // Arrange: Create and save currency series
    var series = new CurrencySeries();
    series.setCurrencyCode("EUR");
    series.setProviderSeriesId("DEXUSEU");
    series.setEnabled(true);
    var saved = currencySeriesRepository.save(series);

    // Act: Update enabled status
    saved.setEnabled(false);
    var updated = currencySeriesRepository.save(saved);

    // Assert: Verify update
    var retrieved = currencySeriesRepository.findById(updated.getId());
    assertThat(retrieved).isPresent().get().extracting(CurrencySeries::isEnabled).isEqualTo(false);
  }

  @Test
  void delete() {
    // Arrange: Create and save currency series
    var series = new CurrencySeries();
    series.setCurrencyCode("THB");
    series.setProviderSeriesId("DEXTHUS");
    series.setEnabled(true);
    var saved = currencySeriesRepository.save(series);
    var savedId = saved.getId();

    // Act: Delete
    currencySeriesRepository.delete(saved);

    // Assert: Verify deletion
    var retrieved = currencySeriesRepository.findById(savedId);
    assertThat(retrieved).isEmpty();
  }
}
