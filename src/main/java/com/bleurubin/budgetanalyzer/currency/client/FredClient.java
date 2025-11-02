package com.bleurubin.budgetanalyzer.currency.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

import com.bleurubin.service.exception.ServiceUnavailableException;

@Component
public class FredClient {

  private static final Logger log = LoggerFactory.getLogger(FredClient.class);

  private final WebClient webClient;

  private final String fredBaseUrl;

  public FredClient(
      WebClient.Builder webClientBuilder,
      @Value("${fred.api.base-url:https://fred.stlouisfed.org/graph/fredgraph.csv}")
          String fredBaseUrl) {
    this.fredBaseUrl = fredBaseUrl;
    this.webClient =
        webClientBuilder
            .baseUrl(fredBaseUrl)
            .defaultHeader("User-Agent", "CurrencyService/1.0")
            .build();
  }

  public Resource getExchangeRateDataAsResource(String seriesId, LocalDate startDate) {
    var url = buildFredUrl(seriesId, startDate);
    log.info(
        "Requesting exchange rate csv series: {} startDate: {} url: {}", seriesId, startDate, url);

    try {
      var resource =
          webClient
              .get()
              .uri(url)
              .accept(MediaType.TEXT_PLAIN)
              .retrieve()
              .onStatus(HttpStatusCode::isError, this::handleErrorResponse)
              .bodyToMono(Resource.class)
              .timeout(Duration.ofSeconds(30))
              .block();

      if (resource == null) {
        throw new ServiceUnavailableException("Received null resource from FRED");
      }

      log.info("Successfully fetched resource from FRED");
      return resource;
    } catch (Exception e) {
      log.error("Error fetching FRED data: {}", e.getMessage());
      throw new ServiceUnavailableException("Failed to fetch FRED data", e);
    }
  }

  private Mono<? extends Throwable> handleErrorResponse(ClientResponse response) {
    return response
        .bodyToMono(String.class)
        .defaultIfEmpty("No response body")
        .flatMap(
            body -> {
              String message =
                  String.format(
                      "Error fetching FRED data: HTTP %s - %s",
                      response.statusCode(),
                      body.length() > 200 ? body.substring(0, 200) + "..." : body);

              log.error(message);
              return Mono.error(new ServiceUnavailableException(message));
            });
  }

  private String buildFredUrl(String seriesId, LocalDate startDate) {
    var url =
        new StringBuilder(fredBaseUrl)
            .append("?id=")
            .append(URLEncoder.encode(seriesId, StandardCharsets.UTF_8));

    if (startDate != null) {
      url.append("&cosd=").append(URLEncoder.encode(startDate.toString(), StandardCharsets.UTF_8));
    }

    return url.toString();
  }
}
