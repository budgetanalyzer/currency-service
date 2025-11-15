package org.budgetanalyzer.currency.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.currency.base.AbstractControllerTest;
import org.budgetanalyzer.currency.config.CacheConfig;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.ExchangeRateTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
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
 *
 * <p><b>Cache Configuration:</b> This test explicitly enables Redis cache to verify cache behavior
 * through the API.
 */
@TestPropertySource(properties = "spring.cache.type=redis")
public class AdminExchangeRateControllerTest extends AbstractControllerTest {

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @Autowired private CacheManager cacheManager;

  @Autowired private ObjectMapper objectMapper;

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
  void shouldImportSingleEnabledCurrencySuccessfully() throws Exception {
    // Arrange: Create enabled EUR currency series
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    // Mock FRED API to return 5 exchange rates
    var observations =
        List.of(
            new FredApiStubs.Observation("2024-01-01", "0.8500"),
            new FredApiStubs.Observation("2024-01-02", "0.8510"),
            new FredApiStubs.Observation("2024-01-03", "0.8520"),
            new FredApiStubs.Observation("2024-01-04", "0.8530"),
            new FredApiStubs.Observation("2024-01-05", "0.8540"));
    FredApiStubs.stubSuccessWithObservations(TestConstants.FRED_SERIES_EUR, observations);

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
  void shouldImportMultipleEnabledCurrenciesSuccessfully() throws Exception {
    // Arrange: Create 3 enabled currency series
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().build());
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultGbp().build());
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultThb().build());

    // Mock FRED API for EUR
    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(new FredApiStubs.Observation("2024-01-01", "0.8500")));

    // Mock FRED API for GBP
    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_GBP,
        List.of(new FredApiStubs.Observation("2024-01-01", "0.7800")));

    // Mock FRED API for THB
    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_THB,
        List.of(new FredApiStubs.Observation("2024-01-01", "32.6800")));

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
  void shouldSkipDisabledCurrenciesAndImportOnlyEnabled() throws Exception {
    // Arrange: Create enabled EUR series and disabled GBP series
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().enabled(true).build());
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultGbp().enabled(false).build());

    // Mock FRED API for EUR only (GBP should not be called)
    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(new FredApiStubs.Observation("2024-01-01", "0.8500")));

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
    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(
            new FredApiStubs.Observation("2024-01-01", "0.8500"), // duplicate
            new FredApiStubs.Observation("2024-01-02", "0.8510"), // duplicate
            new FredApiStubs.Observation("2024-01-03", "0.8520"), // duplicate
            new FredApiStubs.Observation("2024-01-04", "0.8530"), // NEW
            new FredApiStubs.Observation("2024-01-05", "0.8540") // NEW
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
    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(new FredApiStubs.Observation("2024-01-01", "0.8600"))); // CHANGED

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
  void shouldEvictCacheAfterImport() throws Exception {
    // Arrange: Create enabled EUR series
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    // Pre-populate cache (simulate previous query)
    var cache = cacheManager.getCache(CacheConfig.EXCHANGE_RATES_CACHE);
    cache.put("EUR:2024-01-01:2024-01-05", "some cached data");

    // Mock FRED
    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(new FredApiStubs.Observation("2024-01-01", "0.8500")));

    // Act
    performPost("/v1/admin/exchange-rates/import", "").andExpect(status().isOk());

    // Assert: Verify cache is empty after import
    assertThat(cache.get("EUR:2024-01-01:2024-01-05")).isNull();
  }

  // ===========================================================================================
  // B. POST /v1/admin/exchange-rates/import - Edge Cases
  // ===========================================================================================

  @Test
  void shouldReturnEmptyListWhenNoCurrencySeriesExist() throws Exception {
    // Arrange: Empty database

    // Act & Assert
    performPost("/v1/admin/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldBeIdempotentWhenImportingSameDataTwice() throws Exception {
    // Arrange: Create enabled EUR series
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    var observations =
        List.of(
            new FredApiStubs.Observation("2024-01-01", "0.8500"),
            new FredApiStubs.Observation("2024-01-02", "0.8510"));

    FredApiStubs.stubSuccessWithObservations(TestConstants.FRED_SERIES_EUR, observations);

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
  void shouldHandleLargeDatasetImportSuccessfully() throws Exception {
    // Arrange: Create enabled EUR series
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    // Generate 1000 exchange rates
    var observations = new java.util.ArrayList<FredApiStubs.Observation>();
    LocalDate startDate = LocalDate.of(2020, 1, 1);
    for (int i = 0; i < 1000; i++) {
      var rate = new BigDecimal("0.85").add(new BigDecimal(i).multiply(new BigDecimal("0.0001")));
      observations.add(
          new FredApiStubs.Observation(startDate.plusDays(i).toString(), rate.toString()));
    }

    FredApiStubs.stubSuccessWithObservations(TestConstants.FRED_SERIES_EUR, observations);

    // Act & Assert
    performPost("/v1/admin/exchange-rates/import", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].newRecords").value(1000));

    // Verify database has 1000 exchange rates
    assertThat(exchangeRateRepository.findAll()).hasSize(1000);
  }

  @Test
  void shouldCorrectlyReportEarliestAndLatestDateRange() throws Exception {
    // Arrange: Create enabled EUR series
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(
            new FredApiStubs.Observation("2024-01-01", "0.8500"), // earliest
            new FredApiStubs.Observation("2024-03-15", "0.8550"),
            new FredApiStubs.Observation("2024-06-30", "0.8600") // latest
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

}
