package com.bleurubin.budgetanalyzer.currency.api;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import jakarta.validation.constraints.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.bleurubin.budgetanalyzer.currency.api.response.ExchangeRateImportResultResponse;
import com.bleurubin.budgetanalyzer.currency.api.response.ExchangeRateResponse;
import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateImportService;
import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateService;

@Tag(name = "Exchange Rates Handler", description = "Endpoints for operations on exchange rates")
@RestController
@RequestMapping(path = "/v1/exchange-rates")
public class ExchangeRateController {

  private static final Logger log = LoggerFactory.getLogger(ExchangeRateController.class);

  private static final Currency THB = Currency.getInstance("THB");

  private final ExchangeRateImportService exchangeRateImportService;
  private final ExchangeRateService exchangeRateService;

  public ExchangeRateController(
      ExchangeRateImportService csvService, ExchangeRateService exchangeRateService) {
    this.exchangeRateImportService = csvService;
    this.exchangeRateService = exchangeRateService;
  }

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
    log.info(
        "Received get getExchangeRates request startDate: {} endDate: {} targetCurrency: {}",
        startDate.orElse(null),
        endDate.orElse(null),
        targetCurrency);

    var exchangeRates =
        exchangeRateService.getExchangeRates(
            targetCurrency, startDate.orElse(null), endDate.orElse(null));

    return exchangeRates.stream().map(ExchangeRateResponse::from).toList();
  }

  @Operation(
      summary = "Import latest available rates from FRED",
      description =
          "Retrieve latest un-imported exchange rates from FRED- manually triggers daily cron job")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExchangeRateImportResultResponse.class))),
      })
  @GetMapping(path = "/import", produces = "application/json")
  public ExchangeRateImportResultResponse importLatestExchangeRates() {
    log.info("Received importLatestExchangeRates request");

    // we will take currency as parameter when we support multiple currencies
    var exchangeRateImportResult = exchangeRateImportService.importLatestExchangeRates();
    return ExchangeRateImportResultResponse.from(exchangeRateImportResult);
  }
}
