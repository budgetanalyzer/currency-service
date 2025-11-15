package org.budgetanalyzer.currency.api;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import org.budgetanalyzer.currency.base.AbstractControllerTest;
import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.ExchangeRateTestBuilder;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;

/**
 * Integration tests for {@link ExchangeRateController}.
 *
 * <p>Tests the read-only public endpoint for querying exchange rates:
 *
 * <ul>
 *   <li>GET /v1/exchange-rates - Query exchange rates by currency and date range
 * </ul>
 *
 * <p>These are full integration tests covering HTTP layer → Controller → Service → Repository →
 * Database using real PostgreSQL via TestContainers.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Happy paths (with/without date parameters, gap-filling)
 *   <li>Validation errors (missing params, invalid formats, date range validation)
 *   <li>Business errors (no data available, date out of range)
 *   <li>Response structure validation (JSON schema, data types)
 *   <li>Edge cases (leap years, multiple currencies, empty results)
 * </ul>
 */
public class ExchangeRateControllerTest extends AbstractControllerTest {

  @Autowired private CurrencySeriesRepository currencySeriesRepository;
  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @BeforeEach
  void setUp() {
    exchangeRateRepository.deleteAll();
    currencySeriesRepository.deleteAll();
  }

  // ===========================================================================================
  // A. Happy Path Tests
  // ===========================================================================================

