package com.bleurubin.budgetanalyzer.currency.api;

import java.util.List;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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

import com.bleurubin.budgetanalyzer.currency.api.request.CurrencySeriesCreateRequest;
import com.bleurubin.budgetanalyzer.currency.api.request.CurrencySeriesUpdateRequest;
import com.bleurubin.budgetanalyzer.currency.api.response.CurrencySeriesResponse;
import com.bleurubin.budgetanalyzer.currency.service.CurrencyService;
import com.bleurubin.service.api.ApiErrorResponse;

@Tag(name = "Currency Handler", description = "Endpoints for operations on currencies")
@RestController
@RequestMapping(path = "/v1/currencies")
public class CurrencyController {

  private static final Logger log = LoggerFactory.getLogger(CurrencyController.class);

  private final CurrencyService currencyService;

  public CurrencyController(CurrencyService currencyService) {
    this.currencyService = currencyService;
  }

  @Operation(
      summary = "Create a new currency series",
      description =
          "Create a new currency series. This endpoint is typically used when FRED adds support "
              + "for new currency pairs. The 23 commonly-used currencies are already "
              + "pre-populated and can be enabled via the PUT endpoint.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "201",
            description = "Currency series created successfully",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = CurrencySeriesResponse.class))),
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
                          name = "Duplicate Currency Code",
                          summary = "Currency code already exists",
                          value =
                              """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "Currency code 'EUR' already exists",
                        "code": "DUPLICATE_CURRENCY_CODE"
                      }
                      """),
                      @ExampleObject(
                          name = "Invalid ISO 4217 Code",
                          summary = "Currency code is not a valid ISO 4217 code",
                          value =
                              """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "Invalid ISO 4217 currency code: XXX",
                        "code": "INVALID_ISO_4217_CODE"
                      }
                      """),
                      @ExampleObject(
                          name = "Currency Not Supported",
                          summary = "Currency code is not supported",
                          value =
                              """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "Currency code 'ZZZ' is not supported. Supported currencies: [AUD, BRL, CAD, ...]",
                        "code": "CURRENCY_NOT_SUPPORTED"
                      }
                      """)
                    }))
      })
  @PostMapping(produces = "application/json", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public CurrencySeriesResponse create(@Valid @RequestBody CurrencySeriesCreateRequest request) {
    log.info("Creating currency series for currency code: {}", request.currencyCode());

    var entity = request.toEntity();
    var created = currencyService.create(entity);

    return CurrencySeriesResponse.from(created);
  }

  @Operation(
      summary = "Get currency series by ID",
      description = "Retrieve a specific currency series by its unique identifier")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Currency series found",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = CurrencySeriesResponse.class))),
        @ApiResponse(responseCode = "404", description = "Currency series not found")
      })
  @GetMapping(path = "/{id}", produces = "application/json")
  public CurrencySeriesResponse getById(@PathVariable Long id) {
    log.info("Getting currency series by id: {}", id);

    var currencySeries = currencyService.getById(id);
    return CurrencySeriesResponse.from(currencySeries);
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

  @Operation(
      summary = "Update currency series",
      description =
          "Update an existing currency series (currency code and provider series ID are immutable, "
              + "only enabled status can be changed)")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Currency series updated successfully",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = CurrencySeriesResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "404", description = "Currency series not found")
      })
  @PutMapping(path = "/{id}", produces = "application/json", consumes = "application/json")
  public CurrencySeriesResponse update(
      @PathVariable Long id, @Valid @RequestBody CurrencySeriesUpdateRequest request) {
    log.info("Updating currency series id: {}", id);

    var updated = currencyService.update(id, request.enabled());
    return CurrencySeriesResponse.from(updated);
  }
}
