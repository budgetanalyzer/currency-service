package com.bleurubin.budgetanalyzer.currency.client.fred;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

import com.bleurubin.budgetanalyzer.currency.client.fred.response.FredErrorResponse;
import com.bleurubin.budgetanalyzer.currency.client.fred.response.FredSeriesObservationsResponse;
import com.bleurubin.budgetanalyzer.currency.config.CurrencyServiceProperties;
import com.bleurubin.core.logging.SafeLogger;
import com.bleurubin.service.exception.ClientException;

@Component
public class FredClient {

  private static final Logger log = LoggerFactory.getLogger(FredClient.class);

  private static final String USER_AGENT = "CurrencyServiceClient/1.0";

  private final WebClient webClient;
  private final String fredApiKey;
  private final ObjectMapper objectMapper;

  public FredClient(
      WebClient.Builder webClientBuilder,
      CurrencyServiceProperties properties,
      ObjectMapper objectMapper) {

    var fredConfig = properties.getExchangeRateImport().getFred();

    // properties have @Validated but double checking
    if (fredConfig.getApiKey() == null || fredConfig.getApiKey().isBlank()) {
      throw new IllegalArgumentException("FRED API key must be configured");
    }

    this.fredApiKey = fredConfig.getApiKey();
    this.webClient =
        webClientBuilder
            .baseUrl(fredConfig.getBaseUrl())
            .defaultHeader("User-Agent", USER_AGENT)
            .build();
    this.objectMapper = objectMapper;

    log.info("FredClient initialized with base URL: {}", fredConfig.getBaseUrl());
  }

  public FredSeriesObservationsResponse getSeriesObservationsData(
      String seriesId, LocalDate startDate) {
    var url = buildSeriesObservationsUrl(seriesId, startDate);
    log.info("Requesting FRED series: {} startDate: {}", seriesId, startDate);

    try {
      var response =
          webClient
              .get()
              .uri(url)
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .onStatus(HttpStatusCode::isError, this::handleErrorResponse)
              .bodyToMono(FredSeriesObservationsResponse.class)
              .timeout(Duration.ofSeconds(30))
              .block();

      if (response == null) {
        throw new ClientException("Received null response from FRED API");
      }

      log.debug(
          "Successfully fetched data from FRED API for series: {} data:\n{}",
          seriesId,
          SafeLogger.toJson(response));
      return response;
    } catch (ClientException ce) {
      throw ce;
    } catch (Exception e) {
      log.warn(
          "Unexpected error fetching FRED data for series {}: {}", seriesId, e.getMessage(), e);
      throw new ClientException("Failed to fetch FRED data for series: " + seriesId, e);
    }
  }

  private String buildSeriesObservationsUrl(String seriesId, LocalDate startDate) {
    var url =
        new StringBuilder("/series/observations")
            .append("?series_id=")
            .append(URLEncoder.encode(seriesId, StandardCharsets.UTF_8))
            .append("&api_key=")
            .append(URLEncoder.encode(fredApiKey, StandardCharsets.UTF_8))
            .append("&file_type=json");

    if (startDate != null) {
      url.append("&observation_start=")
          .append(URLEncoder.encode(startDate.toString(), StandardCharsets.UTF_8));
    }

    return url.toString();
  }

  private Mono<? extends Throwable> handleErrorResponse(ClientResponse response) {
    return response
        .bodyToMono(String.class)
        .defaultIfEmpty("No response body")
        .map(body -> parseErrorAndCreateException(response, body));
  }

  private Throwable parseErrorAndCreateException(ClientResponse response, String body) {
    Integer errorCode = null;
    String errorMessage = body;

    // Try to parse structured error response
    if (body != null && !body.isBlank()) {
      try {
        FredErrorResponse errorResponse = objectMapper.readValue(body, FredErrorResponse.class);

        if (errorResponse.errorMessage() != null) {
          errorMessage = errorResponse.errorMessage();
        }
        errorCode = errorResponse.errorCode();

      } catch (JsonProcessingException e) {
        // Not JSON or doesn't match our error structure
        // Keep the raw body as the message
        log.debug("Could not parse FRED error response as JSON: {}", e.getMessage());

        // Truncate very long error bodies
        if (body.length() > 500) {
          errorMessage = body.substring(0, 500) + "... (truncated)";
        }
      }
    }

    log.warn(
        "FRED API error: HTTP {} - Error Code: {} - Message: {}",
        response.statusCode(),
        errorCode,
        errorMessage);

    return new ClientException("FRED API error message: " + errorMessage + " code: " + errorCode);
  }

  /**
   * Checks if a series exists in FRED.
   *
   * @param seriesId The FRED series ID to check
   * @return true if the series exists, false if it does not exist
   * @throws ClientException if there is a network error, rate limiting, or API failure (not a
   *     "series not found" error)
   */
  public boolean seriesExists(String seriesId) {
    var url = buildSeriesUrl(seriesId);
    log.debug("Checking if FRED series exists: {}", seriesId);

    try {
      return webClient
          .get()
          .uri(url)
          .accept(MediaType.APPLICATION_JSON)
          .exchangeToMono(
              response -> {
                int statusCode = response.statusCode().value();

                // Series exists
                if (statusCode == 200) {
                  log.debug("FRED series exists: {}", seriesId);
                  return Mono.just(true);
                }

                // Series doesn't exist
                if (statusCode == 400 || statusCode == 404) {
                  log.debug("FRED series does not exist: {} (HTTP {})", seriesId, statusCode);
                  return Mono.just(false);
                }

                // Any other error - parse and throw
                return response
                    .bodyToMono(String.class)
                    .defaultIfEmpty("No response body")
                    .flatMap(body -> Mono.error(parseErrorAndCreateException(response, body)));
              })
          .block(Duration.ofSeconds(5));

    } catch (ClientException ce) {
      throw ce;
    } catch (Exception e) {
      log.warn(
          "Unexpected error checking if FRED series exists {}: {}", seriesId, e.getMessage(), e);
      throw new ClientException("Failed to check if FRED series exists: " + seriesId, e);
    }
  }

  private String buildSeriesUrl(String seriesId) {
    return new StringBuilder("/series")
        .append("?series_id=")
        .append(URLEncoder.encode(seriesId, StandardCharsets.UTF_8))
        .append("&api_key=")
        .append(URLEncoder.encode(fredApiKey, StandardCharsets.UTF_8))
        .append("&file_type=json")
        .toString();
  }
}
