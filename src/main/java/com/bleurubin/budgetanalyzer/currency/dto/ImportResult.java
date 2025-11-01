package com.bleurubin.budgetanalyzer.currency.dto;

import java.time.Instant;

public record ImportResult(
    int totalRowsParsed,
    int skippedRows,
    int newRecords,
    int updatedRecords,
    int skippedRecords,
    Instant timestamp) {
  public ImportResult(
      int totalRowsParsed,
      int filteredRows,
      int newRecords,
      int updatedRecords,
      int skippedRecords) {
    this(totalRowsParsed, filteredRows, newRecords, updatedRecords, skippedRecords, Instant.now());
  }

  public int totalProcessed() {
    return newRecords + updatedRecords + skippedRecords;
  }

  public int validRows() {
    return totalRowsParsed - skippedRows;
  }
}
