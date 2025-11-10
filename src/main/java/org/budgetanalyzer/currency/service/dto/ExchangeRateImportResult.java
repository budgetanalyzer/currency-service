package org.budgetanalyzer.currency.service.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Result of an exchange rate import operation for a specific currency.
 *
 * <p>This record contains statistics and metadata about the import operation, including the
 * currency information, counts of new, updated, and skipped records, as well as the date range of
 * the imported data.
 */
public record ExchangeRateImportResult(
    String currencyCode,
    String providerSeriesId,
    int newRecords,
    int updatedRecords,
    int skippedRecords,
    LocalDate earliestExchangeRateDate,
    LocalDate latestExchangeRateDate,
    Instant timestamp) {

  /**
   * Convenience constructor that automatically sets timestamp to current time.
   *
   * @param currencyCode The ISO 4217 currency code
   * @param providerSeriesId The provider series ID used for this import
   * @param newRecords Number of new records created
   * @param updatedRecords Number of existing records updated
   * @param skippedRecords Number of records skipped
   * @param earliestExchangeRateDate Earliest exchange rate date in the import
   * @param latestExchangeRateDate Latest exchange rate date in the import
   */
  public ExchangeRateImportResult(
      String currencyCode,
      String providerSeriesId,
      int newRecords,
      int updatedRecords,
      int skippedRecords,
      LocalDate earliestExchangeRateDate,
      LocalDate latestExchangeRateDate) {
    this(
        currencyCode,
        providerSeriesId,
        newRecords,
        updatedRecords,
        skippedRecords,
        earliestExchangeRateDate,
        latestExchangeRateDate,
        Instant.now());
  }
}
