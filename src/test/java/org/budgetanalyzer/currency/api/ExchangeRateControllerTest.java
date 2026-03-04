package org.budgetanalyzer.currency.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;

import org.budgetanalyzer.currency.base.AbstractControllerTest;
import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.ExchangeRateTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.service.security.test.JwtTestBuilder;

/**
 * Integration tests for {@link ExchangeRateController}.
 *
 * <p>Tests all endpoints for exchange rate management:
 *
 * <ul>
 *   <li>GET /v1/exchange-rates - Query exchange rates by currency and date range
 *   <li>POST /v1/exchange-rates/import - Manually trigger import from FRED
 * </ul>
 *
 * <p>These are full integration tests covering HTTP layer → Controller → Service → Repository →
 * Database using real PostgreSQL via TestContainers and WireMock for FRED API mocking.
 */
class ExchangeRateControllerTest extends AbstractControllerTest {

  @Autowired private CurrencySeriesRepository currencySeriesRepository;
  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @BeforeEach
  void setUp() {
    exchangeRateRepository.deleteAll();
    currencySeriesRepository.deleteAll();
    setCustomJwt(currenciesReadJwt());
  }

  // ===========================================================================================
  // JWT Helpers
  // ===========================================================================================

  private static Jwt currenciesReadJwt() {
    return JwtTestBuilder.user("usr_reader").withPermissions("currencies:read").build();
  }

  private static Jwt currenciesWriteJwt() {
    return JwtTestBuilder.user("usr_writer")
        .withPermissions("currencies:read", "currencies:write")
        .build();
  }

  private static Jwt noPermissionsJwt() {
    return JwtTestBuilder.user("usr_noperms").withPermissions("transactions:read").build();
  }

  // ===========================================================================================
  // A. Authorization Tests
  // ===========================================================================================

