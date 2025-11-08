package com.bleurubin.budgetanalyzer.currency.service.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Result of an exchange rate import operation.
 *
 * <p>This record contains statistics and metadata about the import operation, including counts of
 * new, updated, and skipped records, as well as the date range of the imported data.
 */
public record ExchangeRateImportResult(
    int newRecords,
    int updatedRecords,
    int skippedRecords,
    LocalDate earliestExchangeRateDate,
    LocalDate latestExchangeRateDate,
    Instant timestamp) {

  /**
   * Convenience constructor that automatically sets timestamp to current time.
   *
   * @param newRecords Number of new records created
   * @param updatedRecords Number of existing records updated
   * @param skippedRecords Number of records skipped
   * @param earliestExchangeRateDate Earliest exchange rate date in the import
   * @param latestExchangeRateDate Latest exchange rate date in the import
   */
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
