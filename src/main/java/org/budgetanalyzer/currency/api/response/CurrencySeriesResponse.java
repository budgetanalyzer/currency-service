package org.budgetanalyzer.currency.api.response;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.currency.domain.CurrencySeries;

/** Response DTO for currency series operations. */
@Schema(description = "Currency series response with mapping details")
public record CurrencySeriesResponse(
    @Schema(
            description = "Unique identifier",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        Long id,
    @Schema(
            description = "ISO 4217 three-letter currency code",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "EUR")
        String currencyCode,
    @Schema(
            description = "Exchange rate provider series identifier",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "DEXUSEU")
        String providerSeriesId,
    @Schema(
            description = "Whether this currency is enabled for exchange rate access",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "true")
        boolean enabled,
    @Schema(
            description = "Timestamp when this currency series was created",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2025-01-15T10:30:00Z")
        Instant createdAt,
    @Schema(
            description = "Timestamp when this currency series was last updated",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2025-01-15T14:45:00Z")
        Instant updatedAt) {

  /**
   * Create a response DTO from a domain entity.
   *
   * @param entity The currency series entity
   * @return CurrencySeriesResponse
   */
  public static CurrencySeriesResponse from(CurrencySeries entity) {
    return new CurrencySeriesResponse(
        entity.getId(),
        entity.getCurrencyCode(),
        entity.getProviderSeriesId(),
        entity.isEnabled(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
