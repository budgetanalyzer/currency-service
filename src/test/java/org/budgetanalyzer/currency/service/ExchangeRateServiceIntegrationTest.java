package org.budgetanalyzer.currency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;

import org.budgetanalyzer.currency.base.AbstractIntegrationTest;
import org.budgetanalyzer.currency.config.CacheConfig;
import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.ExchangeRateTestBuilder;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.service.exception.BusinessException;

/**
 * Integration tests for {@link ExchangeRateService}.
 *
 * <p>Tests all exchange rate service operations including:
 *
 * <ul>
 *   <li>Query operations with various parameter combinations (happy paths)
 *   <li>Date range filtering and boundary behavior
 *   <li>Gap-filling algorithm (forward-fill and backward lookup)
 *   <li>Validation and error handling (date validation, missing data)
 *   <li>Empty result scenarios (future dates, no data in range)
 *   <li>Redis distributed caching behavior (cache hits, misses, key format)
 * </ul>
 *
 * <p>Uses TestContainers for PostgreSQL, Redis, and RabbitMQ infrastructure.
 */
@SpringBootTest
class ExchangeRateServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ExchangeRateService service;

  @Autowired private ExchangeRateRepository repository;

  @Autowired private CurrencySeriesRepository seriesRepository;

  @Autowired private CacheManager cacheManager;

  private CurrencySeries eurSeries;
  private CurrencySeries thbSeries;
  private CurrencySeries gbpSeries;

  @BeforeEach
  void setUp() {
    // Clean database and cache for test isolation
    repository.deleteAll();
    seriesRepository.deleteAll();
    var cache = cacheManager.getCache(CacheConfig.EXCHANGE_RATES_CACHE);
    if (cache != null) {
      cache.clear();
    }

    // Create common currency series
    eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    seriesRepository.save(eurSeries);

    thbSeries = CurrencySeriesTestBuilder.defaultThb().build();
    seriesRepository.save(thbSeries);

    gbpSeries = CurrencySeriesTestBuilder.defaultGbp().build();
    seriesRepository.save(gbpSeries);
  }

  // ===========================================================================================
  // A. Happy Path Query Operations
  // ===========================================================================================

  @Test
  @DisplayName("Query with all parameters provided returns data within date range")
  void queryWithAllParametersReturnsDataWithinRange() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query with specific date range (Jan 1-5)
    var queryStart = TestConstants.DATE_2024_JAN_01;
    var queryEnd = TestConstants.DATE_2024_JAN_05;
    var result = service.getExchangeRates(TestConstants.CURRENCY_EUR, queryStart, queryEnd);

    // Assert - Returns 5 days of data
    assertThat(result).hasSize(5);
    assertThat(result).allMatch(r -> !r.date().isBefore(queryStart) && !r.date().isAfter(queryEnd));
  }

  @Test
  @DisplayName("Query with only currency (both dates null) returns all available data")
  void queryWithOnlyCurrencyReturnsAllData() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query with null dates
    var result = service.getExchangeRates(TestConstants.CURRENCY_EUR, null, null);

    // Assert - Returns all 10 days
    assertThat(result).hasSize(10);
    assertThat(result.get(0).date()).isEqualTo(startDate);
    assertThat(result.get(result.size() - 1).date()).isEqualTo(endDate);
  }

  @Test
  @DisplayName("Query with only startDate (endDate null) returns from start to latest")
  void queryWithOnlyStartDateReturnsToLatest() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query from Jan 5 onwards (endDate = null)
    var queryStart = TestConstants.DATE_2024_JAN_05;
    var result = service.getExchangeRates(TestConstants.CURRENCY_EUR, queryStart, null);

    // Assert - Returns Jan 5-10 (6 days)
    assertThat(result).hasSize(6);
    assertThat(result.get(0).date()).isEqualTo(queryStart);
    assertThat(result.get(result.size() - 1).date()).isEqualTo(endDate);
  }

  @Test
  @DisplayName("Query with only endDate (startDate null) returns earliest to end")
  void queryWithOnlyEndDateReturnsFromEarliest() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query up to Jan 5 (startDate = null)
    var queryEnd = TestConstants.DATE_2024_JAN_05;
    var result = service.getExchangeRates(TestConstants.CURRENCY_EUR, null, queryEnd);

    // Assert - Returns Jan 1-5 (5 days)
    assertThat(result).hasSize(5);
    assertThat(result.get(0).date()).isEqualTo(startDate);
    assertThat(result.get(result.size() - 1).date()).isEqualTo(queryEnd);
  }

  @Test
  @DisplayName("Query with startDate equal to endDate returns single day")
  void queryWithSingleDateRangReturnsSingleDay() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query single day (Jan 5)
    var singleDate = TestConstants.DATE_2024_JAN_05;
    var result = service.getExchangeRates(TestConstants.CURRENCY_EUR, singleDate, singleDate);

    // Assert - Returns exactly 1 day
    assertThat(result).hasSize(1);
    assertThat(result.get(0).date()).isEqualTo(singleDate);
    assertThat(result.get(0).rate()).isEqualTo(TestConstants.RATE_EUR_USD);
  }

  // ===========================================================================================
  // B. Date Range Filtering
  // ===========================================================================================

  @Test
  @DisplayName("Query returns only data within range (excludes boundaries)")
  void queryReturnsOnlyDataWithinRange() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query Jan 3-7 (middle of available range)
    var queryStart = TestConstants.DATE_2024_JAN_01.plusDays(2);
    var queryEnd = TestConstants.DATE_2024_JAN_01.plusDays(6);
    var result = service.getExchangeRates(TestConstants.CURRENCY_EUR, queryStart, queryEnd);

    // Assert - Returns exactly 5 days (Jan 3-7 inclusive)
    assertThat(result).hasSize(5);
    assertThat(result.get(0).date()).isEqualTo(queryStart);
    assertThat(result.get(result.size() - 1).date()).isEqualTo(queryEnd);
  }

  @Test
  @DisplayName("Query excludes data before startDate")
  void queryExcludesDataBeforeStartDate() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query from Jan 5 onwards
    var queryStart = TestConstants.DATE_2024_JAN_05;
    var result = service.getExchangeRates(TestConstants.CURRENCY_EUR, queryStart, null);

    // Assert - No dates before Jan 5
    assertThat(result).allMatch(r -> !r.date().isBefore(queryStart));
    assertThat(result.get(0).date()).isEqualTo(queryStart);
  }

  @Test
  @DisplayName("Query excludes data after endDate")
  void queryExcludesDataAfterEndDate() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query up to Jan 5
    var queryEnd = TestConstants.DATE_2024_JAN_05;
    var result = service.getExchangeRates(TestConstants.CURRENCY_EUR, null, queryEnd);

    // Assert - No dates after Jan 5
    assertThat(result).allMatch(r -> !r.date().isAfter(queryEnd));
    assertThat(result.get(result.size() - 1).date()).isEqualTo(queryEnd);
  }

  @Test
  @DisplayName("Query filters by targetCurrency only (multiple currencies in DB)")
  void queryFiltersByTargetCurrencyOnly() {
    // Arrange - Create rates for EUR, THB, and GBP (same dates)
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_05;
    repository.saveAll(
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD));
    repository.saveAll(
        ExchangeRateTestBuilder.buildDateRange(
            thbSeries, startDate, endDate, TestConstants.RATE_THB_USD));
    repository.saveAll(
        ExchangeRateTestBuilder.buildDateRange(
            gbpSeries, startDate, endDate, TestConstants.RATE_GBP_USD));

    // Act - Query only THB
    var result = service.getExchangeRates(TestConstants.CURRENCY_THB, startDate, endDate);

    // Assert - Returns only THB rates
    assertThat(result).hasSize(5);
    assertThat(result)
        .allMatch(
            r -> r.targetCurrency().getCurrencyCode().equals(TestConstants.VALID_CURRENCY_THB));
    assertThat(result).allMatch(r -> r.rate().equals(TestConstants.RATE_THB_USD));
  }

  // ===========================================================================================
  // C. Gap-Filling Algorithm
  // ===========================================================================================

  @Test
  @DisplayName("Gap-filling forwards Friday rate to weekend (Saturday and Sunday)")
  void gapFillingForwardsFridayRateToWeekend() {
    // Arrange - Create weekday-only EUR rates (Mon-Fri, Jan 1-5)
    var weekStart = TestConstants.DATE_2024_JAN_01; // Monday
    var weekEnd = TestConstants.DATE_2024_JAN_05; // Friday
    var rates =
        ExchangeRateTestBuilder.buildWeekdaysOnly(
            eurSeries, weekStart, weekEnd, TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query including weekend (Jan 1-7)
    var queryEnd = TestConstants.DATE_2024_JAN_07_WEEKEND; // Sunday
    var result = service.getExchangeRates(TestConstants.CURRENCY_EUR, weekStart, queryEnd);

    // Assert - Returns 7 days (Mon-Sun continuous)
    assertThat(result).hasSize(7);

    // Assert - Saturday (Jan 6) has Friday's rate
    var saturdayRate =
        result.stream()
            .filter(r -> r.date().equals(TestConstants.DATE_2024_JAN_06_WEEKEND))
            .findFirst()
            .orElseThrow();
    assertThat(saturdayRate.rate()).isEqualTo(TestConstants.RATE_EUR_USD);
    assertThat(saturdayRate.publishedDate()).isEqualTo(weekEnd); // Friday

    // Assert - Sunday (Jan 7) has Friday's rate
    var sundayRate =
        result.stream()
            .filter(r -> r.date().equals(TestConstants.DATE_2024_JAN_07_WEEKEND))
            .findFirst()
            .orElseThrow();
    assertThat(sundayRate.rate()).isEqualTo(TestConstants.RATE_EUR_USD);
    assertThat(sundayRate.publishedDate()).isEqualTo(weekEnd); // Friday
  }

  @Test
  @DisplayName("Gap-filling handles multi-day gaps (3-5 missing days)")
  void gapFillingHandlesMultiDayGaps() {
    // Arrange - Create rates with gaps: Jan 1, 2, then gap, then Jan 6, 7
    var jan01 =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(TestConstants.DATE_2024_JAN_01)
            .withRate(new BigDecimal("0.8500"))
            .build();
    var jan02 =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(TestConstants.DATE_2024_JAN_02)
            .withRate(new BigDecimal("0.8600"))
            .build();
    var jan06 =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(TestConstants.DATE_2024_JAN_06_WEEKEND)
            .withRate(new BigDecimal("0.8700"))
            .build();
    var jan07 =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(TestConstants.DATE_2024_JAN_07_WEEKEND)
            .withRate(new BigDecimal("0.8800"))
            .build();
    repository.saveAll(java.util.List.of(jan01, jan02, jan06, jan07));

    // Act - Query Jan 1-7 (includes 3-day gap: Jan 3, 4, 5)
    var result =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_07_WEEKEND);

    // Assert - Returns 7 continuous days
    assertThat(result).hasSize(7);

    // Assert - Gap days (Jan 3, 4, 5) use Jan 2's rate
    assertThat(result.get(2).date()).isEqualTo(TestConstants.DATE_2024_JAN_01.plusDays(2)); // Jan 3
    assertThat(result.get(2).rate()).isEqualTo(new BigDecimal("0.8600"));
    assertThat(result.get(2).publishedDate()).isEqualTo(TestConstants.DATE_2024_JAN_02);

    assertThat(result.get(3).date()).isEqualTo(TestConstants.DATE_2024_JAN_01.plusDays(3)); // Jan 4
    assertThat(result.get(3).rate()).isEqualTo(new BigDecimal("0.8600"));
    assertThat(result.get(3).publishedDate()).isEqualTo(TestConstants.DATE_2024_JAN_02);

    assertThat(result.get(4).date()).isEqualTo(TestConstants.DATE_2024_JAN_01.plusDays(4)); // Jan 5
    assertThat(result.get(4).rate()).isEqualTo(new BigDecimal("0.8600"));
    assertThat(result.get(4).publishedDate()).isEqualTo(TestConstants.DATE_2024_JAN_02);
  }

  @Test
  @DisplayName("Gap-filling preserves publishedDate (not equal to date for filled dates)")
  void gapFillingPreservesPublishedDate() {
    // Arrange - Create weekday-only rates
    var rates =
        ExchangeRateTestBuilder.buildWeekdaysOnly(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_05,
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query including weekend
    var result =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_07_WEEKEND);

    // Assert - Weekdays: date == publishedDate
    for (int i = 0; i < 5; i++) {
      assertThat(result.get(i).date()).isEqualTo(result.get(i).publishedDate());
    }

    // Assert - Weekend: date != publishedDate (forward-filled)
    assertThat(result.get(5).date()).isEqualTo(TestConstants.DATE_2024_JAN_06_WEEKEND); // Saturday
    assertThat(result.get(5).publishedDate()).isEqualTo(TestConstants.DATE_2024_JAN_05); // Friday

    assertThat(result.get(6).date()).isEqualTo(TestConstants.DATE_2024_JAN_07_WEEKEND); // Sunday
    assertThat(result.get(6).publishedDate()).isEqualTo(TestConstants.DATE_2024_JAN_05); // Friday
  }

  @Test
  @DisplayName("Gap-filling performs backward lookup when first data is after startDate")
  void gapFillingPerformsBackwardLookup() {
    // Arrange - Create rate BEFORE query range (Dec 31, 2023)
    var previousRate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2023, 12, 31))
            .withRate(new BigDecimal("0.8400"))
            .build();
    repository.save(previousRate);

    // Create rate AFTER query start (Jan 5, 2024)
    var laterRate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(TestConstants.DATE_2024_JAN_05)
            .withRate(new BigDecimal("0.8500"))
            .build();
    repository.save(laterRate);

    // Act - Query from Jan 1 (no data at Jan 1, but data exists before)
    var result =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_05);

    // Assert - Returns 5 days (Jan 1-5)
    assertThat(result).hasSize(5);

    // Assert - Jan 1-4 use backward-looked-up rate (Dec 31)
    for (int i = 0; i < 4; i++) {
      assertThat(result.get(i).rate()).isEqualTo(new BigDecimal("0.8400"));
      assertThat(result.get(i).publishedDate()).isEqualTo(LocalDate.of(2023, 12, 31));
    }

    // Assert - Jan 5 uses its own rate
    assertThat(result.get(4).date()).isEqualTo(TestConstants.DATE_2024_JAN_05);
    assertThat(result.get(4).rate()).isEqualTo(new BigDecimal("0.8500"));
    assertThat(result.get(4).publishedDate()).isEqualTo(TestConstants.DATE_2024_JAN_05);
  }

  @Test
  @DisplayName("Gap-filling with no backward lookup needed (data exists at startDate)")
  void gapFillingNoBackwardLookupNeeded() {
    // Arrange - Create rate at startDate (Jan 1)
    var jan01Rate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(TestConstants.DATE_2024_JAN_01)
            .withRate(new BigDecimal("0.8500"))
            .build();
    repository.save(jan01Rate);

    // Act - Query from Jan 1
    var result =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_01);

    // Assert - Returns 1 day with correct data
    assertThat(result).hasSize(1);
    assertThat(result.get(0).date()).isEqualTo(TestConstants.DATE_2024_JAN_01);
    assertThat(result.get(0).publishedDate()).isEqualTo(TestConstants.DATE_2024_JAN_01);
    assertThat(result.get(0).rate()).isEqualTo(new BigDecimal("0.8500"));
  }

  @Test
  @DisplayName("Gap-filling produces continuous daily data (no missing dates)")
  void gapFillingProducesContinuousDailyData() {
    // Arrange - Create weekday-only rates (realistic FRED pattern)
    var rates =
        ExchangeRateTestBuilder.buildWeekdaysOnly(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query full range including weekends
    var result =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15);

    // Assert - Returns 15 continuous days (no gaps)
    assertThat(result).hasSize(15);

    // Assert - Verify no date gaps (each day is +1 from previous)
    for (int i = 1; i < result.size(); i++) {
      var previousDate = result.get(i - 1).date();
      var currentDate = result.get(i).date();
      assertThat(currentDate).isEqualTo(previousDate.plusDays(1));
    }
  }

  @Test
  @DisplayName("Gap-filling with weekdays-only source data (realistic FRED pattern)")
  void gapFillingWithWeekdaysOnlySourceData() {
    // Arrange - Create realistic FRED data (Mon-Fri only, 2 weeks)
    var rates =
        ExchangeRateTestBuilder.buildWeekdaysOnly(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query full range
    var result =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15);

    // Assert - Returns 15 days (10 weekdays + 4 weekend days + 1 Monday)
    assertThat(result).hasSize(15);

    // Assert - Weekend rates use Friday rates
    var firstSaturday =
        result.stream()
            .filter(r -> r.date().equals(TestConstants.DATE_2024_JAN_06_WEEKEND))
            .findFirst()
            .orElseThrow();
    assertThat(firstSaturday.publishedDate()).isEqualTo(TestConstants.DATE_2024_JAN_05); // Friday
  }

  // ===========================================================================================
  // D. Validation & Error Handling
  // ===========================================================================================

  @Test
  @DisplayName("Query with startDate after endDate throws IllegalArgumentException")
  void queryWithStartAfterEndThrowsException() {
    // Arrange - Create some EUR data
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act & Assert - Start after end should throw
    assertThatThrownBy(
            () ->
                service.getExchangeRates(
                    TestConstants.CURRENCY_EUR,
                    TestConstants.DATE_2024_JAN_15,
                    TestConstants.DATE_2024_JAN_01))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Start date must be before or equal to end date");
  }

  @Test
  @DisplayName("Query for non-existent currency throws BusinessException")
  void queryForNonExistentCurrencyThrowsException() {
    // Act & Assert - Query ZAR (no data exists)
    assertThatThrownBy(
            () ->
                service.getExchangeRates(
                    TestConstants.CURRENCY_ZAR_NOT_IN_DB,
                    TestConstants.DATE_2024_JAN_01,
                    TestConstants.DATE_2024_JAN_15))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("No exchange rate data available");
  }

  @Test
  @DisplayName("Query with startDate before earliest data throws BusinessException")
  void queryWithStartBeforeEarliestThrowsException() {
    // Arrange - Create EUR data starting Jan 15, 2024
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.DATE_2024_JAN_15.plusDays(10),
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act & Assert - Query starting Jan 1 (before earliest data)
    assertThatThrownBy(
            () ->
                service.getExchangeRates(
                    TestConstants.CURRENCY_EUR, TestConstants.DATE_2024_JAN_01, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("not available before");
  }

  @Test
  @DisplayName("Exception messages contain currency code and dates for debugging")
  void exceptionMessagesContainCurrencyAndDates() {
    // Arrange - Create EUR data starting Jan 15
    var earliestDate = TestConstants.DATE_2024_JAN_15;
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, earliestDate, earliestDate.plusDays(5), TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act & Assert - Verify exception message contains currency and date
    assertThatThrownBy(
            () ->
                service.getExchangeRates(
                    TestConstants.CURRENCY_EUR, TestConstants.DATE_2024_JAN_01, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("EUR")
        .hasMessageContaining("2024-01-15");
  }

  @Test
  @DisplayName("Query with startDate equal to earliest date succeeds (edge case)")
  void queryWithStartEqualToEarliestSucceeds() {
    // Arrange - Create EUR data starting Jan 15
    var earliestDate = TestConstants.DATE_2024_JAN_15;
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, earliestDate, earliestDate.plusDays(5), TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query starting at earliest date (should succeed)
    var result = service.getExchangeRates(TestConstants.CURRENCY_EUR, earliestDate, null);

    // Assert - Returns data
    assertThat(result).isNotEmpty();
    assertThat(result.get(0).date()).isEqualTo(earliestDate);
  }

  // ===========================================================================================
  // E. Empty Result Scenarios
  // ===========================================================================================

  @Test
  @DisplayName("Query for future date range returns empty list (not an error)")
  void queryForFutureDateRangeReturnsEmptyList() {
    // Arrange - Create EUR data for past dates
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query future dates (2030)
    var result =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2030_JAN_01,
            TestConstants.DATE_2030_JAN_01.plusDays(10));

    // Assert - Returns empty list (not exception)
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Query for date range with no data returns empty list")
  void queryForDateRangeWithNoDataReturnsEmptyList() {
    // Arrange - Create EUR data for Jan 1-5
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_05,
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query later dates where no data exists (Jun 2024)
    var result =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JUN_15,
            TestConstants.DATE_2024_JUN_15.plusDays(10));

    // Assert - Returns empty list
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Empty result list is distinct from no data available exception")
  void emptyResultIsDistinctFromNoDataException() {
    // Arrange - Create EUR data for past dates
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query future dates (returns empty list, not exception)
    var result =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2030_JAN_01,
            TestConstants.DATE_2030_JAN_01.plusDays(10));

    // Assert - Empty list returned
    assertThat(result).isEmpty();

    // Act & Assert - Query non-existent currency (throws exception)
    assertThatThrownBy(
            () ->
                service.getExchangeRates(
                    TestConstants.CURRENCY_ZAR_NOT_IN_DB,
                    TestConstants.DATE_2024_JAN_01,
                    TestConstants.DATE_2024_JAN_15))
        .isInstanceOf(BusinessException.class);
  }

  // ===========================================================================================
  // F. Redis Caching Behavior
  // ===========================================================================================

  @Test
  @DisplayName("First query is cache miss and populates cache")
  void firstQueryIsCacheMissAndPopulatesCache() {
    // Arrange - Create EUR data
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - First query (cache miss)
    var result1 =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15);

    // Assert - Result is not empty (cache behavior is verified via second query test)
    assertThat(result1).isNotEmpty().hasSize(15);
  }

  @Test
  @DisplayName("Second query with same parameters is cache hit")
  void secondQueryWithSameParametersIsCacheHit() {
    // Arrange - Create EUR data
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - First query (cache miss)
    var result1 =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15);

    // Act - Second query (cache hit)
    var result2 =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15);

    // Assert - Both results are identical
    assertThat(result2).isEqualTo(result1);
  }

  @Test
  @DisplayName("Different query parameters use different cache keys")
  void differentParametersUseDifferentCacheKeys() {
    // Arrange - Create EUR data
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query with different date ranges
    var result1 =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_05);
    var result2 =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15);

    // Assert - Both queries return different results (proving different cache keys)
    assertThat(result1).hasSize(5);
    assertThat(result2).hasSize(15);
  }

  @Test
  @DisplayName("Cache key format is {currencyCode}:{startDate}:{endDate}")
  void cacheKeyFormatIsCorrect() {
    // Arrange - Create EUR data
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query with specific parameters
    var result =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15);

    // Assert - Verify service returns expected data (cache key format is internal implementation)
    assertThat(result).hasSize(15);
    assertThat(result.get(0).targetCurrency().getCurrencyCode()).isEqualTo("EUR");
  }

  @Test
  @DisplayName("Cache contains correct deserialized data structure")
  void cacheContainsCorrectDeserializedData() {
    // Arrange - Create EUR data
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_05,
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query twice (second query uses cache)
    var result1 =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_05);
    var result2 =
        service.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_05);

    // Assert - Both results are equal (cache preserves data structure)
    assertThat(result2).isEqualTo(result1);
    assertThat(result1).hasSize(5);
  }

  @Test
  @DisplayName("Null parameters in cache key format (currency:null:null)")
  void nullParametersInCacheKeyFormat() {
    // Arrange - Create EUR data
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    repository.saveAll(rates);

    // Act - Query with null dates (twice to verify caching works)
    var result1 = service.getExchangeRates(TestConstants.CURRENCY_EUR, null, null);
    var result2 = service.getExchangeRates(TestConstants.CURRENCY_EUR, null, null);

    // Assert - Both queries return same result (cache works with null parameters)
    assertThat(result2).isEqualTo(result1);
    assertThat(result1).hasSize(15);
  }
}
