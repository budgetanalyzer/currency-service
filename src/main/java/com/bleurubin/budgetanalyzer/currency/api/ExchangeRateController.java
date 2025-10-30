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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.bleurubin.budgetanalyzer.currency.api.response.ExchangeRateResponse;
import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;
import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateImportService;
import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateService;
import com.bleurubin.service.api.ApiErrorResponse;

@Tag(name = "Exchange Rates Handler", description = "Endpoints for operations on exchange rates")
@RestController
@RequestMapping(path = "/exchange-rates")
public class ExchangeRateController {

  private static final Logger log = LoggerFactory.getLogger(ExchangeRateController.class);

  private final ExchangeRateImportService csvService;

  private final ExchangeRateService exchangeRateService;

  public ExchangeRateController(
      ExchangeRateImportService csvService, ExchangeRateService exchangeRateService) {
    this.csvService = csvService;
    this.exchangeRateService = exchangeRateService;
  }

  @Operation(
      summary = "Import CSV file containing USD exchange rate series from FRED",
      description =
          "Imports USD exchange rates in the format provided by FRED, i.e. https://fred.stlouisfed.org/series/DEXTHUS")
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
        @ApiResponse(
            responseCode = "422",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = {
                      @ExampleObject(
                          name = "CSV Parsing Error",
                          summary = "Invalid value found in csv file",
                          value =
                              """
        {
          "type": "APPLICATION_ERROR",
          "message": "Invalid value in csv file",
          "code": "CSV_PARSING_ERROR"
        }
        """)
                    }))
      })
  @PostMapping(path = "/import", consumes = "multipart/form-data", produces = "application/json")
  public List<ExchangeRate> importExchangeRates(
      @Parameter(
              description =
                  "Target currency for exchange rate series.  Currently only supports THB.",
              example = "THB")
          @NotNull
          @RequestParam(value = "targetCurrency", defaultValue = "THB")
          Currency targetCurrency,
      @Parameter(description = "CSV file to upload", required = true) @NotNull @RequestParam("file")
          MultipartFile file) {
    log.info(
        "Received importExchangeRates request targetCurrency: {} fileName: {}",
        targetCurrency,
        file.getOriginalFilename());

    return csvService.importExchangeRates(file, targetCurrency);
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
        "Received get exchange-rates request startDate: {} endDate: {} targetCurrency: {}",
        startDate.orElse(null),
        endDate.orElse(null),
        targetCurrency);

    var exchangeRates =
        exchangeRateService.getExchangeRates(
            targetCurrency, startDate.orElse(null), endDate.orElse(null));

    return exchangeRates.stream().map(ExchangeRateResponse::from).toList();
  }
}
