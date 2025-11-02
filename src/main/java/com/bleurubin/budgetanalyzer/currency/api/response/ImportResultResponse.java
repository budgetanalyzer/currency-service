package com.bleurubin.budgetanalyzer.currency.api.response;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.bleurubin.budgetanalyzer.currency.dto.ImportResult;

@Schema(description = "Import result response")
public record ImportResultResponse(
    @Schema(
            description = "Number of new exchange rates created",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "10")
        int newRecords,
    @Schema(
            description =
                "Number of exchange rates that were updated due to having a different rate than what we have stored- should never happen, rates shouldn't change",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "32.68")
        int updatedRecords,
    @Schema(
            description =
                "Number of rows in the csv that already had matching exchange rates created in the database",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2025-10-31")
        int skippedRecords,
    @Schema(
            description = "Timestamp of the import",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2025-10-31")
        Instant timestamp) {

  public static ImportResultResponse from(ImportResult importResult) {
    return new ImportResultResponse(
        importResult.newRecords(),
        importResult.updatedRecords(),
        importResult.skippedRecords(),
        importResult.timestamp());
  }
}
