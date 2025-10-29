package com.bleurubin.budgetanalyzer.currency.api.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;

import io.swagger.v3.oas.annotations.media.Schema;

import com.bleurubin.budgetanalyzer.currency.dto.ExchangeRateDTO;

@Schema(description = "Exchange rate ")
public record ExchangeRateResponse(
    @Schema(
            description = "Base currency for conversion (currently only USD supported)",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "USD")
        Currency baseCurrency,
    @Schema(
            description = "Target currency for conversion (currently only THB supported)",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "THB")
        Currency targetCurrency,
    @Schema(
            description = "Date for the given rate",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "32.68")
        LocalDate date,
    @Schema(
            description = "Exchange rate for the given date",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "32.68")
        BigDecimal rate,
    @Schema(
            description =
                "There was no exchange rate for the given date so it was inferred from the last previous date that had a rate",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "true")
        boolean inferred) {

  public static ExchangeRateResponse from(ExchangeRateDTO dto) {
    return new ExchangeRateResponse(
        dto.baseCurrency(), dto.targetCurrency(), dto.date(), dto.rate(), dto.isInferredRate());
  }
}
