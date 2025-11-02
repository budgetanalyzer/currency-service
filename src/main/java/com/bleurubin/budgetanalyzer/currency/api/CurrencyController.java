package com.bleurubin.budgetanalyzer.currency.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Currency Handler", description = "Endpoints for operations on currencies")
@RestController
@RequestMapping(path = "/currencies")
public class CurrencyController {

  private static final Logger log = LoggerFactory.getLogger(CurrencyController.class);

  private static final List<String> SUPPORTED_CURRENCIES = List.of("USD", "THB");

  @Operation(
      summary = "Get supported currencies",
      description = "Get a list of currencies that have exchange rate data")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = String.class)))),
      })
  @GetMapping(path = "", produces = "application/json")
  public List<String> getSupportedCurrencies() {
    log.info("Received getSupportedCurrencies request");
    return SUPPORTED_CURRENCIES;
  }
}
