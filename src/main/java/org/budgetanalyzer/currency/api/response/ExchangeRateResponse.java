package org.budgetanalyzer.currency.api.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.currency.service.dto.ExchangeRateData;

@Schema(description = "Exchange rate response")
public record ExchangeRateResponse(
    @Schema(
            description = "Base currency (1 unit of base = rate units of target)",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "USD")
        String baseCurrency,
    @Schema(
            description = "Target currency",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "THB")
        String targetCurrency,
    @Schema(
            description = "Date for the given rate",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2025-11-02")
        LocalDate date,
    @Schema(
            description = "Exchange rate for the given date",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "32.68")
        BigDecimal rate,
    @Schema(
            description =
                "Date the exchange rate was published. FRED doesn't publish rates for weekends"
                    + " and holidays, so we use the nearest previously published rate",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2025-10-31")
        LocalDate publishedDate) {

  /**
   * Creates an ExchangeRateResponse from ExchangeRateData.
   *
   * <p>ExchangeRateData already has USD normalized as base currency, so this is a direct mapping.
   *
   * @param data the exchange rate data (already normalized to USD as base)
   * @return the response
   */
  public static ExchangeRateResponse from(ExchangeRateData data) {
    return new ExchangeRateResponse(
        data.baseCurrency().getCurrencyCode(),
        data.targetCurrency().getCurrencyCode(),
        data.date(),
        data.rate(),
        data.publishedDate());
  }
}
