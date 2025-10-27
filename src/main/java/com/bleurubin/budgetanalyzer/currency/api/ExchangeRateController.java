package com.bleurubin.budgetanalyzer.currency.api;

import com.bleurubin.budgetanalyzer.currency.api.response.ExchangeRateResponse;
import com.bleurubin.service.api.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Exchange Rates Handler", description = "Endpoints for operations on exchange rates")
@RestController
@RequestMapping(path = "/exchange-rates")
public class ExchangeRateController {

  private static final Currency DEFAULT_BASE_CURRENCY = Currency.getInstance("USD");

  private final Logger log = LoggerFactory.getLogger(this.getClass());

  @Operation(
      summary = "Import CSV file containing USD exchange rate series from FRED",
      description =
          "Imports USD exchange rates in the format provided by FRED, i.e. https://fred.stlouisfed.org/series/DEXTHUS")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Exchange rates imported successfully",
            content =
                @Content(
                    mediaType = "application/json",
                    array =
                        @ArraySchema(
                            schema = @Schema(implementation = ExchangeRateResponse.class)))),
        @ApiResponse(
            responseCode = "422",
            description =
                "Request was correctly formatted but there were business rules violated by the request so it couldn't be processed",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(path = "/import", consumes = "multipart/form-data", produces = "application/json")
  public List<ExchangeRateResponse> importExchangeRates(
      @Parameter(
              description = "Base currency for exchange rate series.  Currently only supports USD.",
              example = "USD")
          @RequestParam(value = "baseCurrency", required = false, defaultValue = "USD")
          Currency baseCurrency,
      @Parameter(
              description =
                  "Target currency for exchange rate series.  Currently only supports THB.",
              example = "THB")
          @NotNull
          @RequestParam(value = "targetCurrency", defaultValue = "THB")
          Currency targetCurrency,
      @Parameter(description = "CSV file to upload", required = true) @NotNull @RequestParam("file")
          MultipartFile file)
      throws IOException {
    log.info(
        "Received importExchangeRates request baseCurrency: {} targetCurrency: {} fileName: {}",
        baseCurrency,
        targetCurrency,
        file.getOriginalFilename());

    if (file.isEmpty()) {
      log.warn("No file provided");
      throw new IllegalArgumentException("No file provided");
    }

    return null;
  }

  @Operation(
      summary = "Get exchange rates",
      description = "Get exchange rates for converting USD to the target currency")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "List of exchange rates",
            content =
                @Content(
                    mediaType = "application/json",
                    array =
                        @ArraySchema(
                            schema = @Schema(implementation = ExchangeRateResponse.class)))),
      })
  @GetMapping(path = "", produces = "application/json")
  public List<ExchangeRateResponse> getTransactions(
      @Parameter(
              description = "Start date for exchange rates in ISO format",
              example = "2024-01-01")
          @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          Optional<LocalDate> startDate,
      @Parameter(description = "End date for exchange rates in ISO format", example = "2024-12-31")
          @RequestParam(required = false)
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

    return buildExchangeRates();
  }

  private List<ExchangeRateResponse> buildExchangeRates() {
    var rv = new ArrayList<ExchangeRateResponse>();
    var startDate = LocalDate.of(2020, 1, 1);
    var endDate = LocalDate.of(2025, 10, 25);
    var amount = new BigDecimal("32.68");

    // Iterate through all dates in the range
    for (var date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
      var response =
          ExchangeRateResponse.builder()
              .baseCurrency(Currency.getInstance("USD"))
              .targetCurrency(Currency.getInstance("THB"))
              .date(date)
              .rate(amount)
              .build();

      rv.add(response);
    }

    return rv;
  }
}
