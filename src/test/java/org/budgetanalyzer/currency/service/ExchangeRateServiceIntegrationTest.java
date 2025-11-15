package org.budgetanalyzer.currency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.budgetanalyzer.currency.base.AbstractIntegrationTest;
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
 * </ul>
 *
 * <p>Uses TestContainers for PostgreSQL, Redis, and RabbitMQ infrastructure.
 */
class ExchangeRateServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ExchangeRateService exchangeRateService;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  private CurrencySeries eurSeries;
  private CurrencySeries thbSeries;
  private CurrencySeries gbpSeries;

  @BeforeEach
  void setUp() {
    // Clean database for test isolation
    exchangeRateRepository.deleteAll();
    currencySeriesRepository.deleteAll();

    // Create common currency series
    eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    thbSeries = CurrencySeriesTestBuilder.defaultThb().build();
    currencySeriesRepository.save(thbSeries);

    gbpSeries = CurrencySeriesTestBuilder.defaultGbp().build();
    currencySeriesRepository.save(gbpSeries);
  }

  // ===========================================================================================
  // A. Happy Path Query Operations
  // ===========================================================================================

  @Test
  void queryWithAllParametersReturnsDataWithinRange() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query with specific date range (Jan 1-5)
    var queryStart = TestConstants.DATE_2024_JAN_01;
    var queryEnd = TestConstants.DATE_2024_JAN_05;
    var result =
        exchangeRateService.getExchangeRates(TestConstants.CURRENCY_EUR, queryStart, queryEnd);

    // Assert - Returns 5 days of data
    assertThat(result).hasSize(5);
    assertThat(result).allMatch(r -> !r.date().isBefore(queryStart) && !r.date().isAfter(queryEnd));
  }

  @Test
  void queryWithOnlyCurrencyReturnsAllData() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query with null dates
    var result = exchangeRateService.getExchangeRates(TestConstants.CURRENCY_EUR, null, null);

    // Assert - Returns all 10 days
    assertThat(result).hasSize(10);
    assertThat(result.get(0).date()).isEqualTo(startDate);
    assertThat(result.get(result.size() - 1).date()).isEqualTo(endDate);
  }

  @Test
  void queryWithOnlyStartDateReturnsToLatest() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query from Jan 5 onwards (endDate = null)
    var queryStart = TestConstants.DATE_2024_JAN_05;
    var result = exchangeRateService.getExchangeRates(TestConstants.CURRENCY_EUR, queryStart, null);

    // Assert - Returns Jan 5-10 (6 days)
    assertThat(result).hasSize(6);
    assertThat(result.get(0).date()).isEqualTo(queryStart);
    assertThat(result.get(result.size() - 1).date()).isEqualTo(endDate);
  }

  @Test
  void queryWithOnlyEndDateReturnsFromEarliest() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query up to Jan 5 (startDate = null)
    var queryEnd = TestConstants.DATE_2024_JAN_05;
    var result = exchangeRateService.getExchangeRates(TestConstants.CURRENCY_EUR, null, queryEnd);

    // Assert - Returns Jan 1-5 (5 days)
    assertThat(result).hasSize(5);
    assertThat(result.get(0).date()).isEqualTo(startDate);
    assertThat(result.get(result.size() - 1).date()).isEqualTo(queryEnd);
  }

  @Test
  void queryWithSingleDateRangReturnsSingleDay() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query single day (Jan 5)
    var singleDate = TestConstants.DATE_2024_JAN_05;
    var result =
        exchangeRateService.getExchangeRates(TestConstants.CURRENCY_EUR, singleDate, singleDate);

    // Assert - Returns exactly 1 day
    assertThat(result).hasSize(1);
    assertThat(result.get(0).date()).isEqualTo(singleDate);
    assertThat(result.get(0).rate()).isEqualTo(TestConstants.RATE_EUR_USD);
  }

  // ===========================================================================================
  // B. Date Range Filtering
  // ===========================================================================================

  @Test
  void queryReturnsOnlyDataWithinRange() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query Jan 3-7 (middle of available range)
    var queryStart = TestConstants.DATE_2024_JAN_01.plusDays(2);
    var queryEnd = TestConstants.DATE_2024_JAN_01.plusDays(6);
    var result =
        exchangeRateService.getExchangeRates(TestConstants.CURRENCY_EUR, queryStart, queryEnd);

    // Assert - Returns exactly 5 days (Jan 3-7 inclusive)
    assertThat(result).hasSize(5);
    assertThat(result.get(0).date()).isEqualTo(queryStart);
    assertThat(result.get(result.size() - 1).date()).isEqualTo(queryEnd);
  }

  @Test
  void queryExcludesDataBeforeStartDate() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query from Jan 5 onwards
    var queryStart = TestConstants.DATE_2024_JAN_05;
    var result = exchangeRateService.getExchangeRates(TestConstants.CURRENCY_EUR, queryStart, null);

    // Assert - No dates before Jan 5
    assertThat(result).allMatch(r -> !r.date().isBefore(queryStart));
    assertThat(result.get(0).date()).isEqualTo(queryStart);
  }

  @Test
  void queryExcludesDataAfterEndDate() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query up to Jan 5
    var queryEnd = TestConstants.DATE_2024_JAN_05;
    var result = exchangeRateService.getExchangeRates(TestConstants.CURRENCY_EUR, null, queryEnd);

    // Assert - No dates after Jan 5
    assertThat(result).allMatch(r -> !r.date().isAfter(queryEnd));
    assertThat(result.get(result.size() - 1).date()).isEqualTo(queryEnd);
  }

  @Test
  void queryFiltersByTargetCurrencyOnly() {
    // Arrange - Create rates for EUR, THB, and GBP (same dates)
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_05;
    exchangeRateRepository.saveAll(
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD));
    exchangeRateRepository.saveAll(
        ExchangeRateTestBuilder.buildDateRange(
            thbSeries, startDate, endDate, TestConstants.RATE_THB_USD));
    exchangeRateRepository.saveAll(
        ExchangeRateTestBuilder.buildDateRange(
            gbpSeries, startDate, endDate, TestConstants.RATE_GBP_USD));

    // Act - Query only THB
    var result =
        exchangeRateService.getExchangeRates(TestConstants.CURRENCY_THB, startDate, endDate);

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
  void gapFillingForwardsFridayRateToWeekend() {
    // Arrange - Create weekday-only EUR rates (Mon-Fri, Jan 1-5)
    var weekStart = TestConstants.DATE_2024_JAN_01; // Monday
    var weekEnd = TestConstants.DATE_2024_JAN_05; // Friday
    var rates =
        ExchangeRateTestBuilder.buildWeekdaysOnly(
            eurSeries, weekStart, weekEnd, TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query including weekend (Jan 1-7)
    var queryEnd = TestConstants.DATE_2024_JAN_07_WEEKEND; // Sunday
    var result =
        exchangeRateService.getExchangeRates(TestConstants.CURRENCY_EUR, weekStart, queryEnd);

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
    exchangeRateRepository.saveAll(java.util.List.of(jan01, jan02, jan06, jan07));

    // Act - Query Jan 1-7 (includes 3-day gap: Jan 3, 4, 5)
    var result =
        exchangeRateService.getExchangeRates(
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
  void gapFillingPreservesPublishedDate() {
    // Arrange - Create weekday-only rates
    var rates =
        ExchangeRateTestBuilder.buildWeekdaysOnly(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_05,
            TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query including weekend
    var result =
        exchangeRateService.getExchangeRates(
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
  void gapFillingPerformsBackwardLookup() {
    // Arrange - Create rate BEFORE query range (Dec 31, 2023)
    var previousRate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2023, 12, 31))
            .withRate(new BigDecimal("0.8400"))
            .build();
    exchangeRateRepository.save(previousRate);

    // Create rate AFTER query start (Jan 5, 2024)
    var laterRate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(TestConstants.DATE_2024_JAN_05)
            .withRate(new BigDecimal("0.8500"))
            .build();
    exchangeRateRepository.save(laterRate);

    // Act - Query from Jan 1 (no data at Jan 1, but data exists before)
    var result =
        exchangeRateService.getExchangeRates(
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
  void gapFillingNoBackwardLookupNeeded() {
    // Arrange - Create rate at startDate (Jan 1)
    var jan01Rate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(TestConstants.DATE_2024_JAN_01)
            .withRate(new BigDecimal("0.8500"))
            .build();
    exchangeRateRepository.save(jan01Rate);

    // Act - Query from Jan 1
    var result =
        exchangeRateService.getExchangeRates(
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
  void gapFillingProducesContinuousDailyData() {
    // Arrange - Create weekday-only rates (realistic FRED pattern)
    var rates =
        ExchangeRateTestBuilder.buildWeekdaysOnly(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query full range including weekends
    var result =
        exchangeRateService.getExchangeRates(
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
  void gapFillingWithWeekdaysOnlySourceData() {
    // Arrange - Create realistic FRED data (Mon-Fri only, 2 weeks)
    var rates =
        ExchangeRateTestBuilder.buildWeekdaysOnly(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query full range
    var result =
        exchangeRateService.getExchangeRates(
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
  void queryWithStartAfterEndThrowsException() {
    // Arrange - Create some EUR data
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act & Assert - Start after end should throw
    assertThatThrownBy(
            () ->
                exchangeRateService.getExchangeRates(
                    TestConstants.CURRENCY_EUR,
                    TestConstants.DATE_2024_JAN_15,
                    TestConstants.DATE_2024_JAN_01))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Start date must be before or equal to end date");
  }

  @Test
  void queryForNonExistentCurrencyThrowsException() {
    // Act & Assert - Query ZAR (no data exists)
    assertThatThrownBy(
            () ->
                exchangeRateService.getExchangeRates(
                    TestConstants.CURRENCY_ZAR_NOT_IN_DB,
                    TestConstants.DATE_2024_JAN_01,
                    TestConstants.DATE_2024_JAN_15))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("No exchange rate data available");
  }

  @Test
  void queryWithStartBeforeEarliestThrowsException() {
    // Arrange - Create EUR data starting Jan 15, 2024
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.DATE_2024_JAN_15.plusDays(10),
            TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act & Assert - Query starting Jan 1 (before earliest data)
    assertThatThrownBy(
            () ->
                exchangeRateService.getExchangeRates(
                    TestConstants.CURRENCY_EUR, TestConstants.DATE_2024_JAN_01, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("not available before");
  }

  @Test
  void exceptionMessagesContainCurrencyAndDates() {
    // Arrange - Create EUR data starting Jan 15
    var earliestDate = TestConstants.DATE_2024_JAN_15;
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, earliestDate, earliestDate.plusDays(5), TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act & Assert - Verify exception message contains currency and date
    assertThatThrownBy(
            () ->
                exchangeRateService.getExchangeRates(
                    TestConstants.CURRENCY_EUR, TestConstants.DATE_2024_JAN_01, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("EUR")
        .hasMessageContaining("2024-01-15");
  }

  @Test
  void queryWithStartEqualToEarliestSucceeds() {
    // Arrange - Create EUR data starting Jan 15
    var earliestDate = TestConstants.DATE_2024_JAN_15;
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, earliestDate, earliestDate.plusDays(5), TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query starting at earliest date (should succeed)
    var result =
        exchangeRateService.getExchangeRates(TestConstants.CURRENCY_EUR, earliestDate, null);

    // Assert - Returns data
    assertThat(result).isNotEmpty();
    assertThat(result.get(0).date()).isEqualTo(earliestDate);
  }

  // ===========================================================================================
  // E. Empty Result Scenarios
  // ===========================================================================================

  @Test
  void queryForFutureDateRangeReturnsEmptyList() {
    // Arrange - Create EUR data for past dates
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query future dates (2030)
    var result =
        exchangeRateService.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2030_JAN_01,
            TestConstants.DATE_2030_JAN_01.plusDays(10));

    // Assert - Returns empty list (not exception)
    assertThat(result).isEmpty();
  }

  @Test
  void queryForDateRangeWithNoDataReturnsEmptyList() {
    // Arrange - Create EUR data for Jan 1-5
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_05,
            TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query later dates where no data exists (Jun 2024)
    var result =
        exchangeRateService.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JUN_15,
            TestConstants.DATE_2024_JUN_15.plusDays(10));

    // Assert - Returns empty list
    assertThat(result).isEmpty();
  }

  @Test
  void emptyResultIsDistinctFromNoDataException() {
    // Arrange - Create EUR data for past dates
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act - Query future dates (returns empty list, not exception)
    var result =
        exchangeRateService.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2030_JAN_01,
            TestConstants.DATE_2030_JAN_01.plusDays(10));

    // Assert - Empty list returned
    assertThat(result).isEmpty();

    // Act & Assert - Query non-existent currency (throws exception)
    assertThatThrownBy(
            () ->
                exchangeRateService.getExchangeRates(
                    TestConstants.CURRENCY_ZAR_NOT_IN_DB,
                    TestConstants.DATE_2024_JAN_01,
                    TestConstants.DATE_2024_JAN_15))
        .isInstanceOf(BusinessException.class);
  }
}
