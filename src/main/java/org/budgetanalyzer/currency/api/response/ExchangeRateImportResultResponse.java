package org.budgetanalyzer.currency.api.response;

import java.time.Instant;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.currency.service.dto.ExchangeRateImportResult;

@Schema(description = "Exchange rate import result response for a specific currency")
public record ExchangeRateImportResultResponse(
    @Schema(
            description = "ISO 4217 currency code",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "EUR")
        String currencyCode,
    @Schema(
            description = "Provider series ID used for this import",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "DEXUSEU")
        String providerSeriesId,
    @Schema(
            description = "Number of new exchange rates created",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "10")
        int newRecords,
    @Schema(
            description =
                "Number of exchange rates that were updated due to having a different rate"
                    + " than what we have stored- should never happen, rates shouldn't change",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "0")
        int updatedRecords,
    @Schema(
            description =
                "Number of rows in the csv that already had matching exchange rates created in the"
                    + " database",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "5")
        int skippedRecords,
    @Schema(
            description = "Date of the earliest exchange rate in this import",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "2025-01-01")
        LocalDate earliestExchangeRateDate,
    @Schema(
            description = "Date of the latest exchange rate in this import",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "2025-10-31")
        LocalDate latestExchangeRateDate,
    @Schema(
            description = "Timestamp of the import execution",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2025-10-31T15:30:00Z")
        Instant timestamp) {

  public static ExchangeRateImportResultResponse from(
      ExchangeRateImportResult exchangeRateImportResult) {
    return new ExchangeRateImportResultResponse(
        exchangeRateImportResult.currencyCode(),
        exchangeRateImportResult.providerSeriesId(),
        exchangeRateImportResult.newRecords(),
        exchangeRateImportResult.updatedRecords(),
        exchangeRateImportResult.skippedRecords(),
        exchangeRateImportResult.earliestExchangeRateDate(),
        exchangeRateImportResult.latestExchangeRateDate(),
        exchangeRateImportResult.timestamp());
  }
}
