package org.budgetanalyzer.currency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

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

    // Assert - Behavior change: a null endDate means today (the fixed clock date), not the last
    // stored rate, so the result runs from the first rate through today
    assertThat(result)
        .hasSize((int) ChronoUnit.DAYS.between(startDate, TestConstants.FIXED_TODAY) + 1);
    assertThat(result.get(0).date()).isEqualTo(startDate);
    assertThat(result.get(result.size() - 1).date()).isEqualTo(TestConstants.FIXED_TODAY);
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

    // Assert - Behavior change: a null endDate means today, so the result runs from Jan 5 through
    // the fixed clock date instead of stopping at the last stored rate (Jan 10)
    assertThat(result)
        .hasSize((int) ChronoUnit.DAYS.between(queryStart, TestConstants.FIXED_TODAY) + 1);
    assertThat(result.get(0).date()).isEqualTo(queryStart);
    assertThat(result.get(result.size() - 1).date()).isEqualTo(TestConstants.FIXED_TODAY);
  }

  @Test
  void queryWithNullEndDateForwardFillsLastRateToToday() {
    // Arrange - EUR rates for Jan 1-10, 2024 with a different rate every day
    var startDate = TestConstants.DATE_2024_JAN_01;
    var lastRateDate = TestConstants.DATE_2024_JAN_01.plusDays(9);
    exchangeRateRepository.saveAll(
        ExchangeRateTestBuilder.buildDateRangeWithIncrement(
            eurSeries,
            startDate,
            lastRateDate,
            TestConstants.RATE_EUR_USD,
            new BigDecimal("0.0100")));

    // Act
    var result = exchangeRateService.getExchangeRates(TestConstants.CURRENCY_EUR, null, null);

    // Assert - Every day after the last stored rate carries that rate and its published date
    var lastRate =
        result.stream().filter(r -> r.date().equals(lastRateDate)).findFirst().orElseThrow();
    var forwardFilled = result.stream().filter(r -> r.date().isAfter(lastRateDate)).toList();
    assertThat(forwardFilled)
        .hasSize((int) ChronoUnit.DAYS.between(lastRateDate, TestConstants.FIXED_TODAY));
    assertThat(forwardFilled)
        .allSatisfy(
            exchangeRateData -> {
              assertThat(exchangeRateData.publishedDate()).isEqualTo(lastRateDate);
              assertThat(exchangeRateData.rate()).isEqualByComparingTo(lastRate.rate());
            });
    assertThat(lastRate.rate()).isNotEqualByComparingTo(result.getFirst().rate());
    assertThat(result.getLast().date()).isEqualTo(TestConstants.FIXED_TODAY);
  }

  @Test
  void queryWithFarFutureEndDateIsClampedToToday() {
    // Arrange - Create EUR rates for Jan 1-10, 2024
    var startDate = TestConstants.DATE_2024_JAN_01;
    exchangeRateRepository.saveAll(
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, startDate.plusDays(9), TestConstants.RATE_EUR_USD));

    // Act - End date years after today
    var result =
        exchangeRateService.getExchangeRates(
            TestConstants.CURRENCY_EUR, startDate, TestConstants.DATE_2030_JAN_01);

    // Assert - The response stops at today instead of growing to 2030
    assertThat(result)
        .hasSize((int) ChronoUnit.DAYS.between(startDate, TestConstants.FIXED_TODAY) + 1);
    assertThat(result.getLast().date()).isEqualTo(TestConstants.FIXED_TODAY);
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

    // Assert - Returns exactly 1 day with inverted rate (1/0.85 = 1.1765)
    assertThat(result).hasSize(1);
    assertThat(result.get(0).date()).isEqualTo(singleDate);
    assertThat(result.get(0).rate())
        .isCloseTo(new BigDecimal("1.1765"), within(new BigDecimal("0.0001")));
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

    // EUR rate is inverted (1/0.85 = 1.1765) since API normalizes to USD as base
    var expectedRate = new BigDecimal("1.1765");

    // Assert - Saturday (Jan 6) has Friday's rate (inverted)
    var saturdayRate =
        result.stream()
            .filter(r -> r.date().equals(TestConstants.DATE_2024_JAN_06_WEEKEND))
            .findFirst()
            .orElseThrow();
    assertThat(saturdayRate.rate()).isCloseTo(expectedRate, within(new BigDecimal("0.0001")));
    assertThat(saturdayRate.publishedDate()).isEqualTo(weekEnd); // Friday

    // Assert - Sunday (Jan 7) has Friday's rate (inverted)
    var sundayRate =
        result.stream()
            .filter(r -> r.date().equals(TestConstants.DATE_2024_JAN_07_WEEKEND))
            .findFirst()
            .orElseThrow();
    assertThat(sundayRate.rate()).isCloseTo(expectedRate, within(new BigDecimal("0.0001")));
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
    exchangeRateRepository.saveAll(List.of(jan01, jan02, jan06, jan07));

    // Act - Query Jan 1-7 (includes 3-day gap: Jan 3, 4, 5)
    var result =
        exchangeRateService.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_07_WEEKEND);

    // Assert - Returns 7 continuous days
    assertThat(result).hasSize(7);

    // EUR rates are inverted (1/0.86 = 1.1628) since API normalizes to USD as base
    var expectedGapRate = new BigDecimal("1.1628");

    // Assert - Gap days (Jan 3, 4, 5) use Jan 2's inverted rate
    assertThat(result.get(2).date()).isEqualTo(TestConstants.DATE_2024_JAN_01.plusDays(2)); // Jan 3
    assertThat(result.get(2).rate()).isCloseTo(expectedGapRate, within(new BigDecimal("0.0001")));
    assertThat(result.get(2).publishedDate()).isEqualTo(TestConstants.DATE_2024_JAN_02);

    assertThat(result.get(3).date()).isEqualTo(TestConstants.DATE_2024_JAN_01.plusDays(3)); // Jan 4
    assertThat(result.get(3).rate()).isCloseTo(expectedGapRate, within(new BigDecimal("0.0001")));
    assertThat(result.get(3).publishedDate()).isEqualTo(TestConstants.DATE_2024_JAN_02);

    assertThat(result.get(4).date()).isEqualTo(TestConstants.DATE_2024_JAN_01.plusDays(4)); // Jan 5
    assertThat(result.get(4).rate()).isCloseTo(expectedGapRate, within(new BigDecimal("0.0001")));
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

    // EUR rates are inverted since API normalizes to USD as base
    var expectedBackwardRate = new BigDecimal("1.1905"); // 1/0.84
    var expectedJan5Rate = new BigDecimal("1.1765"); // 1/0.85

    // Assert - Jan 1-4 use backward-looked-up inverted rate (Dec 31)
    for (int i = 0; i < 4; i++) {
      assertThat(result.get(i).rate())
          .isCloseTo(expectedBackwardRate, within(new BigDecimal("0.0001")));
      assertThat(result.get(i).publishedDate()).isEqualTo(LocalDate.of(2023, 12, 31));
    }

    // Assert - Jan 5 uses its own inverted rate
    assertThat(result.get(4).date()).isEqualTo(TestConstants.DATE_2024_JAN_05);
    assertThat(result.get(4).rate()).isCloseTo(expectedJan5Rate, within(new BigDecimal("0.0001")));
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

    // Assert - Returns 1 day with inverted rate (1/0.85 = 1.1765)
    assertThat(result).hasSize(1);
    assertThat(result.get(0).date()).isEqualTo(TestConstants.DATE_2024_JAN_01);
    assertThat(result.get(0).publishedDate()).isEqualTo(TestConstants.DATE_2024_JAN_01);
    assertThat(result.get(0).rate())
        .isCloseTo(new BigDecimal("1.1765"), within(new BigDecimal("0.0001")));
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
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void queryWithFutureStartAfterFutureEndThrowsExceptionBeforeClamping() {
    // Arrange - Create some EUR data
    exchangeRateRepository.saveAll(
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD));

    // Act & Assert - Both dates are after today; the reversed input is rejected, not clamped into
    // an empty result
    assertThatThrownBy(
            () ->
                exchangeRateService.getExchangeRates(
                    TestConstants.CURRENCY_EUR,
                    TestConstants.DATE_2030_JAN_01.plusDays(10),
                    TestConstants.DATE_2030_JAN_01))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void queryForNonExistentCurrencyThrowsException() {
    // Arrange - GBP series exists and is enabled, but has no exchange rate data
    // (no exchange rates saved for GBP)

    // Act & Assert - Query GBP (enabled but no data exists)
    assertThatThrownBy(
            () ->
                exchangeRateService.getExchangeRates(
                    TestConstants.CURRENCY_GBP,
                    TestConstants.DATE_2024_JAN_01,
                    TestConstants.DATE_2024_JAN_15))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(CurrencyServiceError.NO_EXCHANGE_RATE_DATA_AVAILABLE.name()));
  }

  @Test
  void queryForDisabledCurrencyThrowsException() {
    // Arrange - Create disabled GBP currency series and exchange rate data
    gbpSeries.setEnabled(false);
    currencySeriesRepository.save(gbpSeries);

    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            gbpSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_GBP_USD);
    exchangeRateRepository.saveAll(rates);

    // Act & Assert - Query disabled currency throws CURRENCY_NOT_ENABLED
    assertThatThrownBy(
            () ->
                exchangeRateService.getExchangeRates(
                    TestConstants.CURRENCY_GBP,
                    TestConstants.DATE_2024_JAN_01,
                    TestConstants.DATE_2024_JAN_15))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(CurrencyServiceError.CURRENCY_NOT_ENABLED.name()));
  }

  @Test
  void queryForCurrencyNotInDatabaseThrowsException() {
    // Act & Assert - Query ZAR (currency not in database at all)
    assertThatThrownBy(
            () ->
                exchangeRateService.getExchangeRates(
                    TestConstants.CURRENCY_ZAR_NOT_IN_DB,
                    TestConstants.DATE_2024_JAN_01,
                    TestConstants.DATE_2024_JAN_15))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(CurrencyServiceError.CURRENCY_NOT_ENABLED.name()));
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
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(CurrencyServiceError.START_DATE_OUT_OF_RANGE.name()));
  }

  @Test
  void queryBeforeEarliestUsesStartDateOutOfRangeErrorCode() {
    // Arrange - Create EUR data starting Jan 15
    var earliestDate = TestConstants.DATE_2024_JAN_15;
    var rates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, earliestDate, earliestDate.plusDays(5), TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(rates);

    // Act & Assert
    assertThatThrownBy(
            () ->
                exchangeRateService.getExchangeRates(
                    TestConstants.CURRENCY_EUR, TestConstants.DATE_2024_JAN_01, null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(CurrencyServiceError.START_DATE_OUT_OF_RANGE.name()));
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
  void queryForRangeStartingAfterTodayReturnsEmptyList() {
    // Arrange - Create EUR data for past dates
    exchangeRateRepository.saveAll(
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_15,
            TestConstants.RATE_EUR_USD));

    // Act - Correctly ordered range that starts the day after today; clamping its end date to
    // today leaves an effective end before the start
    var result =
        exchangeRateService.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.FIXED_TODAY.plusDays(1),
            TestConstants.FIXED_TODAY.plusDays(30));

    // Assert - Empty list, not an exception
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

    // Act & Assert - Query currency with no data (THB exists but has no exchange rates)
    assertThatThrownBy(
            () ->
                exchangeRateService.getExchangeRates(
                    TestConstants.CURRENCY_THB,
                    TestConstants.DATE_2024_JAN_01,
                    TestConstants.DATE_2024_JAN_15))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(CurrencyServiceError.NO_EXCHANGE_RATE_DATA_AVAILABLE.name()));
  }

  // ===========================================================================================
  // F. Rate Inversion (USD Normalization)
  // ===========================================================================================

  @Test
  void queryInvertsRateForUsdPerForeignSeries() {
    // Arrange - Create AUD series with DEXUSAL pattern (USD per AUD)
    var audSeries = CurrencySeriesTestBuilder.defaultAud().build();
    currencySeriesRepository.save(audSeries);

    // Store with base=AUD, target=USD (as import service would)
    // Rate 0.66 means 1 AUD = 0.66 USD
    var rate =
        ExchangeRateTestBuilder.forSeries(audSeries)
            .withDate(TestConstants.DATE_2024_JAN_02)
            .withRate(new BigDecimal("0.6600"))
            .build();
    exchangeRateRepository.save(rate);

    // Act
    var result =
        exchangeRateService.getExchangeRates(
            TestConstants.CURRENCY_AUD,
            TestConstants.DATE_2024_JAN_02,
            TestConstants.DATE_2024_JAN_02);

    // Assert - Service returns USD as base with inverted rate (1/0.66 = 1.5152)
    assertThat(result).hasSize(1);
    assertThat(result.get(0).baseCurrency().getCurrencyCode()).isEqualTo("USD");
    assertThat(result.get(0).targetCurrency().getCurrencyCode()).isEqualTo("AUD");
    assertThat(result.get(0).rate())
        .isCloseTo(new BigDecimal("1.5152"), within(new BigDecimal("0.0001")));
  }

  @Test
  void queryInvertsRateForEurSeries() {
    // Arrange - EUR uses DEXUSEU pattern (USD per EUR)
    // Store with base=EUR, target=USD (as import service would)
    // Rate 0.85 means 1 EUR = 0.85 USD
    var rate =
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(TestConstants.DATE_2024_JAN_02)
            .withRate(new BigDecimal("0.8500"))
            .build();
    exchangeRateRepository.save(rate);

    // Act
    var result =
        exchangeRateService.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_02,
            TestConstants.DATE_2024_JAN_02);

    // Assert - Service returns USD as base with inverted rate (1/0.85 = 1.1765)
    assertThat(result).hasSize(1);
    assertThat(result.get(0).baseCurrency().getCurrencyCode()).isEqualTo("USD");
    assertThat(result.get(0).targetCurrency().getCurrencyCode()).isEqualTo("EUR");
    assertThat(result.get(0).rate())
        .isCloseTo(new BigDecimal("1.1765"), within(new BigDecimal("0.0001")));
  }

  @Test
  void queryDoesNotInvertRateForForeignPerUsdSeries() {
    // Arrange - THB uses DEXTHUS pattern (THB per USD)
    // Store with base=USD, target=THB (as import service would)
    // Rate 32.68 means 1 USD = 32.68 THB
    var rate =
        ExchangeRateTestBuilder.forSeries(thbSeries)
            .withDate(TestConstants.DATE_2024_JAN_02)
            .withRate(new BigDecimal("32.6800"))
            .build();
    exchangeRateRepository.save(rate);

    // Act
    var result =
        exchangeRateService.getExchangeRates(
            TestConstants.CURRENCY_THB,
            TestConstants.DATE_2024_JAN_02,
            TestConstants.DATE_2024_JAN_02);

    // Assert - Service returns USD as base, rate unchanged
    assertThat(result).hasSize(1);
    assertThat(result.get(0).baseCurrency().getCurrencyCode()).isEqualTo("USD");
    assertThat(result.get(0).targetCurrency().getCurrencyCode()).isEqualTo("THB");
    assertThat(result.get(0).rate()).isEqualByComparingTo(new BigDecimal("32.6800"));
  }

  @Test
  void queryReturnsConsistentUsdBaseAcrossMultipleCurrencies() {
    // Arrange - Create rates for both EUR (DEXUS*) and THB (DEX*US)
    var audSeries = CurrencySeriesTestBuilder.defaultAud().build();
    currencySeriesRepository.save(audSeries);

    exchangeRateRepository.save(
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(TestConstants.DATE_2024_JAN_02)
            .withRate(new BigDecimal("0.8500"))
            .build());
    exchangeRateRepository.save(
        ExchangeRateTestBuilder.forSeries(thbSeries)
            .withDate(TestConstants.DATE_2024_JAN_02)
            .withRate(new BigDecimal("32.6800"))
            .build());
    exchangeRateRepository.save(
        ExchangeRateTestBuilder.forSeries(audSeries)
            .withDate(TestConstants.DATE_2024_JAN_02)
            .withRate(new BigDecimal("0.6600"))
            .build());

    // Act - Query all three currencies
    var eurResult =
        exchangeRateService.getExchangeRates(
            TestConstants.CURRENCY_EUR,
            TestConstants.DATE_2024_JAN_02,
            TestConstants.DATE_2024_JAN_02);
    var thbResult =
        exchangeRateService.getExchangeRates(
            TestConstants.CURRENCY_THB,
            TestConstants.DATE_2024_JAN_02,
            TestConstants.DATE_2024_JAN_02);
    var audResult =
        exchangeRateService.getExchangeRates(
            TestConstants.CURRENCY_AUD,
            TestConstants.DATE_2024_JAN_02,
            TestConstants.DATE_2024_JAN_02);

    // Assert - All have USD as base
    assertThat(eurResult.get(0).baseCurrency().getCurrencyCode()).isEqualTo("USD");
    assertThat(thbResult.get(0).baseCurrency().getCurrencyCode()).isEqualTo("USD");
    assertThat(audResult.get(0).baseCurrency().getCurrencyCode()).isEqualTo("USD");

    // Assert - Each has correct target currency
    assertThat(eurResult.get(0).targetCurrency().getCurrencyCode()).isEqualTo("EUR");
    assertThat(thbResult.get(0).targetCurrency().getCurrencyCode()).isEqualTo("THB");
    assertThat(audResult.get(0).targetCurrency().getCurrencyCode()).isEqualTo("AUD");
  }
}
