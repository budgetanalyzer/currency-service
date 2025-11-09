package com.bleurubin.budgetanalyzer.currency.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.bleurubin.budgetanalyzer.currency.domain.CurrencySeries;

/**
 * Request DTO for creating a new currency series.
 *
 * <p>This endpoint is typically used when FRED adds support for new currency pairs. The 23
 * commonly-used currencies are already pre-populated in the database and can be enabled via the PUT
 * endpoint.
 */
@Schema(description = "Request to create a new currency series")
public record CurrencySeriesCreateRequest(
    @Schema(
            description = "ISO 4217 three-letter currency code",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "EUR")
        @NotBlank(message = "Currency code is required")
        @Size(min = 3, max = 3, message = "Currency code must be exactly 3 characters")
        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency code must be 3 uppercase letters")
        String currencyCode,
    @Schema(
            description =
                "Exchange rate provider series identifier (e.g., DEXUSEU for EUR/USD). "
                    + "Must be a valid FRED series ID.",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "DEXUSEU")
        @NotBlank(message = "Provider series ID is required")
        @Size(max = 50, message = "Provider series ID must not exceed 50 characters")
        String providerSeriesId,
    @Schema(
            description = "Whether this currency is enabled for exchange rate access",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "true",
            defaultValue = "true")
        boolean enabled) {

  /**
   * Convert this request DTO to a domain entity.
   *
   * @return CurrencySeries entity
   */
  public CurrencySeries toEntity() {
    var entity = new CurrencySeries();
    entity.setCurrencyCode(currencyCode);
    entity.setProviderSeriesId(providerSeriesId);
    entity.setEnabled(enabled);

    return entity;
  }
}
