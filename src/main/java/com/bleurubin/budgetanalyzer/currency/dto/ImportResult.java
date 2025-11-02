package com.bleurubin.budgetanalyzer.currency.dto;

import java.time.Instant;

public record ImportResult(
    int newRecords, int updatedRecords, int skippedRecords, Instant timestamp) {
  public ImportResult(int newRecords, int updatedRecords, int skippedRecords) {
    this(newRecords, updatedRecords, skippedRecords, Instant.now());
  }
}
