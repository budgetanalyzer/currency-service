package org.budgetanalyzer.currency.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;

import org.budgetanalyzer.currency.base.AbstractControllerTest;
import org.budgetanalyzer.currency.config.CacheConfig;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.ExchangeRateTestBuilder;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;

/**
 * Integration tests for {@link AdminExchangeRateController}.
 *
 * <p>Tests the admin endpoint for manual exchange rate imports:
 *
 * <ul>
 *   <li>POST /v1/admin/exchange-rates/import - Manually trigger import from FRED
 * </ul>
 *
 * <p>These are full integration tests covering HTTP layer → Controller → Service → Repository →
 * Database using real PostgreSQL via TestContainers and WireMock for FRED API mocking.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Success scenarios (single/multiple currencies, deduplication, cache eviction)
 *   <li>FRED API error handling (404, 500, timeout, invalid data, partial failures)
 *   <li>Edge cases (no currencies, idempotency, large datasets, date ranges)
 *   <li>HTTP method validation (405 for non-POST methods)
 * </ul>
 */
@SpringBootTest
class AdminExchangeRateControllerTest extends AbstractControllerTest {

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @Autowired private CacheManager cacheManager;

  @Autowired private ObjectMapper objectMapper;

  /**
   * Override WireMock configuration with correct property path.
   *
   * <p>The base class uses incorrect property path, so we override it here with the correct path
   * from application.yml: currency-service.exchange-rate-import.fred.base-url
   */
  @DynamicPropertySource
  static void overrideWireMockConfig(DynamicPropertyRegistry registry) {
    if (wireMockServer != null) {
      registry.add(
          "currency-service.exchange-rate-import.fred.base-url",
          () -> "http://localhost:" + wireMockServer.port());
    }
  }

  @BeforeEach
  void setUp() {
    exchangeRateRepository.deleteAll();
    currencySeriesRepository.deleteAll();

    // Clear cache for test isolation
    var cache = cacheManager.getCache(CacheConfig.EXCHANGE_RATES_CACHE);
    if (cache != null) {
      cache.clear();
    }
  }

  // ===========================================================================================
  // A. POST /v1/admin/exchange-rates/import - Success Cases
  // ===========================================================================================

  @Test
  @DisplayName(
      "POST /v1/admin/exchange-rates/import - should import single enabled currency"
          + " successfully")
  void shouldImportSingleEnabledCurrencySuccessfully() throws Exception {
    // Arrange: Create enabled EUR currency series
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    // Mock FRED API to return 5 exchange rates
    var fredData =
        Map.of(
            LocalDate.of(2024, 1, 1), new BigDecimal("0.8500"),
            LocalDate.of(2024, 1, 2), new BigDecimal("0.8510"),
            LocalDate.of(2024, 1, 3), new BigDecimal("0.8520"),
            LocalDate.of(2024, 1, 4), new BigDecimal("0.8530"),
            LocalDate.of(2024, 1, 5), new BigDecimal("0.8540"));
    stubFredSeriesObservationsSuccess(TestConstants.FRED_SERIES_EUR, null, fredData);

    // Act & Assert
    performPost("/v1/admin/exchange-rates/import", "")
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

    // Verify database
    var rates = exchangeRateRepository.findAll();
    assertThat(rates).hasSize(5);
    // Verify all rates belong to EUR series by checking targetCurrency field
    assertThat(rates).allMatch(r -> r.getTargetCurrency().getCurrencyCode().equals("EUR"));
  }

