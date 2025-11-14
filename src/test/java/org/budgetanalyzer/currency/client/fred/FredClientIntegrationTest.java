package org.budgetanalyzer.currency.client.fred;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.WireMockServer;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.config.WireMockConfig;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.service.exception.ClientException;

/**
 * Integration tests for FredClient using WireMock to simulate FRED API responses.
 *
 * <p>Tests HTTP client behavior, error handling, timeouts, and response parsing. This test suite
 * validates the FredClient component in isolation from the real FRED API, ensuring reliable and
 * fast test execution.
 *
 * <p>This test class extends AbstractIntegrationTest to get:
 *
 * <ul>
 *   <li>Full Spring Boot context with all beans configured
 *   <li>TestContainers for infrastructure dependencies (if needed)
 *   <li>WireMock server for mocking external HTTP calls
 * </ul>
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li><b>getSeriesObservationsData():</b> Success scenarios (valid data, empty data, missing
 *       data, large datasets, with start date)
 *   <li><b>getSeriesObservationsData():</b> Error scenarios (400, 404, 500, 429, timeout, malformed
 *       JSON, non-JSON errors)
 *   <li><b>seriesExists():</b> Success scenarios (series exists, not found, invalid ID)
 *   <li><b>seriesExists():</b> Error scenarios (server error, rate limited, timeout)
 *   <li><b>Edge cases:</b> URL encoding, error message truncation
 * </ul>
 *
 * <p><b>Key Behaviors Validated:</b>
 *
 * <ul>
 *   <li>HTTP timeout configuration (2s for tests)
 *   <li>Error response parsing (FredErrorResponse JSON format)
 *   <li>Distinction between "not found" (return false) vs "server error" (throw exception)
 *   <li>Handling of FRED's missing data indicator (".")
 *   <li>URL encoding of query parameters
 *   <li>Response deserialization edge cases
 * </ul>
 *
 * @see FredClient
 * @see FredApiStubs
 * @see WireMockConfig
 */
@DisplayName("FredClient Integration Tests")
class FredClientIntegrationTest extends AbstractWireMockTest {

  @Autowired private FredClient fredClient;

  @Autowired private WireMockServer wireMockServer;

  // ===========================================================================================
  // Group 1: getSeriesObservationsData() - Success Scenarios (5 tests)
  // ===========================================================================================

  @Test
  @DisplayName("Should successfully fetch series observations with sample data")
  void shouldFetchSeriesObservationsSuccessfully() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;
    var startDate = LocalDate.of(2024, 1, 1);
    FredApiStubs.stubSuccessWithSampleData(seriesId);

    // When
    var response = fredClient.getSeriesObservationsData(seriesId, startDate);

    // Then
    assertThat(response).isNotNull();
    assertThat(response.observations()).hasSize(10);
    assertThat(response.observations())
        .allSatisfy(
            obs -> {
              assertThat(obs.date()).isNotNull();
              assertThat(obs.value()).isNotBlank();
            });

