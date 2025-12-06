package org.budgetanalyzer.currency.api;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
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
import org.budgetanalyzer.service.permission.AuthorizationContext;
import org.budgetanalyzer.service.permission.PermissionClient;
import org.budgetanalyzer.service.security.SecurityContextUtil;

@Tag(name = "Currency Series Handler", description = "Endpoints for querying currency series")
@RestController
@RequestMapping(path = "/v1/currencies")
public class CurrencySeriesController {

  private static final Logger log = LoggerFactory.getLogger(CurrencySeriesController.class);

  private final CurrencyService currencyService;
  private final PermissionClient permissionClient;

  public CurrencySeriesController(
      CurrencyService currencyService, PermissionClient permissionClient) {
    this.currencyService = currencyService;
    this.permissionClient = permissionClient;
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
          boolean enabledOnly,
      HttpServletRequest request) {

    var userId =
        SecurityContextUtil.getCurrentUserId()
            .orElseThrow(() -> new AccessDeniedException("Authentication required"));

    var ctx = AuthorizationContext.fromRequest(userId, request);

    if (!permissionClient.canPerform(ctx, "read", "currency")) {
      log.warn("Access denied for user {} to read currencies", userId);
      throw new AccessDeniedException("Access denied");
    }

    log.info("Getting all currency series for user {}, enabledOnly: {}", userId, enabledOnly);

    return currencyService.getAll(enabledOnly).stream().map(CurrencySeriesResponse::from).toList();
  }
}