  @Test
  void shouldReturn401WhenUnauthenticatedUserTriesToGetExchangeRates() throws Exception {
    performGetUnauthenticated("/v1/exchange-rates?targetCurrency=EUR")
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldReturn401WhenUnauthenticatedUserTriesToImport() throws Exception {
    performPostUnauthenticated("/v1/exchange-rates/import", "")
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldReturn403WhenUserWithoutCurrenciesReadTriesToGetExchangeRates() throws Exception {
    performGetWithJwt("/v1/exchange-rates?targetCurrency=EUR", noPermissionsJwt())
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldReturn403WhenUserWithOnlyReadTriesToImport() throws Exception {
    performPostWithJwt("/v1/exchange-rates/import", "", currenciesReadJwt())
        .andExpect(status().isForbidden());
  }

  // ===========================================================================================
  // B. GET /v1/exchange-rates - Happy Path Tests
  // ===========================================================================================

  @Test
  void shouldReturnExchangeRatesForValidDateRange() throws Exception {
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 1, 10),
        TestConstants.RATE_EUR_USD);

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
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 1, 31),
        TestConstants.RATE_EUR_USD);

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
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 1, 31),
        TestConstants.RATE_EUR_USD);

    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-22")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(10))
        .andExpect(jsonPath("$[0].date").value("2024-01-22"))
        .andExpect(jsonPath("$[9].date").value("2024-01-31"));
  }

  @Test
  void shouldReturnExchangeRatesWithoutDateParameters() throws Exception {
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 1, 10),
        TestConstants.RATE_EUR_USD);

    performGet("/v1/exchange-rates?targetCurrency=EUR")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(10))
        .andExpect(jsonPath("$[*].targetCurrency").value(everyItem(is("EUR"))));
  }

  @Test
  void shouldReturnGapFilledExchangeRates() throws Exception {
    saveWeekdayRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 1, 10),
        TestConstants.RATE_EUR_USD);

    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-01&endDate=2024-01-10")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(10))
        .andExpect(jsonPath("$[*].targetCurrency").value(everyItem(is("EUR"))))
        .andExpect(jsonPath("$[5].date").value("2024-01-06"))
        .andExpect(jsonPath("$[6].date").value("2024-01-07"))
        .andExpect(jsonPath("$[5].publishedDate").value("2024-01-05"))
        .andExpect(jsonPath("$[6].publishedDate").value("2024-01-05"));
  }

  // ===========================================================================================
  // C. GET /v1/exchange-rates - Validation Error Tests (400 Bad Request)
  // ===========================================================================================

  @Test
  void shouldReturn400WhenTargetCurrencyIsMissing() throws Exception {
    performGet("/v1/exchange-rates?startDate=2024-01-01&endDate=2024-12-31")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"));
  }

  @Test
  void shouldReturn400WhenTargetCurrencyIsInvalid() throws Exception {
    performGet("/v1/exchange-rates?targetCurrency=INVALID&startDate=2024-01-01")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"));
  }

  @Test
  void shouldReturn400WhenStartDateIsAfterEndDate() throws Exception {
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-12-31&endDate=2024-01-01")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"));
  }

  @Test
  void shouldReturn400ForInvalidDateFormat() throws Exception {
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-13-01")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"));
  }

  @Test
  void shouldReturn400ForMalformedDateFormat() throws Exception {
    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=not-a-date")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"));
  }

  // ===========================================================================================
  // D. GET /v1/exchange-rates - Business Error Tests (422 Unprocessable Entity)
  // ===========================================================================================

  @Test
  void shouldReturn422WhenNoCurrencySeriesExists() throws Exception {
    CurrencySeries series = createSeriesForCurrency("ZAR");
    currencySeriesRepository.save(series);

    performGet("/v1/exchange-rates?targetCurrency=ZAR&startDate=2024-01-01&endDate=2024-12-31")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("NO_EXCHANGE_RATE_DATA_AVAILABLE"));
  }

  @Test
  void shouldReturn422WhenCurrencyNotEnabled() throws Exception {
    CurrencySeries series =
        new CurrencySeriesTestBuilder()
            .withCurrencyCode("ZAR")
            .withProviderSeriesId("DEXZAUS")
            .enabled(false)
            .build();
    currencySeriesRepository.save(series);

    performGet("/v1/exchange-rates?targetCurrency=ZAR&startDate=2024-01-01&endDate=2024-12-31")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("CURRENCY_NOT_ENABLED"));
  }

  @Test
  void shouldReturn422WhenStartDateOutOfRange() throws Exception {
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 3),
        LocalDate.of(2024, 12, 31),
        TestConstants.RATE_EUR_USD);

    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=1900-01-01&endDate=2024-01-31")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("START_DATE_OUT_OF_RANGE"));
  }

  @Test
  void shouldReturnEmptyArrayWhenNoDataAvailableForDateRange() throws Exception {
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 12, 31),
        TestConstants.RATE_EUR_USD);

    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2030-01-01&endDate=2030-12-31")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  // ===========================================================================================
  // E. GET /v1/exchange-rates - Response Structure Validation
  // ===========================================================================================

  @Test
  void shouldReturnCorrectJsonStructureWithAllFields() throws Exception {
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 2),
        LocalDate.of(2024, 1, 2),
        new BigDecimal("0.8500"));

    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-02&endDate=2024-01-02")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].baseCurrency").isString())
        .andExpect(jsonPath("$[0].targetCurrency").isString())
        .andExpect(jsonPath("$[0].date").isString())
        .andExpect(jsonPath("$[0].rate").isNumber())
        .andExpect(jsonPath("$[0].publishedDate").isString())
        .andExpect(jsonPath("$[0].baseCurrency").value("USD"))
        .andExpect(jsonPath("$[0].targetCurrency").value("EUR"))
        .andExpect(jsonPath("$[0].date").value("2024-01-02"))
        .andExpect(jsonPath("$[0].rate").value(0.85))
        .andExpect(jsonPath("$[0].publishedDate").value("2024-01-02"));
  }

  @Test
  void shouldReturnEmptyArrayWhenNoRatesInRange() throws Exception {
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 12, 31),
        TestConstants.RATE_EUR_USD);

    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2025-01-01&endDate=2025-01-31")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldHandleMultipleCurrencies() throws Exception {
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

    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-01&endDate=2024-01-10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(10))
        .andExpect(jsonPath("$[*].targetCurrency").value(everyItem(is("EUR"))));

    performGet("/v1/exchange-rates?targetCurrency=THB&startDate=2024-01-01&endDate=2024-01-10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(10))
        .andExpect(jsonPath("$[*].targetCurrency").value(everyItem(is("THB"))));
  }

  // ===========================================================================================
  // F. GET /v1/exchange-rates - Edge Cases
  // ===========================================================================================

  @Test
  void shouldHandleLeapYearDate() throws Exception {
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 2, 29),
        LocalDate.of(2024, 2, 29),
        TestConstants.RATE_EUR_USD);

    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-02-29&endDate=2024-02-29")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].date").value("2024-02-29"));
  }

  @Test
  void shouldHandleSameDateForStartAndEnd() throws Exception {
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 2),
        LocalDate.of(2024, 1, 2),
        TestConstants.RATE_EUR_USD);

    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-02&endDate=2024-01-02")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].date").value("2024-01-02"));
  }

  @Test
  void shouldHandleLargeDateRanges() throws Exception {
    saveExchangeRatesForSeries(
        TestConstants.VALID_CURRENCY_EUR,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 12, 31),
        TestConstants.RATE_EUR_USD);

    performGet("/v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-01&endDate=2024-12-31")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(366))
        .andExpect(jsonPath("$[*].targetCurrency").value(everyItem(is("EUR"))));
  }

  // ===========================================================================================
  // G. POST /v1/exchange-rates/import - Success Cases
  // ===========================================================================================

  @Test
  void shouldImportSingleEnabledCurrencySuccessfully() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    var observations =
        List.of(
            new FredApiStubs.Observation("2024-01-01", "0.8500"),
            new FredApiStubs.Observation("2024-01-02", "0.8510"),
            new FredApiStubs.Observation("2024-01-03", "0.8520"),
            new FredApiStubs.Observation("2024-01-04", "0.8530"),
            new FredApiStubs.Observation("2024-01-05", "0.8540"));
    FredApiStubs.stubSuccessWithObservations(TestConstants.FRED_SERIES_EUR, observations);

    performPost("/v1/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].currencyCode").value("EUR"))
        .andExpect(jsonPath("$[0].providerSeriesId").value("DEXUSEU"))
        .andExpect(jsonPath("$[0].newRecords").value(5))
        .andExpect(jsonPath("$[0].updatedRecords").value(0))
        .andExpect(jsonPath("$[0].skippedRecords").value(0))
        .andExpect(jsonPath("$[0].earliestExchangeRateDate").value("2024-01-01"))
        .andExpect(jsonPath("$[0].latestExchangeRateDate").value("2024-01-05"))
        .andExpect(jsonPath("$[0].timestamp").isString());

    var rates = exchangeRateRepository.findAll();
    assertThat(rates).hasSize(5);
    assertThat(rates).allMatch(r -> r.getTargetCurrency().getCurrencyCode().equals("EUR"));
  }

  @Test
  void shouldImportMultipleEnabledCurrenciesSuccessfully() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().build());
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultGbp().build());
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultThb().build());

    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(new FredApiStubs.Observation("2024-01-01", "0.8500")));

    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_GBP,
        List.of(new FredApiStubs.Observation("2024-01-01", "0.7800")));

    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_THB,
        List.of(new FredApiStubs.Observation("2024-01-01", "32.6800")));

    performPost("/v1/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(
            jsonPath("$[*].currencyCode")
                .value(org.hamcrest.Matchers.containsInAnyOrder("EUR", "GBP", "THB")))
        .andExpect(
            jsonPath("$[*].newRecords")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.greaterThan(0))));

    var rates = exchangeRateRepository.findAll();
    assertThat(rates).hasSize(3);
    assertThat(rates.stream().map(r -> r.getTargetCurrency().getCurrencyCode()).distinct())
        .containsExactlyInAnyOrder("EUR", "GBP", "THB");
  }

  @Test
  void shouldSkipDisabledCurrenciesAndImportOnlyEnabled() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().enabled(true).build());
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultGbp().enabled(false).build());

    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(new FredApiStubs.Observation("2024-01-01", "0.8500")));

    performPost("/v1/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].currencyCode").value("EUR"));

    var rates = exchangeRateRepository.findAll();
    assertThat(rates).hasSize(1);
    assertThat(rates).allMatch(r -> r.getTargetCurrency().getCurrencyCode().equals("EUR"));
  }

  @Test
  void shouldReturnEmptyListWhenNoEnabledCurrenciesExist() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().enabled(false).build());

    performPost("/v1/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));

    var rates = exchangeRateRepository.findAll();
    assertThat(rates).isEmpty();
  }

  @Test
  void shouldSkipDuplicatesAndCreateOnlyNewRates() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    exchangeRateRepository.save(
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 1))
            .withRate(new BigDecimal("0.8500"))
            .build());
    exchangeRateRepository.save(
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 2))
            .withRate(new BigDecimal("0.8510"))
            .build());
    exchangeRateRepository.save(
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 3))
            .withRate(new BigDecimal("0.8520"))
            .build());

    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(
            new FredApiStubs.Observation("2024-01-01", "0.8500"),
            new FredApiStubs.Observation("2024-01-02", "0.8510"),
            new FredApiStubs.Observation("2024-01-03", "0.8520"),
            new FredApiStubs.Observation("2024-01-04", "0.8530"),
            new FredApiStubs.Observation("2024-01-05", "0.8540")));

    performPost("/v1/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].newRecords").value(2))
        .andExpect(jsonPath("$[0].skippedRecords").value(3))
        .andExpect(jsonPath("$[0].updatedRecords").value(0));

    var rates = exchangeRateRepository.findAll();
    assertThat(rates).hasSize(5);
  }

  @Test
  void shouldUpdateExistingRatesWhenValuesDiffer() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    exchangeRateRepository.save(
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 1))
            .withRate(new BigDecimal("0.8500"))
            .build());

    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(new FredApiStubs.Observation("2024-01-01", "0.8600")));

    performPost("/v1/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].newRecords").value(0))
        .andExpect(jsonPath("$[0].skippedRecords").value(0))
        .andExpect(jsonPath("$[0].updatedRecords").value(1));

    var rates = exchangeRateRepository.findAll();
    assertThat(rates).hasSize(1);
    assertThat(rates.get(0).getRate()).isEqualByComparingTo(new BigDecimal("0.8600"));
  }

  // ===========================================================================================
  // H. POST /v1/exchange-rates/import - Edge Cases
  // ===========================================================================================

  @Test
  void shouldReturnEmptyListWhenNoCurrencySeriesExist() throws Exception {
    setCustomJwt(currenciesWriteJwt());

    performPost("/v1/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldBeIdempotentWhenImportingSameDataTwice() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    var observations =
        List.of(
            new FredApiStubs.Observation("2024-01-01", "0.8500"),
            new FredApiStubs.Observation("2024-01-02", "0.8510"));

    FredApiStubs.stubSuccessWithObservations(TestConstants.FRED_SERIES_EUR, observations);

    performPost("/v1/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].newRecords").value(2))
        .andExpect(jsonPath("$[0].skippedRecords").value(0))
        .andReturn();

    assertThat(exchangeRateRepository.findAll()).hasSize(2);

    performPost("/v1/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].newRecords").value(0))
        .andExpect(jsonPath("$[0].skippedRecords").value(2));

    assertThat(exchangeRateRepository.findAll()).hasSize(2);
  }

  @Test
  void shouldHandleLargeDatasetImportSuccessfully() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    var observations = new java.util.ArrayList<FredApiStubs.Observation>();
    LocalDate startDate = LocalDate.of(2020, 1, 1);
    for (int i = 0; i < 1000; i++) {
      var rate = new BigDecimal("0.85").add(new BigDecimal(i).multiply(new BigDecimal("0.0001")));
      observations.add(
          new FredApiStubs.Observation(startDate.plusDays(i).toString(), rate.toString()));
    }

    FredApiStubs.stubSuccessWithObservations(TestConstants.FRED_SERIES_EUR, observations);

    performPost("/v1/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].newRecords").value(1000));

    assertThat(exchangeRateRepository.findAll()).hasSize(1000);
  }

  @Test
  void shouldCorrectlyReportEarliestAndLatestDateRange() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(
            new FredApiStubs.Observation("2024-01-01", "0.8500"),
            new FredApiStubs.Observation("2024-03-15", "0.8550"),
            new FredApiStubs.Observation("2024-06-30", "0.8600")));

    performPost("/v1/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].earliestExchangeRateDate").value("2024-01-01"))
        .andExpect(jsonPath("$[0].latestExchangeRateDate").value("2024-06-30"));
  }

  // ===========================================================================================
  // Helper Methods
  // ===========================================================================================

  private void saveExchangeRatesForSeries(
      String currencyCode, LocalDate startDate, LocalDate endDate, BigDecimal rate) {
    var series = createSeriesForCurrency(currencyCode);
    currencySeriesRepository.save(series);

    var rates = ExchangeRateTestBuilder.buildDateRange(series, startDate, endDate, rate);
    exchangeRateRepository.saveAll(rates);
  }

  private void saveWeekdayRatesForSeries(
      String currencyCode, LocalDate startDate, LocalDate endDate, BigDecimal rate) {
    var series = createSeriesForCurrency(currencyCode);
    currencySeriesRepository.save(series);

    var rates = ExchangeRateTestBuilder.buildWeekdaysOnly(series, startDate, endDate, rate);
    exchangeRateRepository.saveAll(rates);
  }

  private CurrencySeries createSeriesForCurrency(String currencyCode) {
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