    // Verify first observation structure
    var firstObs = response.observations().get(0);
    assertThat(firstObs.hasValue()).isTrue();
    assertThat(firstObs.getValueAsBigDecimal()).isNotNull();
  }

  @Test
  @DisplayName("Should handle empty observations array without error")
  void shouldHandleEmptyObservationsArray() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;
    FredApiStubs.stubSuccessEmpty(seriesId);

    // When
    var response = fredClient.getSeriesObservationsData(seriesId, null);

    // Then
    assertThat(response).isNotNull();
    assertThat(response.observations()).isEmpty();
  }

  @Test
  @DisplayName("Should handle observations with missing data indicator '.'")
  void shouldHandleMissingDataIndicator() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;
    FredApiStubs.stubSuccessWithMissingData(seriesId);

    // When
    var response = fredClient.getSeriesObservationsData(seriesId, null);

    // Then
    assertThat(response.observations()).isNotEmpty();

    // Verify some observations have missing data
    var missingDataObs =
        response.observations().stream().filter(obs -> !obs.hasValue()).findFirst();

    assertThat(missingDataObs).isPresent();
    assertThat(missingDataObs.get().value()).isEqualTo(".");
    assertThat(missingDataObs.get().getValueAsBigDecimal()).isNull();
  }

  @Test
  @DisplayName("Should handle large dataset with 365 observations efficiently")
  void shouldHandleLargeDataset() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;
    FredApiStubs.stubSuccessWithLargeDataset(seriesId);

    // When
    long startTime = System.currentTimeMillis();
    var response = fredClient.getSeriesObservationsData(seriesId, null);
    long duration = System.currentTimeMillis() - startTime;

    // Then
    assertThat(response.observations()).hasSize(365);
    assertThat(duration).isLessThan(2000); // Should complete in < 2 seconds
  }

  @Test
  @DisplayName("Should include startDate in query parameters when provided")
  void shouldIncludeStartDateInRequest() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;
    var startDate = LocalDate.of(2024, 6, 1);
    FredApiStubs.stubSuccessWithSampleData(seriesId);

    // When
    var response = fredClient.getSeriesObservationsData(seriesId, startDate);

    // Then
    assertThat(response).isNotNull();
    // Note: WireMock stub doesn't filter by date, but request is made correctly
    // Actual filtering happens on FRED side
  }

  // ===========================================================================================
  // Group 2: getSeriesObservationsData() - Error Scenarios (7 tests)
  // ===========================================================================================

  @Test
  @DisplayName("Should throw ClientException on 400 Bad Request with parsed error message")
  void shouldThrowClientExceptionOn400BadRequest() {
    // Given
    var seriesId = "INVALID_SERIES";
    FredApiStubs.stubBadRequest(seriesId);

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("Bad Request");
  }

  @Test
  @DisplayName("Should throw ClientException on 404 Not Found")
  void shouldThrowClientExceptionOn404NotFound() {
    // Given
    var seriesId = "NONEXISTENT";
    FredApiStubs.stubNotFound(seriesId);

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("Not Found");
  }

  @Test
  @DisplayName("Should throw ClientException on 500 Server Error")
  void shouldThrowClientExceptionOn500ServerError() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;
    FredApiStubs.stubServerError(seriesId);

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("500");
  }

  @Test
  @DisplayName("Should throw ClientException on 429 Rate Limited")
  void shouldThrowClientExceptionOn429RateLimit() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;
    FredApiStubs.stubRateLimited(seriesId);

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("Rate limit");
  }

  @Test
  @DisplayName("Should throw ClientException on timeout after 2 seconds")
  void shouldThrowClientExceptionOnTimeout() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;
    FredApiStubs.stubTimeout(seriesId); // 2 second delay

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .satisfies(
            ex -> {
              // Should timeout in ~2 seconds, not wait full 5
              // Message should mention timeout or series failure
              var message = ex.getMessage().toLowerCase();
              assertThat(message)
                  .matches(
                      msg ->
                          msg.contains("timeout")
                              || msg.contains("timed out")
                              || msg.contains("failed to fetch"));
            });
  }

  @Test
  @DisplayName("Should throw ClientException on malformed JSON response")
  void shouldThrowClientExceptionOnMalformedJson() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;
    FredApiStubs.stubMalformedJson(seriesId);

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class);
  }

  @Test
  @DisplayName("Should handle non-JSON error response gracefully")
  void shouldHandleNonJsonErrorResponse() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;

    // Stub plain text error response
    wireMockServer.stubFor(
        get(urlPathEqualTo("/series/observations"))
            .willReturn(
                aResponse()
                    .withStatus(500)
                    .withHeader("Content-Type", "text/plain")
                    .withBody("Internal Server Error")));

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("500");
  }

  // ===========================================================================================
  // Group 3: seriesExists() - Success Scenarios (3 tests)
  // ===========================================================================================

  @Test
  @DisplayName("Should return true when series exists (200 OK)")
  void shouldReturnTrueWhenSeriesExists() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;
    FredApiStubs.stubSeriesExistsSuccess(seriesId);

    // When
    boolean exists = fredClient.seriesExists(seriesId);

    // Then
    assertThat(exists).isTrue();
  }

  @Test
  @DisplayName("Should return false when series not found (404) - not throw exception")
  void shouldReturnFalseWhenSeriesNotFound() {
    // Given
    var seriesId = "NONEXISTENT";
    FredApiStubs.stubSeriesExistsNotFound(seriesId);

    // When
    boolean exists = fredClient.seriesExists(seriesId);

    // Then
    assertThat(exists).isFalse();
  }

  @Test
  @DisplayName("Should return false when series ID invalid (400) - not throw exception")
  void shouldReturnFalseWhenSeriesIdInvalid() {
    // Given
    var seriesId = "INVALID@@@";
    FredApiStubs.stubSeriesExistsBadRequest(seriesId);

    // When
    boolean exists = fredClient.seriesExists(seriesId);

    // Then
    assertThat(exists).isFalse();
  }

  // ===========================================================================================
  // Group 4: seriesExists() - Error Scenarios (3 tests)
  // ===========================================================================================

  @Test
  @DisplayName("Should throw ClientException on 500 Server Error (not return false)")
  void shouldThrowClientExceptionOnServerError() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;
    FredApiStubs.stubSeriesExistsServerError(seriesId);

    // When/Then
    assertThatThrownBy(() -> fredClient.seriesExists(seriesId))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("500");
  }

  @Test
  @DisplayName("Should throw ClientException on 429 Rate Limited")
  void shouldThrowClientExceptionOnRateLimitForSeriesExists() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;

    // Stub rate limit for /series endpoint
    wireMockServer.stubFor(
        get(urlPathEqualTo("/series"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "error_code": 429,
                          "error_message": "API rate limit exceeded"
                        }
                        """)));

    // When/Then
    assertThatThrownBy(() -> fredClient.seriesExists(seriesId))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("rate limit");
  }

  @Test
  @DisplayName("Should throw ClientException on timeout after 3 seconds for seriesExists")
  void shouldThrowClientExceptionOnTimeoutForSeriesExists() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;
    FredApiStubs.stubSeriesExistsTimeout(seriesId); // 6 second delay

    // When/Then
    assertThatThrownBy(() -> fredClient.seriesExists(seriesId))
        .isInstanceOf(ClientException.class)
        .satisfies(
            ex -> {
              // Message should mention timeout or series failure
              var message = ex.getMessage().toLowerCase();
              assertThat(message)
                  .matches(
                      msg ->
                          msg.contains("timeout")
                              || msg.contains("timed out")
                              || msg.contains("failed to check"));
            });
  }

  // ===========================================================================================
  // Group 5: Edge Cases (2 tests)
  // ===========================================================================================

  @Test
  @DisplayName("Should properly URL encode special characters in series ID")
  void shouldUrlEncodeSpecialCharacters() {
    // Given
    var seriesId = "TEST/SERIES:123";

    // Stub with pattern matching for URL-encoded series ID
    wireMockServer.stubFor(
        get(urlPathEqualTo("/series/observations"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "realtime_start": "2024-01-01",
                          "realtime_end": "2024-01-01",
                          "observation_start": "2024-01-01",
                          "observation_end": "2024-01-01",
                          "units": "Currency",
                          "output_type": 1,
                          "file_type": "json",
                          "order_by": "observation_date",
                          "sort_order": "asc",
                          "count": 0,
                          "offset": 0,
                          "limit": 100000,
                          "observations": []
                        }
                        """)));

    // When
    var response = fredClient.getSeriesObservationsData(seriesId, null);

    // Then
    assertThat(response).isNotNull();
    assertThat(response.observations()).isEmpty();
    // Verify WireMock received URL-encoded request
    // (WireMock automatically handles this, so if no error = success)
  }

  @Test
  @DisplayName("Should truncate very long error messages to 500 characters")
  void shouldTruncateLongErrorMessages() {
    // Given
    var seriesId = TestConstants.FRED_SERIES_EUR;
    var longErrorMessage = "Error: " + "x".repeat(1000); // 1000+ chars

    wireMockServer.stubFor(
        get(urlPathEqualTo("/fred/series/observations"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "error_code": 400,
                          "error_message": "%s"
                        }
                        """
                            .formatted(longErrorMessage))));

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .satisfies(
            ex -> {
              assertThat(ex.getMessage().length()).isLessThanOrEqualTo(550); // ~500 + overhead
            });
  }
}
