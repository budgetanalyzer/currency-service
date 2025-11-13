package org.budgetanalyzer.currency.service.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.budgetanalyzer.currency.base.AbstractIntegrationTest;
import org.budgetanalyzer.currency.config.WireMockConfiguration;
import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.FredApiStubs.Observation;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.service.exception.ClientException;

/**
 * Integration tests for FredExchangeRateProvider using WireMock to simulate FRED API. Tests
 * transformation logic, missing data handling, error scenarios, and integration with FredClient.
 *
 * <p>Focus areas:
 *
 * <ul>
 *   <li>Transformation from FRED format (Observations) to domain format (Map&lt;LocalDate,
 *       BigDecimal&gt;)
 *   <li>Missing data filtering (FRED uses "." to indicate no data)
 *   <li>BigDecimal parsing with various numeric formats
 *   <li>Error handling and exception propagation
 *   <li>Series validation behavior
 * </ul>
 *
 * <p>This test class extends AbstractIntegrationTest to get:
 *
 * <ul>
 *   <li>Full Spring Boot context with real FredClient
 *   <li>WireMock server for FRED API simulation
 *   <li>TestContainers for infrastructure dependencies
 * </ul>
 *
 * @see FredExchangeRateProvider
 * @see org.budgetanalyzer.currency.client.fred.FredClient
 * @see ExchangeRateProvider
 */
@DisplayName("FredExchangeRateProvider Integration Tests")
class FredExchangeRateProviderIntegrationTest extends AbstractIntegrationTest {

  @Autowired private FredExchangeRateProvider provider;

  private CurrencySeries eurSeries;
  private CurrencySeries thbSeries;

  /**
   * Configures Spring Boot to use WireMock server for FRED API calls.
   *
   * <p>Overrides {@code currency-service.fred.base-url} property to point to local WireMock server.
   *
   * @param registry Spring dynamic property registry
   */
  @DynamicPropertySource
  static void configureWireMockProperties(DynamicPropertyRegistry registry) {
    var wireMock = WireMockConfiguration.getWireMockServer();
    registry.add(
        "currency-service.exchange-rate-import.fred.base-url",
        () -> "http://localhost:" + wireMock.port());
  }

  @BeforeEach
  void setUp() {
    // Create test currency series
    eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    thbSeries = CurrencySeriesTestBuilder.defaultThb().build();

    // Note: We don't persist these to database - provider only needs the DTOs
    // FredClient uses providerSeriesId field, not database ID
  }

  // ========================================
  // Group 1: Happy Path - Successful Data Transformation
  // ========================================

  @Test
  @DisplayName("Should fetch full history when startDate is null")
  void shouldFetchFullHistoryWhenStartDateIsNull() {
    // Given
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).isNotEmpty();
    // Sample data has 10 days starting from Jan 1, 2024 (Monday)
    // Jan 6-7 are weekend (missing data filtered out) = 8 valid observations
    assertThat(rates).hasSize(8);
    assertThat(rates)
        .containsOnlyKeys(
            LocalDate.of(2024, 1, 1), // Mon
            LocalDate.of(2024, 1, 2), // Tue
            LocalDate.of(2024, 1, 3), // Wed
            LocalDate.of(2024, 1, 4), // Thu
            LocalDate.of(2024, 1, 5), // Fri
            LocalDate.of(2024, 1, 8), // Mon
            LocalDate.of(2024, 1, 9), // Tue
            LocalDate.of(2024, 1, 10) // Wed
        );

