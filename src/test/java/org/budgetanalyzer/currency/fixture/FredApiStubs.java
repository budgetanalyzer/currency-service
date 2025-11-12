package org.budgetanalyzer.currency.fixture;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * WireMock stub templates for FRED API responses.
 *
 * <p>Provides pre-configured HTTP stubs for common FRED API scenarios including success responses,
 * error responses, and edge cases.
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * // In a test class extending AbstractControllerTest (which provides WireMock)
 * &#64;Test
 * void testImportExchangeRates() {
 *     // Stub successful FRED API response
 *     FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
 *
 *     // Call service method that uses FRED client
 *     importService.importExchangeRates("EUR");
 *
 *     // Verify data imported correctly
 *     // ...
 * }
 *
 * &#64;Test
 * void testImportFailsWhenSeriesNotFound() {
 *     // Stub 404 error
 *     FredApiStubs.stubNotFound(TestConstants.FRED_SERIES_INVALID);
 *
 *     // Verify exception thrown
 *     assertThrows(BusinessException.class, () ->
 *         importService.importExchangeRates("XXX")
 *     );
 * }
 * }</pre>
 *
 * @see com.github.tomakehurst.wiremock.client.WireMock
 */
public final class FredApiStubs {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  private FredApiStubs() {
    throw new UnsupportedOperationException("Utility class - do not instantiate");
  }

  // ===========================================================================================
  // Success Scenarios
  // ===========================================================================================

