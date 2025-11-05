package com.bleurubin.budgetanalyzer.currency.dto;

import java.time.Instant;
import java.time.LocalDate;

public record ExchangeRateImportResult(
    int newRecords,
    int updatedRecords,
    int skippedRecords,
    LocalDate earliestExchangeRateDate,
    LocalDate latestExchangeRateDate,
    Instant timestamp) {
  public ExchangeRateImportResult(
      int newRecords,
      int updatedRecords,
      int skippedRecords,
      LocalDate earliestExchangeRateDate,
      LocalDate latestExchangeRateDate) {
    this(
        newRecords,
        updatedRecords,
        skippedRecords,
        earliestExchangeRateDate,
        latestExchangeRateDate,
        Instant.now());
  }
}