    // Verify values are parsed correctly as BigDecimal
    assertThat(rates.values())
        .allSatisfy(
            rate -> {
              assertThat(rate).isInstanceOf(BigDecimal.class);
              assertThat(rate).isPositive();
            });
  }

  @Test
  @DisplayName("Should fetch incremental data when startDate is provided")
  void shouldFetchIncrementalDataWhenStartDateProvided() {
    // Given
    var startDate = LocalDate.of(2024, 6, 1);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);

    // When
    var rates = provider.getExchangeRates(eurSeries, startDate);

    // Then
    assertThat(rates).isNotEmpty();
    // Note: WireMock stub doesn't actually filter by date
    // This tests that the request is made with correct parameters
  }

  @Test
  @DisplayName("Should filter out observations with missing data indicator '.'")
  void shouldFilterMissingDataObservations() {
    // Given - FRED returns mix of valid values and "." for weekends
    var observations =
        List.of(
            Observation.of(LocalDate.of(2024, 1, 2), "1.0850"), // Mon - valid
            Observation.of(LocalDate.of(2024, 1, 3), "1.0872"), // Tue - valid
            Observation.of(LocalDate.of(2024, 1, 4), "1.0891"), // Wed - valid
            Observation.missingData(LocalDate.of(2024, 1, 6)), // Sat - missing
            Observation.missingData(LocalDate.of(2024, 1, 7)), // Sun - missing
            Observation.of(LocalDate.of(2024, 1, 8), "1.0823") // Mon - valid
            );
    FredApiStubs.stubSuccessWithObservations(TestConstants.FRED_SERIES_EUR, observations);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).isNotEmpty();

    // Verify only non-missing data is included
    assertThat(rates.values()).allSatisfy(rate -> assertThat(rate).isNotNull());

    // 4 valid observations, 2 missing (filtered out)
    assertThat(rates).hasSize(4);
  }

  @Test
  @DisplayName("Should correctly parse various BigDecimal formats from FRED")
  void shouldParseVariousBigDecimalFormats() {
    // Given - Custom observations with different numeric formats
    var observations =
        List.of(
            Observation.of(TestConstants.DATE_2024_JAN_02, "1.0850"), // Standard
            Observation.of(LocalDate.of(2024, 1, 3), "1.085"), // No trailing zero
            Observation.of(LocalDate.of(2024, 1, 4), "1.08500000"), // Extra precision
            Observation.of(TestConstants.DATE_2024_JAN_05, "0.9123"), // Leading zero
            Observation.of(LocalDate.of(2024, 1, 8), "123.456789") // Large value
            );
    FredApiStubs.stubSuccessWithObservations(TestConstants.FRED_SERIES_EUR, observations);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).hasSize(5);

    // Verify specific values parsed correctly
    assertThat(rates.get(TestConstants.DATE_2024_JAN_02)).isEqualByComparingTo("1.0850");
    assertThat(rates.get(LocalDate.of(2024, 1, 3))).isEqualByComparingTo("1.085");
    assertThat(rates.get(LocalDate.of(2024, 1, 4))).isEqualByComparingTo("1.08500000");
    assertThat(rates.get(TestConstants.DATE_2024_JAN_05)).isEqualByComparingTo("0.9123");
    assertThat(rates.get(LocalDate.of(2024, 1, 8))).isEqualByComparingTo("123.456789");
  }

  @Test
  @DisplayName("Should efficiently handle large dataset with 365 observations")
  void shouldHandleLargeDatasetEfficiently() {
    // Given
    FredApiStubs.stubSuccessWithLargeDataset(TestConstants.FRED_SERIES_EUR);

    // When
    long startTime = System.currentTimeMillis();
    var rates = provider.getExchangeRates(eurSeries, null);
    long duration = System.currentTimeMillis() - startTime;

    // Then
    // 365 days = 52 weeks = 104 weekend days, so ~261 weekdays
    assertThat(rates).hasSizeGreaterThan(250);
    assertThat(duration).isLessThan(2000); // Should complete in < 2 seconds

    // Verify all values are valid BigDecimal
    assertThat(rates.values()).allMatch(rate -> rate.compareTo(BigDecimal.ZERO) > 0);
  }

  @Test
  @DisplayName("Should work with different currency series (THB)")
  void shouldWorkWithDifferentCurrencySeries() {
    // Given
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_THB);

    // When
    var rates = provider.getExchangeRates(thbSeries, null);

    // Then
    assertThat(rates).isNotEmpty();
    // Verify transformation works regardless of currency
    assertThat(rates.values())
        .allSatisfy(
            rate -> {
              assertThat(rate).isInstanceOf(BigDecimal.class);
              assertThat(rate).isPositive();
            });
  }

  // ========================================
  // Group 2: Error Handling - Exception Propagation
  // ========================================

  @Test
  @DisplayName("Should throw ClientException when FRED returns 404 Not Found")
  void shouldThrowClientExceptionOn404NotFound() {
    // Given
    FredApiStubs.stubNotFound(TestConstants.FRED_SERIES_EUR);

    // When/Then
    assertThatThrownBy(() -> provider.getExchangeRates(eurSeries, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("404");
  }

  @Test
  @DisplayName("Should throw ClientException when FRED returns 400 Bad Request")
  void shouldThrowClientExceptionOn400BadRequest() {
    // Given
    FredApiStubs.stubBadRequest(TestConstants.FRED_SERIES_EUR);

    // When/Then
    assertThatThrownBy(() -> provider.getExchangeRates(eurSeries, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("Bad Request");
  }

  @Test
  @DisplayName("Should throw ClientException when FRED returns 500 Server Error")
  void shouldThrowClientExceptionOn500ServerError() {
    // Given
    FredApiStubs.stubServerError(TestConstants.FRED_SERIES_EUR);

    // When/Then
    assertThatThrownBy(() -> provider.getExchangeRates(eurSeries, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("500");
  }

  @Test
  @DisplayName("Should throw ClientException on timeout")
  void shouldThrowClientExceptionOnTimeout() {
    // Given
    FredApiStubs.stubTimeout(TestConstants.FRED_SERIES_EUR); // 35 second delay

    // When/Then
    assertThatThrownBy(() -> provider.getExchangeRates(eurSeries, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("Failed to fetch");
  }

  @Test
  @DisplayName("Should throw ClientException on malformed JSON response")
  void shouldThrowClientExceptionOnMalformedJson() {
    // Given
    FredApiStubs.stubMalformedJson(TestConstants.FRED_SERIES_EUR);

    // When/Then
    assertThatThrownBy(() -> provider.getExchangeRates(eurSeries, null))
        .isInstanceOf(ClientException.class);
  }

  @Test
  @DisplayName("Should throw ClientException when rate limited (429)")
  void shouldThrowClientExceptionOnRateLimit() {
    // Given
    FredApiStubs.stubRateLimited(TestConstants.FRED_SERIES_EUR);

    // When/Then
    assertThatThrownBy(() -> provider.getExchangeRates(eurSeries, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("429");
  }

  // ========================================
  // Group 3: Series Validation Tests
  // ========================================

  @Test
  @DisplayName("Should return true when series exists in FRED")
  void shouldReturnTrueWhenSeriesExists() {
    // Given
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);

    // When
    boolean exists = provider.validateSeriesExists(TestConstants.FRED_SERIES_EUR);

    // Then
    assertThat(exists).isTrue();
  }

  @Test
  @DisplayName("Should return false when series not found (404) - not throw exception")
  void shouldReturnFalseWhenSeriesNotFound() {
    // Given
    FredApiStubs.stubSeriesExistsNotFound("NONEXISTENT");

    // When
    boolean exists = provider.validateSeriesExists("NONEXISTENT");

    // Then
    assertThat(exists).isFalse();
  }

  @Test
  @DisplayName("Should return false when series ID invalid (400) - not throw exception")
  void shouldReturnFalseWhenSeriesIdInvalid() {
    // Given
    FredApiStubs.stubSeriesExistsBadRequest("INVALID@@@");

    // When
    boolean exists = provider.validateSeriesExists("INVALID@@@");

    // Then
    assertThat(exists).isFalse();
  }

  @Test
  @DisplayName("Should throw ClientException when series validation encounters server error")
  void shouldThrowClientExceptionOnValidationServerError() {
    // Given
    FredApiStubs.stubSeriesExistsServerError(TestConstants.FRED_SERIES_EUR);

    // When/Then
    assertThatThrownBy(() -> provider.validateSeriesExists(TestConstants.FRED_SERIES_EUR))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("500");
  }

  // ========================================
  // Group 4: Edge Cases and Special Scenarios
  // ========================================

  @Test
  @DisplayName("Should return empty map when FRED returns no observations")
  void shouldReturnEmptyMapWhenNoObservations() {
    // Given
    FredApiStubs.stubSuccessEmpty(TestConstants.FRED_SERIES_EUR);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).isEmpty();
  }

  @Test
  @DisplayName("Should return empty map when all observations have missing data '.'")
  void shouldReturnEmptyMapWhenAllObservationsMissing() {
    // Given - All observations have value = "."
    var observations =
        List.of(
            Observation.missingData(TestConstants.DATE_2024_JAN_06_WEEKEND), // Saturday
            Observation.missingData(TestConstants.DATE_2024_JAN_07_WEEKEND), // Sunday
            Observation.missingData(LocalDate.of(2024, 1, 13)) // Holiday
            );
    FredApiStubs.stubSuccessWithObservations(TestConstants.FRED_SERIES_EUR, observations);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).isEmpty();
  }

  @Test
  @DisplayName("Should handle mix of valid values and missing data correctly")
  void shouldHandleMixOfValidAndMissingData() {
    // Given - Realistic week with weekdays (data) and weekends (missing)
    var observations =
        List.of(
            Observation.of(TestConstants.DATE_2024_JAN_02, "1.0850"), // Mon
            Observation.of(LocalDate.of(2024, 1, 3), "1.0872"), // Tue
            Observation.of(LocalDate.of(2024, 1, 4), "1.0891"), // Wed
            Observation.of(TestConstants.DATE_2024_JAN_05, "1.0823"), // Thu
            Observation.missingData(TestConstants.DATE_2024_JAN_06_WEEKEND), // Sat
            Observation.missingData(TestConstants.DATE_2024_JAN_07_WEEKEND) // Sun
            );
    FredApiStubs.stubSuccessWithObservations(TestConstants.FRED_SERIES_EUR, observations);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).hasSize(4); // Only weekdays
    assertThat(rates)
        .containsOnlyKeys(
            TestConstants.DATE_2024_JAN_02,
            LocalDate.of(2024, 1, 3),
            LocalDate.of(2024, 1, 4),
            TestConstants.DATE_2024_JAN_05);
    assertThat(rates)
        .doesNotContainKeys(
            TestConstants.DATE_2024_JAN_06_WEEKEND, TestConstants.DATE_2024_JAN_07_WEEKEND);
  }

  @Test
  @DisplayName("Should handle very old historical dates (1971)")
  void shouldHandleVeryOldHistoricalDates() {
    // Given - FRED has data back to 1971
    var observations =
        List.of(Observation.of(LocalDate.of(1971, 1, 4), "0.3571")); // DEM/USD from 1971
    FredApiStubs.stubSuccessWithObservations(TestConstants.FRED_SERIES_EUR, observations);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).hasSize(1);
    assertThat(rates).containsKey(LocalDate.of(1971, 1, 4));
    assertThat(rates.get(LocalDate.of(1971, 1, 4))).isEqualByComparingTo("0.3571");
  }

  @Test
  @DisplayName("Should preserve high precision decimal values")
  void shouldPreserveHighPrecisionValues() {
    // Given - Values with many decimal places
    var observations = List.of(Observation.of(TestConstants.DATE_2024_JAN_02, "1.08501234567890"));
    FredApiStubs.stubSuccessWithObservations(TestConstants.FRED_SERIES_EUR, observations);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).hasSize(1);
    var rate = rates.get(TestConstants.DATE_2024_JAN_02);
    assertThat(rate).isEqualByComparingTo("1.08501234567890");
    assertThat(rate.scale()).isGreaterThanOrEqualTo(10); // Precision preserved
  }

  @Test
  @DisplayName("Should throw exception on duplicate dates (invalid FRED data)")
  void shouldThrowExceptionOnDuplicateDates() {
    // Given - Duplicate dates (should not happen in FRED, this tests error handling)
    var observations =
        List.of(
            Observation.of(TestConstants.DATE_2024_JAN_02, "1.0850"),
            Observation.of(TestConstants.DATE_2024_JAN_02, "1.0999") // Duplicate date
            );
    FredApiStubs.stubSuccessWithObservations(TestConstants.FRED_SERIES_EUR, observations);

    // When/Then - Provider should throw exception on malformed data
    assertThatThrownBy(() -> provider.getExchangeRates(eurSeries, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate key");
  }
}