  /**
   * Stubs a successful FRED API response with custom observations.
   *
   * <p>Use this when you need full control over the response data.
   *
   * @param seriesId the FRED series ID (e.g., "DEXUSEU")
   * @param observations list of observations (date and value pairs)
   */
  public static void stubSuccessWithObservations(String seriesId, List<Observation> observations) {
    var responseBody = buildFredSuccessResponse(seriesId, observations);
    stubFor(
        get(urlPathEqualTo(TestConstants.FRED_API_PATH_OBSERVATIONS))
            .withQueryParam(TestConstants.FRED_PARAM_SERIES_ID, equalTo(seriesId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(responseBody)));
  }

  /**
   * Stubs a successful FRED API response with sample EUR data.
   *
   * <p>Returns 10 days of EUR/USD exchange rates (Jan 1-10, 2024) with realistic values.
   */
  public static void stubSuccessWithSampleData(String seriesId) {
    var observations = new ArrayList<Observation>();
    var startDate = TestConstants.DATE_2024_JAN_01;

    // Create 10 days of data with slight variations
    for (int i = 0; i < 10; i++) {
      var date = startDate.plusDays(i);
      var dayOfWeek = date.getDayOfWeek();

      // Weekends have missing data (value=".")
      if (dayOfWeek.getValue() <= 5) {
        // Weekday: provide rate
        var rate = String.format("0.%04d", 8500 + i * 10); // 0.8500, 0.8510, ...
        observations.add(new Observation(date.toString(), rate));
      } else {
        // Weekend: missing data
        observations.add(new Observation(date.toString(), TestConstants.FRED_MISSING_DATA_VALUE));
      }
    }

    stubSuccessWithObservations(seriesId, observations);
  }

  /**
   * Stubs a successful FRED API response with empty observations array.
   *
   * <p>Use this to test behavior when a series exists but has no data for the requested date range.
   *
   * @param seriesId the FRED series ID
   */
  public static void stubSuccessEmpty(String seriesId) {
    stubSuccessWithObservations(seriesId, List.of());
  }

  /**
   * Stubs a successful FRED API response with only missing data values.
   *
   * <p>All observations have value="." (FRED's indicator for missing data).
   *
   * @param seriesId the FRED series ID
   */
  public static void stubSuccessWithMissingData(String seriesId) {
    var observations =
        List.of(
            new Observation("2024-01-06", TestConstants.FRED_MISSING_DATA_VALUE), // Saturday
            new Observation("2024-01-07", TestConstants.FRED_MISSING_DATA_VALUE) // Sunday
            );
    stubSuccessWithObservations(seriesId, observations);
  }

  // ===========================================================================================
  // Error Scenarios
  // ===========================================================================================

  /**
   * Stubs a 400 Bad Request response from FRED API.
   *
   * <p>Typically returned when the series ID format is invalid.
   *
   * @param seriesId the FRED series ID
   */
  public static void stubBadRequest(String seriesId) {
    var errorResponse =
        buildFredErrorResponse(
            TestConstants.HTTP_BAD_REQUEST,
            "Bad Request. The series does not exist or the parameters are invalid.");
    stubFor(
        get(urlPathEqualTo(TestConstants.FRED_API_PATH_OBSERVATIONS))
            .withQueryParam(TestConstants.FRED_PARAM_SERIES_ID, equalTo(seriesId))
            .willReturn(
                aResponse()
                    .withStatus(TestConstants.HTTP_BAD_REQUEST)
                    .withHeader("Content-Type", "application/json")
                    .withBody(errorResponse)));
  }

  /**
   * Stubs a 404 Not Found response from FRED API.
   *
   * <p>Returned when the series ID does not exist in FRED database.
   *
   * @param seriesId the FRED series ID
   */
  public static void stubNotFound(String seriesId) {
    var errorResponse =
        buildFredErrorResponse(
            TestConstants.HTTP_NOT_FOUND,
            "Not Found. The series '" + seriesId + "' does not exist.");
    stubFor(
        get(urlPathEqualTo(TestConstants.FRED_API_PATH_OBSERVATIONS))
            .withQueryParam(TestConstants.FRED_PARAM_SERIES_ID, equalTo(seriesId))
            .willReturn(
                aResponse()
                    .withStatus(TestConstants.HTTP_NOT_FOUND)
                    .withHeader("Content-Type", "application/json")
                    .withBody(errorResponse)));
  }

  /**
   * Stubs a 500 Internal Server Error response from FRED API.
   *
   * <p>Use this to test error handling when FRED API is experiencing issues.
   *
   * @param seriesId the FRED series ID
   */
  public static void stubServerError(String seriesId) {
    var errorResponse =
        buildFredErrorResponse(
            TestConstants.HTTP_INTERNAL_SERVER_ERROR,
            "Internal Server Error. Please try again later.");
    stubFor(
        get(urlPathEqualTo(TestConstants.FRED_API_PATH_OBSERVATIONS))
            .withQueryParam(TestConstants.FRED_PARAM_SERIES_ID, equalTo(seriesId))
            .willReturn(
                aResponse()
                    .withStatus(TestConstants.HTTP_INTERNAL_SERVER_ERROR)
                    .withHeader("Content-Type", "application/json")
                    .withBody(errorResponse)));
  }

  /**
   * Stubs a delayed response that triggers client timeout.
   *
   * <p>Response is delayed by 35 seconds, which exceeds the typical 30-second client timeout.
   *
   * @param seriesId the FRED series ID
   */
  public static void stubTimeout(String seriesId) {
    stubFor(
        get(urlPathEqualTo(TestConstants.FRED_API_PATH_OBSERVATIONS))
            .withQueryParam(TestConstants.FRED_PARAM_SERIES_ID, equalTo(seriesId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")
                    .withFixedDelay(35_000))); // 35 seconds delay
  }

  // ===========================================================================================
  // Edge Cases
  // ===========================================================================================

  /**
   * Stubs a response with malformed JSON.
   *
   * <p>Use this to test error handling when FRED API returns invalid JSON.
   *
   * @param seriesId the FRED series ID
   */
  public static void stubMalformedJson(String seriesId) {
    stubFor(
        get(urlPathEqualTo(TestConstants.FRED_API_PATH_OBSERVATIONS))
            .withQueryParam(TestConstants.FRED_PARAM_SERIES_ID, equalTo(seriesId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{invalid json content here...")));
  }

  /**
   * Stubs a 429 Too Many Requests response.
   *
   * <p>Use this to test rate limiting behavior.
   *
   * @param seriesId the FRED series ID
   */
  public static void stubRateLimited(String seriesId) {
    stubFor(
        get(urlPathEqualTo(TestConstants.FRED_API_PATH_OBSERVATIONS))
            .withQueryParam(TestConstants.FRED_PARAM_SERIES_ID, equalTo(seriesId))
            .willReturn(
                aResponse()
                    .withStatus(TestConstants.HTTP_TOO_MANY_REQUESTS)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Retry-After", "60")
                    .withBody(
                        buildFredErrorResponse(
                            TestConstants.HTTP_TOO_MANY_REQUESTS,
                            "Too Many Requests. Rate limit exceeded."))));
  }

  /**
   * Stubs a successful response with a large dataset (365 observations).
   *
   * <p>Use this to test performance with a full year of daily data.
   *
   * @param seriesId the FRED series ID
   */
  public static void stubSuccessWithLargeDataset(String seriesId) {
    var observations = new ArrayList<Observation>();
    var startDate = TestConstants.DATE_2024_JAN_01;

    // Create full year of data
    for (int i = 0; i < 365; i++) {
      var date = startDate.plusDays(i);
      var dayOfWeek = date.getDayOfWeek();

      if (dayOfWeek.getValue() <= 5) {
        // Weekday: provide rate with small variation
        var rate = String.format("0.%04d", 8500 + (i % 100));
        observations.add(new Observation(date.toString(), rate));
      } else {
        // Weekend: missing data
        observations.add(new Observation(date.toString(), TestConstants.FRED_MISSING_DATA_VALUE));
      }
    }

    stubSuccessWithObservations(seriesId, observations);
  }

  // ===========================================================================================
  // Helper Methods
  // ===========================================================================================

  /**
   * Builds a complete FRED API success response JSON.
   *
   * @param seriesId the FRED series ID
   * @param observations list of observations
   * @return JSON response as string
   */
  private static String buildFredSuccessResponse(String seriesId, List<Observation> observations) {
    // Determine date range from observations
    var startDate =
        observations.isEmpty()
            ? TestConstants.DATE_2024_JAN_01.toString()
            : observations.get(0).date();
    var endDate =
        observations.isEmpty()
            ? TestConstants.DATE_2024_DEC_31.toString()
            : observations.get(observations.size() - 1).date();

    // Use HashMap since Map.of() only supports up to 10 key-value pairs
    var response = new java.util.HashMap<String, Object>();
    response.put("realtime_start", LocalDate.now().toString());
    response.put("realtime_end", LocalDate.now().toString());
    response.put("observation_start", startDate);
    response.put("observation_end", endDate);
    response.put("units", "Currency");
    response.put("output_type", 1);
    response.put("file_type", "json");
    response.put("order_by", "observation_date");
    response.put("sort_order", "asc");
    response.put("count", observations.size());
    response.put("offset", 0);
    response.put("limit", 100000);
    response.put(
        "observations",
        observations.stream()
            .map(
                obs ->
                    Map.of(
                        "realtime_start",
                        LocalDate.now().toString(),
                        "realtime_end",
                        LocalDate.now().toString(),
                        "date",
                        obs.date(),
                        "value",
                        obs.value()))
            .toList());

    try {
      return objectMapper.writeValueAsString(response);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize FRED response", e);
    }
  }

  /**
   * Builds a FRED API error response JSON.
   *
   * @param errorCode the HTTP error code
   * @param errorMessage the error message
   * @return JSON error response as string
   */
  private static String buildFredErrorResponse(int errorCode, String errorMessage) {
    var errorResponse = Map.of("error_code", errorCode, "error_message", errorMessage);

    try {
      return objectMapper.writeValueAsString(errorResponse);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize FRED error response", e);
    }
  }

  /**
   * Represents a FRED API observation (date + value pair).
   *
   * @param date the observation date in ISO-8601 format (e.g., "2024-01-15")
   * @param value the exchange rate value as string (e.g., "0.8500" or "." for missing data)
   */
  public record Observation(String date, String value) {
    /**
     * Creates an observation from a LocalDate and BigDecimal rate.
     *
     * @param date the observation date
     * @param rate the exchange rate value
     * @return a new Observation
     */
    public static Observation of(LocalDate date, String rate) {
      return new Observation(date.toString(), rate);
    }

    /**
     * Creates an observation with missing data (value=".").
     *
     * @param date the observation date
     * @return a new Observation with missing data
     */
    public static Observation missingData(LocalDate date) {
      return new Observation(date.toString(), TestConstants.FRED_MISSING_DATA_VALUE);
    }
  }
}
