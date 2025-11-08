package com.bleurubin.budgetanalyzer.currency.api.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

import com.bleurubin.budgetanalyzer.currency.service.dto.ExchangeRateData;

@Schema(description = "Exchange rate response")
public record ExchangeRateResponse(
    @Schema(
            description = "Base currency for conversion (currently only USD supported)",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "USD")
        String baseCurrency,
    @Schema(
            description = "Target currency for conversion (currently only THB supported)",
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

  public static ExchangeRateResponse from(ExchangeRateData exchangeRateData) {
    return new ExchangeRateResponse(
        exchangeRateData.baseCurrency().getCurrencyCode(),
        exchangeRateData.targetCurrency().getCurrencyCode(),
        exchangeRateData.date(),
        exchangeRateData.rate(),
        exchangeRateData.publishedDate());
  }
}