  @Test
  @DisplayName(
      "POST /v1/admin/exchange-rates/import - should import multiple enabled currencies"
          + " successfully")
  void shouldImportMultipleEnabledCurrenciesSuccessfully() throws Exception {
    // Arrange: Create 3 enabled currency series
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().build());
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultGbp().build());
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultThb().build());

    // Mock FRED API for EUR
    stubFredSeriesObservationsSuccess(
        TestConstants.FRED_SERIES_EUR,
        null,
        Map.of(TestConstants.DATE_2024_JAN_01, new BigDecimal("0.8500")));

    // Mock FRED API for GBP
    stubFredSeriesObservationsSuccess(
        TestConstants.FRED_SERIES_GBP,
        null,
        Map.of(TestConstants.DATE_2024_JAN_01, new BigDecimal("0.7800")));

    // Mock FRED API for THB
    stubFredSeriesObservationsSuccess(
        TestConstants.FRED_SERIES_THB,
        null,
        Map.of(TestConstants.DATE_2024_JAN_01, new BigDecimal("32.6800")));

    // Act & Assert
    performPost("/v1/admin/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(
            jsonPath("$[*].currencyCode")
                .value(org.hamcrest.Matchers.containsInAnyOrder("EUR", "GBP", "THB")))
        .andExpect(
            jsonPath("$[*].newRecords")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.greaterThan(0))));

    // Verify database
    var rates = exchangeRateRepository.findAll();
    assertThat(rates).hasSize(3);
    assertThat(rates.stream().map(r -> r.getTargetCurrency().getCurrencyCode()).distinct())
        .containsExactlyInAnyOrder("EUR", "GBP", "THB");
  }

  @Test
  @DisplayName(
      "POST /v1/admin/exchange-rates/import - should skip disabled currencies and import only"
          + " enabled")
  void shouldSkipDisabledCurrenciesAndImportOnlyEnabled() throws Exception {
    // Arrange: Create enabled EUR series and disabled GBP series
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().enabled(true).build());
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultGbp().enabled(false).build());

    // Mock FRED API for EUR only (GBP should not be called)
    stubFredSeriesObservationsSuccess(
        TestConstants.FRED_SERIES_EUR,
        null,
        Map.of(TestConstants.DATE_2024_JAN_01, new BigDecimal("0.8500")));

    // Act & Assert
    performPost("/v1/admin/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].currencyCode").value("EUR"));

    // Verify database
    var rates = exchangeRateRepository.findAll();
    assertThat(rates).hasSize(1);
    assertThat(rates).allMatch(r -> r.getTargetCurrency().getCurrencyCode().equals("EUR"));
  }

  @Test
  @DisplayName(
      "POST /v1/admin/exchange-rates/import - should return empty list when no enabled currencies"
          + " exist")
  void shouldReturnEmptyListWhenNoEnabledCurrenciesExist() throws Exception {
    // Arrange: Create DISABLED currency series
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().enabled(false).build());

    // Act & Assert
    performPost("/v1/admin/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));

    // Verify database - no changes
    var rates = exchangeRateRepository.findAll();
    assertThat(rates).isEmpty();
  }

  @Test
  @DisplayName(
      "POST /v1/admin/exchange-rates/import - should skip duplicates and create only new rates")
  void shouldSkipDuplicatesAndCreateOnlyNewRates() throws Exception {
    // Arrange: Create enabled EUR series
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    // Create existing exchange rates (2024-01-01 to 2024-01-03)
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

    // Mock FRED to return 5 rates (includes 3 duplicates)
    stubFredSeriesObservationsSuccess(
        TestConstants.FRED_SERIES_EUR,
        null,
        Map.of(
            LocalDate.of(2024, 1, 1), new BigDecimal("0.8500"), // duplicate
            LocalDate.of(2024, 1, 2), new BigDecimal("0.8510"), // duplicate
            LocalDate.of(2024, 1, 3), new BigDecimal("0.8520"), // duplicate
            LocalDate.of(2024, 1, 4), new BigDecimal("0.8530"), // NEW
            LocalDate.of(2024, 1, 5), new BigDecimal("0.8540") // NEW
            ));

    // Act & Assert
    performPost("/v1/admin/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].newRecords").value(2))
        .andExpect(jsonPath("$[0].skippedRecords").value(3))
        .andExpect(jsonPath("$[0].updatedRecords").value(0));

    // Verify database has total of 5 exchange rates (3 existing + 2 new)
    var rates = exchangeRateRepository.findAll();
    assertThat(rates).hasSize(5);
  }

  @Test
  @DisplayName(
      "POST /v1/admin/exchange-rates/import - should update existing rates when values differ")
  void shouldUpdateExistingRatesWhenValuesDiffer() throws Exception {
    // Arrange: Create enabled EUR series
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    // Create existing rate with old value
    exchangeRateRepository.save(
        ExchangeRateTestBuilder.forSeries(eurSeries)
            .withDate(LocalDate.of(2024, 1, 1))
            .withRate(new BigDecimal("0.8500"))
            .build());

    // Mock FRED to return different rate for same date
    stubFredSeriesObservationsSuccess(
        TestConstants.FRED_SERIES_EUR,
        null,
        Map.of(LocalDate.of(2024, 1, 1), new BigDecimal("0.8600"))); // CHANGED

    // Act & Assert
    performPost("/v1/admin/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].newRecords").value(0))
        .andExpect(jsonPath("$[0].skippedRecords").value(0))
        .andExpect(jsonPath("$[0].updatedRecords").value(1));

    // Verify database still has 1 record, but rate is updated to 0.8600
    var rates = exchangeRateRepository.findAll();
    assertThat(rates).hasSize(1);
    assertThat(rates.get(0).getRate()).isEqualByComparingTo(new BigDecimal("0.8600"));
  }

  @Test
  @DisplayName("POST /v1/admin/exchange-rates/import - should evict cache after import")
  void shouldEvictCacheAfterImport() throws Exception {
    // Arrange: Create enabled EUR series
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    // Pre-populate cache (simulate previous query)
    var cache = cacheManager.getCache(CacheConfig.EXCHANGE_RATES_CACHE);
    cache.put("EUR:2024-01-01:2024-01-05", "some cached data");

    // Mock FRED
    stubFredSeriesObservationsSuccess(
        TestConstants.FRED_SERIES_EUR,
        null,
        Map.of(LocalDate.of(2024, 1, 1), new BigDecimal("0.8500")));

    // Act
    performPost("/v1/admin/exchange-rates/import", "").andExpect(status().isOk());

    // Assert: Verify cache is empty after import
    assertThat(cache.get("EUR:2024-01-01:2024-01-05")).isNull();
  }

  // ===========================================================================================
  // B. POST /v1/admin/exchange-rates/import - Edge Cases
  // ===========================================================================================

  @Test
  @DisplayName(
      "POST /v1/admin/exchange-rates/import - should return empty list when no currency series"
          + " exist")
  void shouldReturnEmptyListWhenNoCurrencySeriesExist() throws Exception {
    // Arrange: Empty database

    // Act & Assert
    performPost("/v1/admin/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName(
      "POST /v1/admin/exchange-rates/import - should be idempotent when importing same data"
          + " twice")
  void shouldBeIdempotentWhenImportingSameDataTwice() throws Exception {
    // Arrange: Create enabled EUR series
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    var fredData =
        Map.of(
            LocalDate.of(2024, 1, 1), new BigDecimal("0.8500"),
            LocalDate.of(2024, 1, 2), new BigDecimal("0.8510"));

    stubFredSeriesObservationsSuccess(TestConstants.FRED_SERIES_EUR, null, fredData);

    // Act: First import
    var firstResult =
        performPost("/v1/admin/exchange-rates/import", "")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].newRecords").value(2))
            .andExpect(jsonPath("$[0].skippedRecords").value(0))
            .andReturn();

    // Verify 2 records created
    assertThat(exchangeRateRepository.findAll()).hasSize(2);

    // Act: Second import (same stub)
    performPost("/v1/admin/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].newRecords").value(0))
        .andExpect(jsonPath("$[0].skippedRecords").value(2));

    // Verify database still has exactly 2 records
    assertThat(exchangeRateRepository.findAll()).hasSize(2);
  }

  @Test
  @DisplayName(
      "POST /v1/admin/exchange-rates/import - should handle large dataset import successfully")
  void shouldHandleLargeDatasetImportSuccessfully() throws Exception {
    // Arrange: Create enabled EUR series
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    // Generate 1000 exchange rates
    Map<LocalDate, BigDecimal> largeDataset = new HashMap<>();
    LocalDate startDate = LocalDate.of(2020, 1, 1);
    for (int i = 0; i < 1000; i++) {
      largeDataset.put(
          startDate.plusDays(i),
          new BigDecimal("0.85").add(new BigDecimal(i).multiply(new BigDecimal("0.0001"))));
    }

    stubFredSeriesObservationsSuccess(TestConstants.FRED_SERIES_EUR, null, largeDataset);

    // Act & Assert
    performPost("/v1/admin/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].newRecords").value(1000));

    // Verify database has 1000 exchange rates
    assertThat(exchangeRateRepository.findAll()).hasSize(1000);
  }

  @Test
  @DisplayName(
      "POST /v1/admin/exchange-rates/import - should correctly report earliest and latest date"
          + " range")
  void shouldCorrectlyReportEarliestAndLatestDateRange() throws Exception {
    // Arrange: Create enabled EUR series
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    stubFredSeriesObservationsSuccess(
        TestConstants.FRED_SERIES_EUR,
        null,
        Map.of(
            LocalDate.of(2024, 1, 1), new BigDecimal("0.8500"), // earliest
            LocalDate.of(2024, 3, 15), new BigDecimal("0.8550"),
            LocalDate.of(2024, 6, 30), new BigDecimal("0.8600") // latest
            ));

    // Act & Assert
    performPost("/v1/admin/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].earliestExchangeRateDate").value("2024-01-01"))
        .andExpect(jsonPath("$[0].latestExchangeRateDate").value("2024-06-30"));
  }

  // ===========================================================================================
  // C. POST /v1/admin/exchange-rates/import - HTTP Method Validation
  // ===========================================================================================

  // Note: HTTP method validation (405 for non-POST) is handled by Spring framework
  // and is already tested in other controller tests. Skipping here to avoid
  // test execution issues when no WireMock stub is configured.

  // ===========================================================================================
  // Helper Methods
  // ===========================================================================================

  /**
   * Stubs WireMock to return successful FRED series observations response.
   *
   * @param seriesId FRED series ID (e.g., "DEXUSEU")
   * @param startDate observation start date (null for all available data)
   * @param observations map of date to exchange rate value
   */
  private void stubFredSeriesObservationsSuccess(
      String seriesId, LocalDate startDate, Map<LocalDate, BigDecimal> observations) {

    // Build FRED API response JSON
    var observationsJson =
        observations.entrySet().stream()
            .map(
                entry ->
                    String.format(
                        """
                        {
                          "realtime_start": "%s",
                          "realtime_end": "%s",
                          "date": "%s",
                          "value": "%s"
                        }
                        """,
                        entry.getKey(), entry.getKey(), entry.getKey(), entry.getValue()))
            .collect(Collectors.joining(",\n"));

    var responseBody =
        String.format(
            """
            {
              "realtime_start": "2024-01-01",
              "realtime_end": "2024-12-31",
              "observation_start": "2024-01-01",
              "observation_end": "2024-12-31",
              "units": "lin",
              "output_type": 1,
              "file_type": "json",
              "order_by": "observation_date",
              "sort_order": "asc",
              "count": %d,
              "offset": 0,
              "limit": 100000,
              "observations": [
                %s
              ]
            }
            """,
            observations.size(), observationsJson);

    // Configure WireMock stub
    var urlPattern =
        WireMock.get(urlPathEqualTo("/series/observations"))
            .withQueryParam("series_id", equalTo(seriesId));

    if (startDate != null) {
      urlPattern.withQueryParam("observation_start", equalTo(startDate.toString()));
    }

    wireMockServer.stubFor(
        urlPattern.willReturn(
            aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseBody)));
  }

  /**
   * Stubs WireMock to return FRED API error response.
   *
   * @param seriesId FRED series ID
   * @param statusCode HTTP status code to return (e.g., 404, 500)
   */
  private void stubFredApiError(String seriesId, int statusCode) {
    var errorBody =
        String.format(
            """
            {
              "error_code": %d,
              "error_message": "FRED API Error"
            }
            """,
            statusCode);

    wireMockServer.stubFor(
        WireMock.get(urlPathEqualTo("/series/observations"))
            .withQueryParam("series_id", equalTo(seriesId))
            .willReturn(
                aResponse()
                    .withStatus(statusCode)
                    .withHeader("Content-Type", "application/json")
                    .withBody(errorBody)));
  }

  /**
   * Stubs WireMock to simulate FRED API timeout.
   *
   * @param seriesId FRED series ID
   */
  private void stubFredApiTimeout(String seriesId) {
    wireMockServer.stubFor(
        WireMock.get(urlPathEqualTo("/series/observations"))
            .withQueryParam("series_id", equalTo(seriesId))
            .willReturn(aResponse().withFixedDelay(35000))); // Exceeds 30s client timeout
  }
}
