package com.bleurubin.budgetanalyzer.currency.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.bleurubin.budgetanalyzer.currency.api.response.ExchangeRateImportResultResponse;
import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateImportService;

/** Admin endpoints for exchange rate management. */
@Tag(
    name = "Admin - Exchange Rates Handler",
    description = "Admin endpoints for importing and managing exchange rates")
@RestController
@RequestMapping(path = "/v1/admin/exchange-rates")
public class AdminExchangeRateController {

  private static final Logger log = LoggerFactory.getLogger(AdminExchangeRateController.class);

  private final ExchangeRateImportService exchangeRateImportService;

  public AdminExchangeRateController(ExchangeRateImportService exchangeRateImportService) {
    this.exchangeRateImportService = exchangeRateImportService;
  }

  @Operation(
      summary = "Import latest available rates from FRED",
      description =
          "Retrieve latest un-imported exchange rates from FRED - manually triggers daily cron"
              + " job")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExchangeRateImportResultResponse.class))),
      })
  @PostMapping(path = "/import", produces = "application/json")
  public ExchangeRateImportResultResponse importLatestExchangeRates() {
    log.info("Received importLatestExchangeRates request");

    var exchangeRateImportResult = exchangeRateImportService.importLatestExchangeRates();
    return ExchangeRateImportResultResponse.from(exchangeRateImportResult);
  }
}
