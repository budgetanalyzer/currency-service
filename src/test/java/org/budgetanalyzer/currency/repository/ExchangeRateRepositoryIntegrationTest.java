package org.budgetanalyzer.currency.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;

import org.budgetanalyzer.currency.base.AbstractIntegrationTest;
import org.budgetanalyzer.currency.domain.ExchangeRate;
import org.budgetanalyzer.currency.fixture.ExchangeRateTestBuilder;
import org.budgetanalyzer.currency.fixture.TestConstants;

/**
 * Integration tests for {@link ExchangeRateRepository}.
 *
 * <p>Tests validate:
 *
 * <ul>
 *   <li>Query methods (derived and custom queries)
 *   <li>Database constraints (unique, not-null, foreign keys)
 *   <li>Foreign key relationships and cascade behavior
 *   <li>JpaSpecificationExecutor with complex filters
 *   <li>Audit timestamp population
 *   <li>Basic CRUD operations
 * </ul>
 *
 * <p>Tests run against real PostgreSQL via TestContainers with automatic transaction rollback for
 * isolation.
 */
class ExchangeRateRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  // ===========================================================================================
  // Query Method Tests - findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc()
  // ===========================================================================================

  @Test
  void findTopByBaseCurrencyAndTargetCurrencyOrderByDateDescReturnsMostRecent() {
    // Arrange: Use existing EUR series from V6 migration and create exchange rates
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate1 =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();
    var rate2 =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 15))
            .withRate(new BigDecimal("0.8600"))
            .build();
    var rate3 =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 10))
            .withRate(new BigDecimal("0.8550"))
            .build();

    exchangeRateRepository.saveAll(java.util.List.of(rate1, rate2, rate3));
    exchangeRateRepository.flush();

    // Act
    var mostRecent =
        exchangeRateRepository.findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(
            TestConstants.BASE_CURRENCY_USD, TestConstants.CURRENCY_EUR);

    // Assert: Should return rate from 2024-01-15 (most recent)
    assertThat(mostRecent)
        .isPresent()
        .get()
        .satisfies(
            rate -> {
              assertThat(rate.getDate()).isEqualTo(LocalDate.of(2024, 1, 15));
              assertThat(rate.getRate()).isEqualByComparingTo(new BigDecimal("0.8600"));
            });
  }

  @Test
  void findTopByBaseCurrencyAndTargetCurrencyOrderByDateDescWithNoDataReturnsEmpty() {
    // Act: Query for currency pair with no data
    var result =
        exchangeRateRepository.findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(
            TestConstants.BASE_CURRENCY_USD, TestConstants.CURRENCY_ZAR_NOT_IN_DB);

    // Assert
    assertThat(result).isEmpty();
  }

  @Test
  void findTopByBaseCurrencyAndTargetCurrencyOrderByDateDescIsolatesByCurrencyPair() {
    // Arrange: Create EUR and THB series with different rates
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    var thbSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_THB).orElseThrow();

    var eurRate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 15))
            .withRate(new BigDecimal("0.8500"))
            .build();
    var thbRate =
        ExchangeRateTestBuilder.forSeries(thbSeries)
            .withDate(LocalDate.of(2024, 1, 20)) // More recent than EUR
            .withRate(new BigDecimal("32.50"))
            .build();

    exchangeRateRepository.saveAll(java.util.List.of(eurRate, thbRate));
    exchangeRateRepository.flush();

    // Act: Query for EUR only
    var eurResult =
        exchangeRateRepository.findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(
            TestConstants.BASE_CURRENCY_USD, TestConstants.CURRENCY_EUR);

    // Assert: Should return EUR rate, not THB (even though THB is more recent)
    assertThat(eurResult)
        .isPresent()
        .get()
        .satisfies(
            rate -> {
              assertThat(rate.getTargetCurrency().getCurrencyCode())
                  .isEqualTo(TestConstants.VALID_CURRENCY_EUR);
              assertThat(rate.getDate()).isEqualTo(LocalDate.of(2024, 1, 15));
            });
  }

  // ===========================================================================================
  // Query Method Tests - findEarliestDateByTargetCurrency()
  // ===========================================================================================

  @Test
  void findEarliestDateByTargetCurrencyReturnsEarliestDate() {
    // Arrange: Create EUR rates with various dates
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            LocalDate.of(2024, 1, 2),
            LocalDate.of(2024, 1, 10),
            new BigDecimal("0.8500"));

    exchangeRateRepository.saveAll(rates);
    exchangeRateRepository.flush();

    // Act
    var earliestDate =
        exchangeRateRepository.findEarliestDateByTargetCurrency(TestConstants.CURRENCY_EUR);

    // Assert: Should return 2024-01-02 (earliest date)
    assertThat(earliestDate).isPresent().contains(LocalDate.of(2024, 1, 2));
  }

  @Test
  void findEarliestDateByTargetCurrencyWithNoDataReturnsEmpty() {
    // Act: Query for currency with no exchange rates
    var result =
        exchangeRateRepository.findEarliestDateByTargetCurrency(
            TestConstants.CURRENCY_ZAR_NOT_IN_DB);

    // Assert
    assertThat(result).isEmpty();
  }

  @Test
  void findEarliestDateByTargetCurrencyReturnsGlobalEarliestAcrossSeries() {
    // Arrange: Create two EUR series with different start dates
    // Note: In reality, there should only be one series per currency, but this tests the query
    var eurSeries1 =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate1 =
        ExchangeRateTestBuilder.forSeries(eurSeries1)
            .withDate(LocalDate.of(2020, 1, 1))
            .withRate(new BigDecimal("0.8500"))
            .build();
    var rate2 =
        ExchangeRateTestBuilder.forSeries(eurSeries1)
            .withDate(LocalDate.of(2024, 1, 1))
            .withRate(new BigDecimal("0.8600"))
            .build();

    exchangeRateRepository.saveAll(java.util.List.of(rate1, rate2));
    exchangeRateRepository.flush();

    // Act
    var earliestDate =
        exchangeRateRepository.findEarliestDateByTargetCurrency(TestConstants.CURRENCY_EUR);

    // Assert: Should return 2020-01-01 (global earliest)
    assertThat(earliestDate).isPresent().contains(LocalDate.of(2020, 1, 1));
  }

  @Test
  void findEarliestDateByTargetCurrencyIsolatesByTargetCurrency() {
    // Arrange: Create EUR and THB rates with different start dates
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    var thbSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_THB).orElseThrow();

    var eurRate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 15))
            .withRate(new BigDecimal("0.8500"))
            .build();
    var thbRate =
        ExchangeRateTestBuilder.forSeries(thbSeries)
            .withDate(LocalDate.of(2020, 1, 1)) // Earlier than EUR
            .withRate(new BigDecimal("32.50"))
            .build();

    exchangeRateRepository.saveAll(java.util.List.of(eurRate, thbRate));
    exchangeRateRepository.flush();

    // Act: Query for EUR only
    var eurEarliest =
        exchangeRateRepository.findEarliestDateByTargetCurrency(TestConstants.CURRENCY_EUR);

    // Assert: Should return EUR date, not THB (even though THB is earlier)
    assertThat(eurEarliest).isPresent().contains(LocalDate.of(2024, 1, 15));
  }

  // ===========================================================================================
  // Query Method Tests - countByCurrencySeries()
  // ===========================================================================================

  @Test
  void countByCurrencySeriesWithNoRatesReturnsZero() {
    // Arrange: Use existing EUR series from V6 migration (no rates initially)
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    // Act
    var count = exchangeRateRepository.countByCurrencySeries(eurSeries);

    // Assert
    assertThat(count).isZero();
  }

  @Test
  void countByCurrencySeriesWithSingleRateReturnsOne() {
    // Arrange: Create EUR series with one rate
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();
    exchangeRateRepository.saveAndFlush(rate);

    // Act
    var count = exchangeRateRepository.countByCurrencySeries(eurSeries);

    // Assert
    assertThat(count).isEqualTo(1);
  }

  @Test
  void countByCurrencySeriesWithMultipleRatesReturnsCount() {
    // Arrange: Create EUR series with multiple rates
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            LocalDate.of(2024, 1, 2),
            LocalDate.of(2024, 1, 10),
            new BigDecimal("0.8500"));

    exchangeRateRepository.saveAll(rates);
    exchangeRateRepository.flush();

    // Act
    var count = exchangeRateRepository.countByCurrencySeries(eurSeries);

    // Assert: 9 days from Jan 2 to Jan 10 inclusive
    assertThat(count).isEqualTo(9);
  }

  @Test
  void countByCurrencySeriesIsolatesBySeries() {
    // Arrange: Create EUR and THB series with different number of rates
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    var thbSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_THB).orElseThrow();

    var eurRates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 5), new BigDecimal("0.85"));
    var thbRates =
        ExchangeRateTestBuilder.buildDateRange(
            thbSeries,
            LocalDate.of(2024, 1, 2),
            LocalDate.of(2024, 1, 10),
            new BigDecimal("32.50"));

    exchangeRateRepository.saveAll(eurRates);
    exchangeRateRepository.saveAll(thbRates);
    exchangeRateRepository.flush();

    // Act
    var eurCount = exchangeRateRepository.countByCurrencySeries(eurSeries);
    var thbCount = exchangeRateRepository.countByCurrencySeries(thbSeries);

    // Assert: EUR has 4 rates, THB has 9 rates
    assertThat(eurCount).isEqualTo(4);
    assertThat(thbCount).isEqualTo(9);
  }

  // ===========================================================================================
  // JpaSpecificationExecutor Tests - Complex Filters
  // ===========================================================================================

  @Test
  void specificationFiltersByDateRange() {
    // Arrange: Create EUR rates across a wide date range
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 31),
            new BigDecimal("0.8500"));

    exchangeRateRepository.saveAll(rates);
    exchangeRateRepository.flush();

    // Act: Filter for Jan 10-15 only
    Specification<ExchangeRate> spec =
        (root, query, cb) ->
            cb.between(root.get("date"), LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 15));

    var filtered = exchangeRateRepository.findAll(spec);

    // Assert: Should return 6 rates (Jan 10-15 inclusive)
    assertThat(filtered)
        .hasSize(6)
        .allSatisfy(
            rate -> {
              assertThat(rate.getDate())
                  .isBetween(LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 15));
            });
  }

  @Test
  void specificationFiltersByMultipleCurrencies() {
    // Arrange: Create EUR, THB, and GBP rates
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    var thbSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_THB).orElseThrow();
    var gbpSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_GBP).orElseThrow();

    var eurRate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.85"))
            .build();
    var thbRate =
        ExchangeRateTestBuilder.forSeries(thbSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("32.50"))
            .build();
    var gbpRate =
        ExchangeRateTestBuilder.forSeries(gbpSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.75"))
            .build();

    exchangeRateRepository.saveAll(java.util.List.of(eurRate, thbRate, gbpRate));
    exchangeRateRepository.flush();

    // Act: Filter for EUR and THB only
    Specification<ExchangeRate> spec =
        (root, query, cb) ->
            root.get("targetCurrency")
                .in(TestConstants.VALID_CURRENCY_EUR, TestConstants.VALID_CURRENCY_THB);

    var filtered = exchangeRateRepository.findAll(spec);

    // Assert: Should return EUR and THB rates, not GBP
    assertThat(filtered)
        .hasSize(2)
        .extracting(ExchangeRate::getTargetCurrency)
        .containsExactlyInAnyOrder(TestConstants.CURRENCY_EUR, TestConstants.CURRENCY_THB);
  }

  @Test
  void specificationFiltersByRateThreshold() {
    // Arrange: Create EUR rates with varying values
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate1 =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 1))
            .withRate(new BigDecimal("0.8000"))
            .build();
    var rate2 =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();
    var rate3 =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 3))
            .withRate(new BigDecimal("0.9000"))
            .build();

    exchangeRateRepository.saveAll(java.util.List.of(rate1, rate2, rate3));
    exchangeRateRepository.flush();

    // Act: Filter for rates >= 0.85
    Specification<ExchangeRate> spec =
        (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("rate"), new BigDecimal("0.8500"));

    var filtered = exchangeRateRepository.findAll(spec);

    // Assert: Should return 2 rates (0.85 and 0.90)
    assertThat(filtered)
        .hasSize(2)
        .allSatisfy(
            rate -> {
              assertThat(rate.getRate()).isGreaterThanOrEqualTo(new BigDecimal("0.8500"));
            });
  }

  @Test
  void specificationCombinesMultipleFilters() {
    // Arrange: Create multiple currencies and dates
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    var thbSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_THB).orElseThrow();

    var eurRates =
        ExchangeRateTestBuilder.buildDateRangeWithIncrement(
            eurSeries,
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 10),
            new BigDecimal("0.8000"),
            new BigDecimal("0.01"));
    var thbRates =
        ExchangeRateTestBuilder.buildDateRange(
            thbSeries,
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 10),
            new BigDecimal("32.50"));

    exchangeRateRepository.saveAll(eurRates);
    exchangeRateRepository.saveAll(thbRates);
    exchangeRateRepository.flush();

    // Act: Filter for EUR rates between Jan 5-10 with rate >= 0.84
    Specification<ExchangeRate> spec =
        (root, query, cb) ->
            cb.and(
                cb.equal(root.get("targetCurrency"), TestConstants.VALID_CURRENCY_EUR),
                cb.between(root.get("date"), LocalDate.of(2024, 1, 5), LocalDate.of(2024, 1, 10)),
                cb.greaterThanOrEqualTo(root.get("rate"), new BigDecimal("0.8400")));

    var filtered = exchangeRateRepository.findAll(spec);

    // Assert: EUR rates on Jan 5-10 with rate >= 0.84 (Jan 5: 0.84, Jan 6: 0.85, ..., Jan 10:
    // 0.89)
    assertThat(filtered)
        .hasSize(6)
        .allSatisfy(
            rate -> {
              assertThat(rate.getTargetCurrency().getCurrencyCode())
                  .isEqualTo(TestConstants.VALID_CURRENCY_EUR);
              assertThat(rate.getDate())
                  .isBetween(LocalDate.of(2024, 1, 5), LocalDate.of(2024, 1, 10));
              assertThat(rate.getRate()).isGreaterThanOrEqualTo(new BigDecimal("0.8400"));
            });
  }

  // ===========================================================================================
  // Database Constraint Tests
  // ===========================================================================================

  @Test
  void saveWithDuplicateCurrencyPairAndDateThrowsException() {
    // Arrange: Create initial EUR rate
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate1 =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();
    exchangeRateRepository.saveAndFlush(rate1);

    // Act & Assert: Try to save duplicate with same base, target, and date
    var duplicate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.9999"))
            .build();

    assertThatThrownBy(
            () -> {
              exchangeRateRepository.saveAndFlush(duplicate);
            })
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void saveWithDuplicateCurrencyPairButDifferentDateSucceeds() {
    // Arrange: Create EUR rate on Jan 2
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate1 =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();
    exchangeRateRepository.saveAndFlush(rate1);

    // Act: Save same currency pair with different date (Jan 3)
    var rate2 =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 3))
            .withRate(new BigDecimal("0.8600"))
            .build();
    var saved = exchangeRateRepository.saveAndFlush(rate2);

    // Assert
    assertThat(saved.getId()).isNotNull();
  }

  @Test
  void saveWithDuplicateDateButDifferentCurrencyPairSucceeds() {
    // Arrange: Create EUR rate on Jan 2
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    var thbSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_THB).orElseThrow();

    var eurRate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();
    exchangeRateRepository.saveAndFlush(eurRate);

    // Act: Save different currency pair with same date
    var thbRate =
        ExchangeRateTestBuilder.forSeries(thbSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("32.50"))
            .build();
    var saved = exchangeRateRepository.saveAndFlush(thbRate);

    // Assert
    assertThat(saved.getId()).isNotNull();
  }

  // ===========================================================================================
  // Foreign Key Relationship Tests
  // ===========================================================================================

  @Test
  void saveWithoutCurrencySeriesThrowsException() {
    // Arrange: Create exchange rate without setting currencySeries
    var rate = new ExchangeRate();
    rate.setBaseCurrency(TestConstants.BASE_CURRENCY_USD);
    rate.setTargetCurrency(TestConstants.CURRENCY_EUR);
    rate.setDate(LocalDate.of(2024, 1, 2));
    rate.setRate(new BigDecimal("0.8500"));

    // Act & Assert: Bean validation happens before DB constraint, so expect
    // ConstraintViolationException
    assertThatThrownBy(
            () -> {
              exchangeRateRepository.saveAndFlush(rate);
            })
        .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
  }

  @Test
  void deleteSeriesWithExistingRatesThrowsException() {
    // Arrange: Use existing EUR series from V6 migration and add exchange rate
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();
    exchangeRateRepository.saveAndFlush(rate);

    // Act & Assert: Try to delete series with related exchange rates
    assertThatThrownBy(
            () -> {
              currencySeriesRepository.delete(eurSeries);
              currencySeriesRepository.flush();
            })
        .isInstanceOf(org.springframework.dao.NonTransientDataAccessException.class);
  }

  @Test
  void deleteExchangeRateDoesNotAffectCurrencySeries() {
    // Arrange: Create EUR series with exchange rate
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();
    var savedRate = exchangeRateRepository.saveAndFlush(rate);

    // Act: Delete exchange rate
    exchangeRateRepository.delete(savedRate);
    exchangeRateRepository.flush();

    // Assert: CurrencySeries should still exist
    var series = currencySeriesRepository.findById(eurSeries.getId());
    assertThat(series).isPresent();
  }

  // ===========================================================================================
  // Audit Timestamp Tests
  // ===========================================================================================

  @Test
  void saveNewEntityPopulatesCreatedAt() {
    // Arrange: Create EUR rate
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();

    // Act
    var saved = exchangeRateRepository.saveAndFlush(rate);

    // Assert
    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  void saveNewEntityPopulatesUpdatedAt() {
    // Arrange: Create EUR rate
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();

    // Act
    var saved = exchangeRateRepository.saveAndFlush(rate);

    // Assert
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  void saveExistingEntityUpdatesUpdatedAt() throws InterruptedException {
    // Arrange: Create and save EUR rate
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();
    var saved = exchangeRateRepository.saveAndFlush(rate);

    final var originalCreatedAt = saved.getCreatedAt();
    var originalUpdatedAt = saved.getUpdatedAt();

    // Wait to ensure timestamp difference
    Thread.sleep(10);

    // Act: Update entity
    saved.setRate(new BigDecimal("0.8600"));
    var updated = exchangeRateRepository.saveAndFlush(saved);

    // Assert: updatedAt should change, createdAt should remain same
    assertThat(updated.getUpdatedAt()).isAfter(originalUpdatedAt);
    assertThat(updated.getCreatedAt()).isEqualTo(originalCreatedAt);
  }

  // ===========================================================================================
  // Basic CRUD Tests
  // ===========================================================================================

  @Test
  void saveAndRetrieveSuccess() {
    // Arrange: Create EUR rate
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();

    // Act: Save
    var saved = exchangeRateRepository.saveAndFlush(rate);

    // Act: Retrieve
    var retrieved = exchangeRateRepository.findById(saved.getId());

    // Assert
    assertThat(retrieved)
        .isPresent()
        .get()
        .satisfies(
            r -> {
              assertThat(r.getTargetCurrency().getCurrencyCode())
                  .isEqualTo(TestConstants.VALID_CURRENCY_EUR);
              assertThat(r.getDate()).isEqualTo(LocalDate.of(2024, 1, 2));
              assertThat(r.getRate()).isEqualByComparingTo(new BigDecimal("0.8500"));
            });
  }

  @Test
  void saveExistingEntityUpdates() {
    // Arrange: Create and save EUR rate
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();
    var saved = exchangeRateRepository.saveAndFlush(rate);

    // Act: Update rate value
    saved.setRate(new BigDecimal("0.8600"));
    var updated = exchangeRateRepository.saveAndFlush(saved);

    // Assert: Verify update persisted
    var retrieved = exchangeRateRepository.findById(updated.getId());
    assertThat(retrieved)
        .isPresent()
        .get()
        .extracting(ExchangeRate::getRate)
        .isEqualTo(new BigDecimal("0.8600"));
  }

  @Test
  void deleteSuccess() {
    // Arrange: Create and save EUR rate
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8500"))
            .build();
    var saved = exchangeRateRepository.saveAndFlush(rate);
    var savedId = saved.getId();

    // Act
    exchangeRateRepository.delete(saved);
    exchangeRateRepository.flush();

    // Assert
    var found = exchangeRateRepository.findById(savedId);
    assertThat(found).isEmpty();
  }

  @Test
  void saveWithNullRateSucceeds() {
    // Arrange: Create EUR rate with null value (simulates missing FRED data for weekends)
    var eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();

    var rate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 6)) // Saturday
            .withNullRate()
            .build();

    // Act
    var saved = exchangeRateRepository.saveAndFlush(rate);

    // Assert
    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getRate()).isNull();
  }
}
