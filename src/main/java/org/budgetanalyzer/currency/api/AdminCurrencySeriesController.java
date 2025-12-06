package org.budgetanalyzer.currency.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.budgetanalyzer.currency.api.request.CurrencySeriesCreateRequest;
import org.budgetanalyzer.currency.api.request.CurrencySeriesUpdateRequest;
import org.budgetanalyzer.currency.api.response.CurrencySeriesResponse;
import org.budgetanalyzer.currency.service.CurrencyService;
import org.budgetanalyzer.service.api.ApiErrorResponse;
import org.budgetanalyzer.service.permission.AuthorizationContext;
import org.budgetanalyzer.service.permission.PermissionClient;
import org.budgetanalyzer.service.security.SecurityContextUtil;

/** Admin endpoints for currency series management. */
@Tag(
    name = "Admin - Currency Series Handler",
    description = "Admin endpoints for creating and updating currency series")
@RestController
@RequestMapping(path = "/v1/admin/currencies")
public class AdminCurrencySeriesController {

  private static final Logger log = LoggerFactory.getLogger(AdminCurrencySeriesController.class);

  private final CurrencyService currencyService;
  private final PermissionClient permissionClient;

  public AdminCurrencySeriesController(
      CurrencyService currencyService, PermissionClient permissionClient) {
    this.currencyService = currencyService;
    this.permissionClient = permissionClient;
  }

  private void requireAdminPermission(HttpServletRequest request, String action) {
    var userId =
        SecurityContextUtil.getCurrentUserId()
            .orElseThrow(() -> new AccessDeniedException("Authentication required"));

    var ctx = AuthorizationContext.fromRequest(userId, request);

    if (!permissionClient.canPerform(ctx, action, "currency")) {
      log.warn("Access denied for user {} to {} currency (admin)", userId, action);
      throw new AccessDeniedException("Access denied");
    }
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
                          name = "Invalid Provider Series ID",
                          summary = "Provider series ID does not exist in FRED",
                          value =
                              """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "Provider series ID 'INVALID_SERIES' does not exist in the external provider",
                        "code": "INVALID_PROVIDER_SERIES_ID"
                      }
                      """)
                    }))
      })
  @PostMapping(produces = "application/json", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<CurrencySeriesResponse> create(
      @Valid @RequestBody CurrencySeriesCreateRequest request, HttpServletRequest httpRequest) {
    requireAdminPermission(httpRequest, "admin");
    log.info("Creating currency series for currency code: {}", request.currencyCode());

    var entity = request.toEntity();
    var created = currencyService.create(entity);

    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(created.getId())
            .toUri();

    return ResponseEntity.created(location).body(CurrencySeriesResponse.from(created));
  }

  @Operation(
      summary = "Get currency series by ID",
      description = "Retrieve a currency series by its unique identifier")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Currency series retrieved successfully",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = CurrencySeriesResponse.class))),
        @ApiResponse(responseCode = "404", description = "Currency series not found")
      })
  @GetMapping(path = "/{id}", produces = "application/json")
  public CurrencySeriesResponse getById(@PathVariable Long id, HttpServletRequest httpRequest) {
    requireAdminPermission(httpRequest, "admin");
    log.info("Retrieving currency series id: {}", id);

    var currencySeries = currencyService.getById(id);
    return CurrencySeriesResponse.from(currencySeries);
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
      @PathVariable Long id,
      @Valid @RequestBody CurrencySeriesUpdateRequest request,
      HttpServletRequest httpRequest) {
    requireAdminPermission(httpRequest, "admin");
    log.info("Updating currency series id: {}", id);

    var updated = currencyService.update(id, request.enabled());
    return CurrencySeriesResponse.from(updated);
  }
}