  @Test
  void shouldReturnExchangeRatesForValidDateRange() throws Exception {
    // Setup: Save EUR series + 10 exchange rates for Jan 1-10
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 1, 10),
        TestConstants.RATE_EUR_USD);

    // Execute
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-01&endDate=2024-01-10")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(10))
        .andExpect(jsonPath("$[*].baseCurrency").value(everyItem(is("USD"))))
        .andExpect(jsonPath("$[*].targetCurrency").value(everyItem(is("EUR"))))
        .andExpect(jsonPath("$[0].date").value("2024-01-01"))
        .andExpect(jsonPath("$[9].date").value("2024-01-10"));
  }

  @Test
  void shouldReturnExchangeRatesWithOptionalStartDate() throws Exception {
    // Setup: Save EUR series + rates from Jan 1-31
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 1, 31),
        TestConstants.RATE_EUR_USD);

    // Execute: Query without startDate (should return all rates up to endDate)
    performGet("/v1/exchange-rates?targetCurrency=EUR&endDate=2024-01-10")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(10))
        .andExpect(jsonPath("$[0].date").value("2024-01-01"))
        .andExpect(jsonPath("$[9].date").value("2024-01-10"));
  }

  @Test
  void shouldReturnExchangeRatesWithOptionalEndDate() throws Exception {
    // Setup: Save EUR series + rates from Jan 1-31
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 1, 31),
        TestConstants.RATE_EUR_USD);

    // Execute: Query without endDate (should return all rates from startDate onwards)
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-22")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(10)) // Jan 22-31 = 10 days
        .andExpect(jsonPath("$[0].date").value("2024-01-22"))
        .andExpect(jsonPath("$[9].date").value("2024-01-31"));
  }

  @Test
  void shouldReturnExchangeRatesWithoutDateParameters() throws Exception {
    // Setup: Save EUR series + 10 rates
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 1, 10),
        TestConstants.RATE_EUR_USD);

    // Execute: Query without date params (should return all rates)
    performGet("/v1/exchange-rates?targetCurrency=EUR")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(10))
        .andExpect(jsonPath("$[*].targetCurrency").value(everyItem(is("EUR"))));
  }

  @Test
  void shouldReturnGapFilledExchangeRates() throws Exception {
    // Setup: Save EUR series + rates for weekdays only (Jan 1-5 are Mon-Fri)
    // Note: Jan 1, 2024 is Monday
    saveWeekdayRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 1, 10),
        TestConstants.RATE_EUR_USD);

    // Execute: Query for full date range including weekend
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-01&endDate=2024-01-10")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(10)) // All 10 days including weekend
        .andExpect(jsonPath("$[*].targetCurrency").value(everyItem(is("EUR"))))
        // Verify weekend dates exist (Jan 6-7 are Sat-Sun)
        .andExpect(jsonPath("$[5].date").value("2024-01-06"))
        .andExpect(jsonPath("$[6].date").value("2024-01-07"))
        // Verify gap-filled entries have different publishedDate (Friday Jan 5)
        .andExpect(jsonPath("$[5].publishedDate").value("2024-01-05"))
        .andExpect(jsonPath("$[6].publishedDate").value("2024-01-05"));
  }

  // ===========================================================================================
  // B. Validation Error Tests (400 Bad Request)
  // ===========================================================================================

  @Test
  void shouldReturn400WhenTargetCurrencyIsMissing() throws Exception {
    // Execute: Query without targetCurrency
    performGet("/v1/exchange-rates?startDate=2024-01-01&endDate=2024-12-31")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"));
  }

  @Test
  void shouldReturn400WhenTargetCurrencyIsInvalid() throws Exception {
    // Execute: Query with invalid currency code
    performGet("/v1/exchange-rates?targetCurrency=INVALID&startDate=2024-01-01")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"));
  }

  @Test
  void shouldReturn400WhenStartDateIsAfterEndDate() throws Exception {
    // Execute: Query with startDate after endDate
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-12-31&endDate=2024-01-01")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"));
  }

  @Test
  void shouldReturn400ForInvalidDateFormat() throws Exception {
    // Execute: Query with invalid month (13)
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-13-01")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"));
  }

  @Test
  void shouldReturn400ForMalformedDateFormat() throws Exception {
    // Execute: Query with non-date string
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=not-a-date")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"));
  }

  // ===========================================================================================
  // C. Business Error Tests (422 Unprocessable Entity)
  // ===========================================================================================

  @Test
  void shouldReturn422WhenNoCurrencySeriesExists() throws Exception {
    // Setup: Empty database (no series for ZAR)

    // Execute: Query for currency that doesn't exist in DB
    performGet("/v1/exchange-rates?targetCurrency=ZAR&startDate=2024-01-01&endDate=2024-12-31")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("NO_EXCHANGE_RATE_DATA_AVAILABLE"));
  }

  @Test
  void shouldReturn422WhenStartDateOutOfRange() throws Exception {
    // Setup: Save EUR series + rates starting from Jan 3, 2024
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 3),
        LocalDate.of(2024, 12, 31),
        TestConstants.RATE_EUR_USD);

    // Execute: Query with startDate before available data
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=1900-01-01&endDate=2024-01-31")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("START_DATE_OUT_OF_RANGE"));
  }

  @Test
  void shouldReturnEmptyArrayWhenNoDataAvailableForDateRange() throws Exception {
    // Setup: Save EUR series + rates only up to 2024
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 12, 31),
        TestConstants.RATE_EUR_USD);

    // Execute: Query for future date range (2030) - should return empty array, not error
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2030-01-01&endDate=2030-12-31")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  // ===========================================================================================
  // D. Response Structure Validation
  // ===========================================================================================

  @Test
  void shouldReturnCorrectJsonStructureWithAllFields() throws Exception {
    // Setup: Save single EUR rate for Jan 2, 2024 with rate 0.8500
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 2),
        LocalDate.of(2024, 1, 2),
        new BigDecimal("0.8500"));

    // Execute
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-02&endDate=2024-01-02")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        // Validate data types
        .andExpect(jsonPath("$[0].baseCurrency").isString())
        .andExpect(jsonPath("$[0].targetCurrency").isString())
        .andExpect(jsonPath("$[0].date").isString())
        .andExpect(jsonPath("$[0].rate").isNumber())
        .andExpect(jsonPath("$[0].publishedDate").isString())
        // Validate actual values
        .andExpect(jsonPath("$[0].baseCurrency").value("USD"))
        .andExpect(jsonPath("$[0].targetCurrency").value("EUR"))
        .andExpect(jsonPath("$[0].date").value("2024-01-02"))
        .andExpect(jsonPath("$[0].rate").value(0.85))
        .andExpect(jsonPath("$[0].publishedDate").value("2024-01-02"));
  }

  @Test
  void shouldReturnEmptyArrayWhenNoRatesInRange() throws Exception {
    // Setup: Save EUR series + rates for 2024 only
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 12, 31),
        TestConstants.RATE_EUR_USD);

    // Execute: Query for 2025 (no data)
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2025-01-01&endDate=2025-01-31")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldHandleMultipleCurrencies() throws Exception {
    // Setup: Save both EUR and THB series with rates
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 1, 10),
        TestConstants.RATE_EUR_USD);
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_THB,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 1, 10),
        TestConstants.RATE_THB_USD);

    // Execute: Query EUR
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-01&endDate=2024-01-10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(10))
        .andExpect(jsonPath("$[*].targetCurrency").value(everyItem(is("EUR"))));

    // Execute: Query THB
    performGet("/v1/exchange-rates?targetCurrency=THB&startDate=2024-01-01&endDate=2024-01-10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(10))
        .andExpect(jsonPath("$[*].targetCurrency").value(everyItem(is("THB"))));
  }

  // ===========================================================================================
  // E. Edge Cases
  // ===========================================================================================

  @Test
  void shouldHandleLeapYearDate() throws Exception {
    // Setup: Save EUR rate for Feb 29, 2024 (leap year)
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 2, 29),
        LocalDate.of(2024, 2, 29),
        TestConstants.RATE_EUR_USD);

    // Execute
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-02-29&endDate=2024-02-29")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].date").value("2024-02-29"));
  }

  @Test
  void shouldHandleSameDateForStartAndEnd() throws Exception {
    // Setup: Save EUR rate for Jan 2
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 2),
        LocalDate.of(2024, 1, 2),
        TestConstants.RATE_EUR_USD);

    // Execute: Query with same start and end date
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-02&endDate=2024-01-02")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].date").value("2024-01-02"));
  }

  @Test
  void shouldHandleLargeDateRanges() throws Exception {
    // Setup: Save EUR series + rates for full year 2024 (365 days)
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 12, 31),
        TestConstants.RATE_EUR_USD);

    // Execute: Query for entire year
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-01&endDate=2024-12-31")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(366)) // 2024 is a leap year
        .andExpect(jsonPath("$[*].targetCurrency").value(everyItem(is("EUR"))));
  }

  // ===========================================================================================
  // Helper Methods
  // ===========================================================================================

  /**
   * Saves a currency series with exchange rates for a date range.
   *
   * @param currencyCode the ISO 4217 currency code
   * @param startDate the start date (inclusive)
   * @param endDate the end date (inclusive)
   * @param rate the exchange rate value for all dates
   */
  private void saveExchangeRatesForSeries(
      String currencyCode, LocalDate startDate, LocalDate endDate, BigDecimal rate) {
    var series = createSeriesForCurrency(currencyCode);
    currencySeriesRepository.save(series);

    var rates = ExchangeRateTestBuilder.buildDateRange(series, startDate, endDate, rate);
    exchangeRateRepository.saveAll(rates);
  }

  /**
   * Saves a currency series with exchange rates for weekdays only.
   *
   * @param currencyCode the ISO 4217 currency code
   * @param startDate the start date (inclusive)
   * @param endDate the end date (inclusive)
   * @param rate the exchange rate value for all dates
   */
  private void saveWeekdayRatesForSeries(
      String currencyCode, LocalDate startDate, LocalDate endDate, BigDecimal rate) {
    var series = createSeriesForCurrency(currencyCode);
    currencySeriesRepository.save(series);

    var rates = ExchangeRateTestBuilder.buildWeekdaysOnly(series, startDate, endDate, rate);
    exchangeRateRepository.saveAll(rates);
  }

  /**
   * Creates a currency series for the given currency code.
   *
   * @param currencyCode the ISO 4217 currency code
   * @return a new CurrencySeries instance with appropriate FRED series ID
   */
  private CurrencySeries createSeriesForCurrency(String currencyCode) {
    var currency = Currency.getInstance(currencyCode);
    return switch (currencyCode) {
      case "EUR" -> CurrencySeriesTestBuilder.defaultEur().build();
      case "THB" -> CurrencySeriesTestBuilder.defaultThb().build();
      case "GBP" -> CurrencySeriesTestBuilder.defaultGbp().build();
      case "JPY" -> CurrencySeriesTestBuilder.defaultJpy().build();
      case "CAD" -> CurrencySeriesTestBuilder.defaultCad().build();
      default ->
          new CurrencySeriesTestBuilder()
              .withCurrencyCode(currencyCode)
              .withProviderSeriesId("DEX" + currencyCode)
              .build();
    };
  }
}
