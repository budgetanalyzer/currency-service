package org.budgetanalyzer.currency.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;

import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.domain.ExchangeRate;
import org.budgetanalyzer.currency.repository.spec.ExchangeRateSpecifications;

/**
 * Unit tests for {@link ExchangeRateRepository} using {@code @DataJpaTest} with H2 in-memory
 * database.
 *
 * <p>Tests validate:
 *
 * <ul>
 *   <li>Query method correctness (derived and custom queries)
 *   <li>JPA Specification filtering logic
 *   <li>Basic CRUD operations
 *   <li>Edge cases (empty results, boundary conditions)
 * </ul>
 *
 * <p>These unit tests focus on JPA query logic and run fast (< 1 second per test) using H2. They
 * complement the existing integration tests which validate database constraints, cascade behavior,
 * and PostgreSQL-specific features.
 *
 * <p><b>Test Data Strategy:</b>
 *
 * <p>Each test creates parent {@link CurrencySeries} entities first, then creates {@link
 * ExchangeRate} entities with foreign key references. Tests are self-contained and don't rely on
 * Flyway seed data.
 *
 * @see ExchangeRateRepositoryIntegrationTest for integration tests with PostgreSQL
 */
class ExchangeRateRepositoryTest extends AbstractRepositoryUnitTest {

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  // ===========================================================================================
  // Query Method Tests - findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc
  // ===========================================================================================

