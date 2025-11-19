package org.budgetanalyzer.currency.api;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import jakarta.validation.constraints.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.budgetanalyzer.currency.api.response.ExchangeRateResponse;
import org.budgetanalyzer.currency.service.ExchangeRateService;
import org.budgetanalyzer.service.api.ApiErrorResponse;
import org.budgetanalyzer.service.exception.InvalidRequestException;
import org.budgetanalyzer.service.security.SecurityContextUtil;

@Tag(name = "Exchange Rates Handler", description = "Endpoints for querying exchange rates")
@RestController
@RequestMapping(path = "/v1/exchange-rates")
public class ExchangeRateController {

  private static final Logger log = LoggerFactory.getLogger(ExchangeRateController.class);

  private final ExchangeRateService exchangeRateService;

  public ExchangeRateController(ExchangeRateService exchangeRateService) {
    this.exchangeRateService = exchangeRateService;
  }

  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Get exchange rates",
      description = "Get exchange rates for converting USD to the target currency")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    array =
                        @ArraySchema(
                            schema = @Schema(implementation = ExchangeRateResponse.class)))),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(
            responseCode = "422",
            description = "Business validation failed",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = {
                      @ExampleObject(
                          name = "No Exchange Rate Data Available",
                          summary = "No exchange rate data exists for the requested currency",
                          value =
                              """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "No exchange rate data available for currency: EUR",
                        "code": "NO_EXCHANGE_RATE_DATA_AVAILABLE"
                      }
                      """),
                      @ExampleObject(
                          name = "Start Date Out of Range",
                          summary = "Start date is before the earliest available data",
                          value =
                              """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "Exchange rates for THB not available before 2000-01-03",
                        "code": "START_DATE_OUT_OF_RANGE"
                      }
                      """),
                      @ExampleObject(
                          name = "Currency Not Enabled",
                          summary = "Currency not enabled for exchange rate data",
                          value =
                              """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "Currency is not enabled: EUR",
                        "code": "CURRENCY_NOT_ENABLED"
                      }
                      """)
                    }))
      })
  @GetMapping(path = "", produces = "application/json")
  public List<ExchangeRateResponse> getExchangeRates(
      @Parameter(
              description = "Start date for exchange rates in ISO format",
              example = "2024-01-01")
          @RequestParam
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          Optional<LocalDate> startDate,
      @Parameter(description = "End date for exchange rates in ISO format", example = "2024-12-31")
          @RequestParam
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          Optional<LocalDate> endDate,
      @Parameter(description = "Target currency of exchange rate", example = "THB")
          @NotNull
          @RequestParam
          Currency targetCurrency) {
    // Log authenticated user information (for audit purposes)
    var userId = SecurityContextUtil.getCurrentUserId();
    var userEmail = SecurityContextUtil.getCurrentUserEmail();
    log.info(
        "Received getExchangeRates request - User ID: {}, Email: {}, startDate: {},"
            + " endDate: {}, targetCurrency: {}",
        userId.orElse("anonymous"),
        userEmail.orElse("N/A"),
        startDate.orElse(null),
        endDate.orElse(null),
        targetCurrency);

    // Request format validation: check if startDate is after endDate
    if (startDate.isPresent() && endDate.isPresent() && startDate.get().isAfter(endDate.get())) {
      throw new InvalidRequestException("Start date must be before or equal to end date");
    }

    var exchangeRates =
        exchangeRateService.getExchangeRates(
            targetCurrency, startDate.orElse(null), endDate.orElse(null));

    return exchangeRates.stream().map(ExchangeRateResponse::from).toList();
  }
}
