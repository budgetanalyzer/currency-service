package org.budgetanalyzer.currency.api;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.budgetanalyzer.currency.api.response.ExchangeRateImportResultResponse;
import org.budgetanalyzer.currency.service.ExchangeRateImportService;
import org.budgetanalyzer.service.permission.AuthorizationContext;
import org.budgetanalyzer.service.permission.PermissionClient;
import org.budgetanalyzer.service.security.SecurityContextUtil;

/** Admin endpoints for exchange rate management. */
@Tag(
    name = "Admin - Exchange Rates Handler",
    description = "Admin endpoints for importing and managing exchange rates")
@RestController
@RequestMapping(path = "/v1/admin/exchange-rates")
public class AdminExchangeRateController {

  private static final Logger log = LoggerFactory.getLogger(AdminExchangeRateController.class);

  private final ExchangeRateImportService exchangeRateImportService;
  private final PermissionClient permissionClient;

  public AdminExchangeRateController(
      ExchangeRateImportService exchangeRateImportService, PermissionClient permissionClient) {
    this.exchangeRateImportService = exchangeRateImportService;
    this.permissionClient = permissionClient;
  }

  @Operation(
      summary = "Import latest available rates from FRED",
      description =
          "Retrieve latest un-imported exchange rates from FRED for all enabled currencies -"
              + " manually triggers daily cron job")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    array =
                        @ArraySchema(
                            schema =
                                @Schema(implementation = ExchangeRateImportResultResponse.class)))),
      })
  @PostMapping(path = "/import", produces = "application/json")
  public List<ExchangeRateImportResultResponse> importLatestExchangeRates(
      HttpServletRequest request) {

    var userId =
        SecurityContextUtil.getCurrentUserId()
            .orElseThrow(() -> new AccessDeniedException("Authentication required"));

    var ctx = AuthorizationContext.fromRequest(userId, request);

    if (!permissionClient.canPerform(ctx, "admin", "currency")) {
      log.warn("Access denied for user {} to import exchange rates (admin)", userId);
      throw new AccessDeniedException("Access denied");
    }

    log.info("Received importLatestExchangeRates request from user {}", userId);

    var results = exchangeRateImportService.importLatestExchangeRates();
    return results.stream().map(ExchangeRateImportResultResponse::from).toList();
  }
}