  @Test
  void findTopByBaseCurrencyAndTargetCurrencyOrderByDateDescReturnsMostRecent() {
    // Arrange: Create parent currency series
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");

    // Create multiple exchange rates with different dates
    var rate1 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 2), new BigDecimal("0.85"));
    var rate2 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 15), new BigDecimal("0.86"));
    var rate3 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 10), new BigDecimal("0.87"));

    exchangeRateRepository.saveAll(List.of(rate1, rate2, rate3));

    // Act
    var result =
        exchangeRateRepository.findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(
            Currency.getInstance("USD"), Currency.getInstance("EUR"));

    // Assert: Should return January 15 (most recent)
    assertThat(result)
        .isPresent()
        .get()
        .satisfies(
            rate -> {
              assertThat(rate.getDate()).isEqualTo(LocalDate.of(2024, 1, 15));
              assertThat(rate.getRate()).isEqualByComparingTo(new BigDecimal("0.86"));
            });
  }

  @Test
  void findTopByBaseCurrencyAndTargetCurrencyOrderByDateDescNoDataReturnsEmpty() {
    // Act: Query for non-existent currency pair
    var result =
        exchangeRateRepository.findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(
            Currency.getInstance("USD"), Currency.getInstance("EUR"));

    // Assert
    assertThat(result).isEmpty();
  }

  @Test
  void findTopByBaseCurrencyAndTargetCurrencyOrderByDateDescIsolatesByCurrencyPair() {
    // Arrange: Create two currency series
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");
    var gbpSeries = createCurrencySeries("GBP", "DEXUSUK");

    // Create rates for both currencies
    var eurRate = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 15), new BigDecimal("0.86"));
    var gbpRate = createExchangeRate(gbpSeries, LocalDate.of(2024, 1, 20), new BigDecimal("0.78"));

    exchangeRateRepository.saveAll(List.of(eurRate, gbpRate));

    // Act: Query for EUR only
    var result =
        exchangeRateRepository.findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(
            Currency.getInstance("USD"), Currency.getInstance("EUR"));

    // Assert: Should return EUR rate, not GBP rate
    assertThat(result)
        .isPresent()
        .get()
        .satisfies(
            rate -> {
              assertThat(rate.getTargetCurrency()).isEqualTo(Currency.getInstance("EUR"));
              assertThat(rate.getDate()).isEqualTo(LocalDate.of(2024, 1, 15));
            });
  }

  // ===========================================================================================
  // Query Method Tests - findByBaseCurrencyAndTargetCurrencyAndDate
  // ===========================================================================================

  @Test
  void findByBaseCurrencyAndTargetCurrencyAndDateExactMatch() {
    // Arrange: Create currency series and exchange rate
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");
    var rate = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 15), new BigDecimal("0.86"));
    exchangeRateRepository.save(rate);

    // Act
    var result =
        exchangeRateRepository.findByBaseCurrencyAndTargetCurrencyAndDate(
            Currency.getInstance("USD"), Currency.getInstance("EUR"), LocalDate.of(2024, 1, 15));

    // Assert
    assertThat(result)
        .isPresent()
        .get()
        .satisfies(
            foundRate -> {
              assertThat(foundRate.getDate()).isEqualTo(LocalDate.of(2024, 1, 15));
              assertThat(foundRate.getRate()).isEqualByComparingTo(new BigDecimal("0.86"));
            });
  }

  @Test
  void findByBaseCurrencyAndTargetCurrencyAndDateNoMatch() {
    // Arrange: Create currency series and exchange rate
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");
    var rate = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 15), new BigDecimal("0.86"));
    exchangeRateRepository.save(rate);

    // Act: Query for different date
    var result =
        exchangeRateRepository.findByBaseCurrencyAndTargetCurrencyAndDate(
            Currency.getInstance("USD"), Currency.getInstance("EUR"), LocalDate.of(2024, 1, 20));

    // Assert
    assertThat(result).isEmpty();
  }

  @Test
  void findByBaseCurrencyAndTargetCurrencyAndDateIsolatesByPairs() {
    // Arrange: Create two currency series with same date
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");
    var gbpSeries = createCurrencySeries("GBP", "DEXUSUK");

    var eurRate = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 15), new BigDecimal("0.86"));
    var gbpRate = createExchangeRate(gbpSeries, LocalDate.of(2024, 1, 15), new BigDecimal("0.78"));

    exchangeRateRepository.saveAll(List.of(eurRate, gbpRate));

    // Act: Query for EUR specifically
    var result =
        exchangeRateRepository.findByBaseCurrencyAndTargetCurrencyAndDate(
            Currency.getInstance("USD"), Currency.getInstance("EUR"), LocalDate.of(2024, 1, 15));

    // Assert: Should return EUR, not GBP
    assertThat(result)
        .isPresent()
        .get()
        .satisfies(
            rate -> {
              assertThat(rate.getTargetCurrency()).isEqualTo(Currency.getInstance("EUR"));
              assertThat(rate.getRate()).isEqualByComparingTo(new BigDecimal("0.86"));
            });
  }

  // ===========================================================================================
  // Query Method Tests - findTopByTargetCurrencyAndDateLessThanOrderByDateDesc
  // ===========================================================================================

  @Test
  void findTopByTargetCurrencyAndDateLessThanOrderByDateDescReturnsBeforeDate() {
    // Arrange: Create currency series with multiple rates
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");

    var rate1 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 5), new BigDecimal("0.85"));
    var rate2 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 10), new BigDecimal("0.86"));
    var rate3 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 15), new BigDecimal("0.87"));

    exchangeRateRepository.saveAll(List.of(rate1, rate2, rate3));

    // Act: Find rate before January 12
    var result =
        exchangeRateRepository.findTopByTargetCurrencyAndDateLessThanOrderByDateDesc(
            Currency.getInstance("EUR"), LocalDate.of(2024, 1, 12));

    // Assert: Should return January 10 (most recent before January 12)
    assertThat(result)
        .isPresent()
        .get()
        .satisfies(
            rate -> {
              assertThat(rate.getDate()).isEqualTo(LocalDate.of(2024, 1, 10));
              assertThat(rate.getRate()).isEqualByComparingTo(new BigDecimal("0.86"));
            });
  }

  @Test
  void findTopByTargetCurrencyAndDateLessThanOrderByDateDescNoDataBeforeDate() {
    // Arrange: Create currency series with rate on January 15
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");
    var rate = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 15), new BigDecimal("0.86"));
    exchangeRateRepository.save(rate);

    // Act: Find rate before January 10 (none exist)
    var result =
        exchangeRateRepository.findTopByTargetCurrencyAndDateLessThanOrderByDateDesc(
            Currency.getInstance("EUR"), LocalDate.of(2024, 1, 10));

    // Assert
    assertThat(result).isEmpty();
  }

  @Test
  void findTopByTargetCurrencyAndDateLessThanOrderByDateDescBoundaryConditions() {
    // Arrange: Create currency series with rate on January 15
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");
    var rate = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 15), new BigDecimal("0.86"));
    exchangeRateRepository.save(rate);

    // Act: Find rate before January 15 (should not include January 15 itself)
    var result =
        exchangeRateRepository.findTopByTargetCurrencyAndDateLessThanOrderByDateDesc(
            Currency.getInstance("EUR"), LocalDate.of(2024, 1, 15));

    // Assert: Should be empty (lessThan excludes the boundary date)
    assertThat(result).isEmpty();
  }

  // ===========================================================================================
  // Query Method Tests - findEarliestDateByTargetCurrency
  // ===========================================================================================

  @Test
  void findEarliestDateByTargetCurrencyReturnsEarliestDate() {
    // Arrange: Create currency series with multiple dates
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");

    var rate1 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 15), new BigDecimal("0.85"));
    var rate2 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 5), new BigDecimal("0.86"));
    var rate3 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 10), new BigDecimal("0.87"));

    exchangeRateRepository.saveAll(List.of(rate1, rate2, rate3));

    // Act
    var result =
        exchangeRateRepository.findEarliestDateByTargetCurrency(Currency.getInstance("EUR"));

    // Assert: Should return January 5 (earliest)
    assertThat(result).isPresent().contains(LocalDate.of(2024, 1, 5));
  }

  @Test
  void findEarliestDateByTargetCurrencyNoDataReturnsEmpty() {
    // Act: Query for non-existent currency
    var result =
        exchangeRateRepository.findEarliestDateByTargetCurrency(Currency.getInstance("EUR"));

    // Assert
    assertThat(result).isEmpty();
  }

  @Test
  void findEarliestDateByTargetCurrencyIsolatesByTargetCurrency() {
    // Arrange: Create two currency series with different earliest dates
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");
    var gbpSeries = createCurrencySeries("GBP", "DEXUSUK");

    var eurRate = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 10), new BigDecimal("0.85"));
    var gbpRate = createExchangeRate(gbpSeries, LocalDate.of(2024, 1, 5), new BigDecimal("0.78"));

    exchangeRateRepository.saveAll(List.of(eurRate, gbpRate));

    // Act: Query for EUR
    var result =
        exchangeRateRepository.findEarliestDateByTargetCurrency(Currency.getInstance("EUR"));

    // Assert: Should return EUR's date (Jan 10), not GBP's date (Jan 5)
    assertThat(result).isPresent().contains(LocalDate.of(2024, 1, 10));
  }

  // ===========================================================================================
  // Query Method Tests - countByCurrencySeries
  // ===========================================================================================

  @Test
  void countByCurrencySeriesNewSeriesReturnsZero() {
    // Arrange: Create currency series without exchange rates
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");

    // Act
    var count = exchangeRateRepository.countByCurrencySeries(eurSeries);

    // Assert
    assertThat(count).isZero();
  }

  @Test
  void countByCurrencySeriesWithRatesReturnsCorrectCount() {
    // Arrange: Create currency series with multiple rates
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");

    var rate1 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 2), new BigDecimal("0.85"));
    var rate2 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 5), new BigDecimal("0.86"));
    var rate3 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 10), new BigDecimal("0.87"));

    exchangeRateRepository.saveAll(List.of(rate1, rate2, rate3));

    // Act
    var count = exchangeRateRepository.countByCurrencySeries(eurSeries);

    // Assert
    assertThat(count).isEqualTo(3);
  }

  @Test
  void countByCurrencySeriesIsolatesBySeries() {
    // Arrange: Create two currency series with different counts
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");
    var gbpSeries = createCurrencySeries("GBP", "DEXUSUK");

    // Create 2 EUR rates and 3 GBP rates
    var eurRate1 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 2), new BigDecimal("0.85"));
    var eurRate2 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 5), new BigDecimal("0.86"));

    var gbpRate1 = createExchangeRate(gbpSeries, LocalDate.of(2024, 1, 2), new BigDecimal("0.78"));
    var gbpRate2 = createExchangeRate(gbpSeries, LocalDate.of(2024, 1, 5), new BigDecimal("0.79"));
    var gbpRate3 = createExchangeRate(gbpSeries, LocalDate.of(2024, 1, 10), new BigDecimal("0.80"));

    exchangeRateRepository.saveAll(List.of(eurRate1, eurRate2, gbpRate1, gbpRate2, gbpRate3));

    // Act
    var eurCount = exchangeRateRepository.countByCurrencySeries(eurSeries);
    var gbpCount = exchangeRateRepository.countByCurrencySeries(gbpSeries);

    // Assert
    assertThat(eurCount).isEqualTo(2);
    assertThat(gbpCount).isEqualTo(3);
  }

  // ===========================================================================================
  // JpaSpecificationExecutor Tests
  // ===========================================================================================

  @Test
  void jpaSpecificationExecutorFilterByDateRange() {
    // Arrange: Create currency series with multiple dates
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");

    var rate1 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 2), new BigDecimal("0.85"));
    var rate2 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 10), new BigDecimal("0.86"));
    var rate3 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 20), new BigDecimal("0.87"));

    exchangeRateRepository.saveAll(List.of(rate1, rate2, rate3));

    // Act: Filter for rates between Jan 5 and Jan 15
    Specification<ExchangeRate> spec =
        ExchangeRateSpecifications.dateGreaterThanOrEqual(LocalDate.of(2024, 1, 5))
            .and(ExchangeRateSpecifications.dateLessThanOrEqual(LocalDate.of(2024, 1, 15)));

    var result = exchangeRateRepository.findAll(spec);

    // Assert: Should return only January 10
    assertThat(result)
        .hasSize(1)
        .extracting(ExchangeRate::getDate)
        .containsExactly(LocalDate.of(2024, 1, 10));
  }

  @Test
  void jpaSpecificationExecutorFilterByMultipleTargetCurrencies() {
    // Arrange: Create three currency series
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");
    var gbpSeries = createCurrencySeries("GBP", "DEXUSUK");
    var jpySeries = createCurrencySeries("JPY", "DEXJPUS");

    var eurRate = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 10), new BigDecimal("0.85"));
    var gbpRate = createExchangeRate(gbpSeries, LocalDate.of(2024, 1, 10), new BigDecimal("0.78"));
    var jpyRate =
        createExchangeRate(jpySeries, LocalDate.of(2024, 1, 10), new BigDecimal("140.50"));

    exchangeRateRepository.saveAll(List.of(eurRate, gbpRate, jpyRate));

    // Act: Filter for EUR only
    Specification<ExchangeRate> spec =
        ExchangeRateSpecifications.hasTargetCurrency(Currency.getInstance("EUR"));

    var result = exchangeRateRepository.findAll(spec);

    // Assert: Should return only EUR rate
    assertThat(result)
        .hasSize(1)
        .extracting(ExchangeRate::getTargetCurrency)
        .containsExactly(Currency.getInstance("EUR"));
  }

  @Test
  void jpaSpecificationExecutorCombineMultipleFilters() {
    // Arrange: Create two currency series with multiple dates
    var eurSeries = createCurrencySeries("EUR", "DEXUSEU");
    var gbpSeries = createCurrencySeries("GBP", "DEXUSUK");

    var eurRate1 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 5), new BigDecimal("0.85"));
    var eurRate2 = createExchangeRate(eurSeries, LocalDate.of(2024, 1, 15), new BigDecimal("0.86"));
    var gbpRate1 = createExchangeRate(gbpSeries, LocalDate.of(2024, 1, 10), new BigDecimal("0.78"));

    exchangeRateRepository.saveAll(List.of(eurRate1, eurRate2, gbpRate1));

    // Act: Filter for EUR currency AND date >= Jan 10
    Specification<ExchangeRate> spec =
        ExchangeRateSpecifications.hasTargetCurrency(Currency.getInstance("EUR"))
            .and(ExchangeRateSpecifications.dateGreaterThanOrEqual(LocalDate.of(2024, 1, 10)));

    var result = exchangeRateRepository.findAll(spec);

    // Assert: Should return only EUR rate from January 15
    assertThat(result)
        .hasSize(1)
        .first()
        .satisfies(
            rate -> {
              assertThat(rate.getTargetCurrency()).isEqualTo(Currency.getInstance("EUR"));
              assertThat(rate.getDate()).isEqualTo(LocalDate.of(2024, 1, 15));
            });
  }

  // ===========================================================================================
  // Helper Methods
  // ===========================================================================================

  /**
   * Creates and saves a currency series.
   *
   * @param currencyCode the ISO 4217 currency code
   * @param providerSeriesId the FRED series ID
   * @return the saved currency series
   */
  private CurrencySeries createCurrencySeries(String currencyCode, String providerSeriesId) {
    var series = new CurrencySeries();
    series.setCurrencyCode(currencyCode);
    series.setProviderSeriesId(providerSeriesId);
    series.setEnabled(true);
    return currencySeriesRepository.save(series);
  }

  /**
   * Creates an exchange rate (not saved).
   *
   * @param currencySeries the parent currency series
   * @param date the exchange rate date
   * @param rate the exchange rate value
   * @return the unsaved exchange rate
   */
  private ExchangeRate createExchangeRate(
      CurrencySeries currencySeries, LocalDate date, BigDecimal rate) {
    var exchangeRate = new ExchangeRate();
    exchangeRate.setCurrencySeries(currencySeries);
    exchangeRate.setBaseCurrency(Currency.getInstance("USD"));
    exchangeRate.setTargetCurrency(Currency.getInstance(currencySeries.getCurrencyCode()));
    exchangeRate.setDate(date);
    exchangeRate.setRate(rate);
    return exchangeRate;
  }
}
