package org.budgetanalyzer.currency.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import org.budgetanalyzer.currency.api.response.CurrencySeriesResponse;
import org.budgetanalyzer.currency.service.CurrencyService;

@Tag(name = "Currency Series Handler", description = "Endpoints for querying currency series")
@RestController
@RequestMapping(path = "/v1/currencies")
public class CurrencySeriesController {

  private static final Logger log = LoggerFactory.getLogger(CurrencySeriesController.class);

  private final CurrencyService currencyService;

  public CurrencySeriesController(CurrencyService currencyService) {
    this.currencyService = currencyService;
  }

  @Operation(
      summary = "Get all currency series",
      description = "Retrieve all currency series, optionally filtered by enabled status")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    array =
                        @ArraySchema(
                            schema = @Schema(implementation = CurrencySeriesResponse.class))))
      })
  @GetMapping(produces = "application/json")
  public List<CurrencySeriesResponse> getAll(
      @Parameter(description = "Filter by enabled status (true = enabled only, false = all)")
          @RequestParam(required = false, defaultValue = "false")
          boolean enabledOnly) {
    log.info("Getting all currency series, enabledOnly: {}", enabledOnly);

    return currencyService.getAll(enabledOnly).stream().map(CurrencySeriesResponse::from).toList();
  }
}
