package com.bleurubin.budgetanalyzer.currency.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bleurubin.budgetanalyzer.currency.client.FredClient;
import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;
import com.bleurubin.budgetanalyzer.currency.dto.ImportResult;
import com.bleurubin.budgetanalyzer.currency.repository.ExchangeRateRepository;
import com.bleurubin.budgetanalyzer.currency.service.CurrencyServiceError;
import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateImportService;
import com.bleurubin.core.csv.CsvData;
import com.bleurubin.core.csv.CsvParser;
import com.bleurubin.service.exception.BusinessException;
import com.bleurubin.service.exception.ServiceException;
import com.bleurubin.service.exception.ServiceUnavailableException;

@Service
public class ExchangeRateImportServiceImpl implements ExchangeRateImportService {

  private static final Logger log = LoggerFactory.getLogger(ExchangeRateImportServiceImpl.class);

  private static final long MAX_FRED_FILE_SIZE = 307200;
  private static final long BYTES_PER_KB = 1024;
  private static final long BYTES_PER_DAY_ESTIMATE = 20;
  private static final double SIZE_TOLERANCE_FACTOR = 4.0; // Allow 4x the expected size
  private static final String FRED_FORMAT = "FRED";

  // The base currency will always be USD but i wanted to keep the column in the database
  // in case the design needs to change in the future.
  private static final Currency BASE_CURRENCY = Currency.getInstance("USD");
  private static final Currency THB = Currency.getInstance("THB");

  // if we decide to support multiple currencies we'll create a map of currency -> seriesId
  private static final String THB_SERIES_ID = "DEXTHUS";

  private final ExchangeRateRepository exchangeRateRepository;
  private final CsvParser csvParser;
  private final FredClient fredClient;
  private final CsvExchangeRateMapper exchangeRateMapper = new CsvExchangeRateMapper();

  public ExchangeRateImportServiceImpl(
      ExchangeRateRepository exchangeRateRepository, CsvParser csvParser, FredClient fredClient) {
    this.exchangeRateRepository = exchangeRateRepository;
    this.csvParser = csvParser;
    this.fredClient = fredClient;
  }

  @Override
  public boolean hasExchangeRateData() {
    return exchangeRateRepository.count() > 0;
  }

  @Override
  @Transactional
  public ImportResult importExchangeRates(
      InputStream inputStream, String fileName, Currency targetCurrency) {
    try {
      log.info("Importing csv file: {} targetCurrency: {}", fileName, targetCurrency);

      var csvData = csvParser.parseCsvInputStream(inputStream, fileName, FRED_FORMAT);
      var totalRows = csvData.rows().size();
      var exchangeRates = buildExchangeRates(csvData, targetCurrency);
      var skippedRows = totalRows - exchangeRates.size();

      log.info(
          "Parsed {} CSV rows: {} valid rates, {} skipped (missing rates)",
          totalRows,
          exchangeRates.size(),
          skippedRows);

      if (exchangeRates.isEmpty()) {
        log.warn("No valid exchange rates found in CSV");
        return new ImportResult(totalRows, skippedRows, 0, 0, 0);
      }

      return saveExchangeRates(exchangeRates, totalRows, skippedRows);
    } catch (BusinessException businessException) {
      throw businessException;
    } catch (Exception e) {
      throw new BusinessException(
          "Failed to import exchange rates: " + e.getMessage(),
          CurrencyServiceError.CSV_PARSING_ERROR.name(),
          e);
    }
  }

  @Override
  @Transactional
  public ImportResult importLatestExchangeRates() {
    var startDate = determineStartDate();
    log.info("Importing exchange rates starting from: {}", startDate);

    try {
      var resource = fredClient.getExchangeRateDataAsResource(THB_SERIES_ID, startDate);
      validateFredFileSize(resource, startDate);

      return importExchangeRatesFromResource(resource, THB);
    } catch (ServiceException se) {
      throw se;
    } catch (Exception e) {
      throw new ServiceUnavailableException("Error importing exchange rates: " + e.getMessage(), e);
    }
  }

  private LocalDate determineStartDate() {
    // Get the most recent exchange rate date from database
    var mostRecent = exchangeRateRepository.findTopByOrderByDateDesc();

    if (mostRecent.isEmpty()) {
      // Edge case 1: No data exists - import everything
      log.info("No existing exchange rates found - importing full history");
      return null;
    }

    // Normal case: Start from day after last stored rate
    var lastDate = mostRecent.get().getDate();
    var nextDate = lastDate.plusDays(1);

    log.info("Last exchange rate date: {}, starting import from: {}", lastDate, nextDate);

    return nextDate;
  }

  private List<ExchangeRate> buildExchangeRates(CsvData csvData, Currency targetCurrency) {
    // some rows in the FRED files have dates and no rates, mapper returns optional so we
    // can filter those out
    return csvData.rows().stream()
        .map(csvRow -> exchangeRateMapper.map(csvRow, BASE_CURRENCY, targetCurrency))
        .flatMap(Optional::stream)
        .toList();
  }

  private ImportResult saveExchangeRates(
      List<ExchangeRate> rates, int totalRows, int filteredRows) {
    // if empty db call saveAll
    var newCount = 0;
    var updatedCount = 0;
    var skippedCount = 0;

    // Get the most recent exchange rate date from database, if empty this is an initial import
    var isInitialImport = exchangeRateRepository.findTopByOrderByDateDesc().isEmpty();

    if (isInitialImport) {
      exchangeRateRepository.saveAll(rates);
      newCount = rates.size();
    } else {
      for (ExchangeRate rate : rates) {
        var existing =
            exchangeRateRepository.findByBaseCurrencyAndTargetCurrencyAndDate(
                rate.getBaseCurrency(), rate.getTargetCurrency(), rate.getDate());

        if (existing.isEmpty()) {
          exchangeRateRepository.save(rate);
          newCount++;
        } else if (existing.get().getRate().compareTo(rate.getRate()) != 0) {
          var exchangeRate = existing.get();
          log.warn(
              "Warning, rate changed. Updating rate for date: {} old rate: {} new rate: {}",
              exchangeRate.getDate(),
              exchangeRate.getRate(),
              rate.getRate());

          exchangeRate.setRate(rate.getRate());
          exchangeRateRepository.save(exchangeRate);
          updatedCount++;
        } else {
          skippedCount++;
        }
      }
    }

    log.info("Save complete: {} new, {} updated, {} skipped", newCount, updatedCount, skippedCount);

    return new ImportResult(totalRows, filteredRows, newCount, updatedCount, skippedCount);
  }

  public static void validateFredFileSize(Resource resource, LocalDate startDate)
      throws IOException {
    var sizeInBytes = resource.contentLength();
    if (sizeInBytes < 0) {
      throw new IOException("Unable to determine FRED file size");
    }

    // Check absolute maximum
    if (sizeInBytes > MAX_FRED_FILE_SIZE) {
      throw new IllegalStateException(
          String.format(
              "FRED file size (%d KB) exceeds maximum allowed size (%d KB)",
              sizeInBytes / BYTES_PER_KB, MAX_FRED_FILE_SIZE / BYTES_PER_KB));
    }

    if (startDate == null) {
      return;
    }

    // Check proportional size based on date range
    var daysSinceStart = ChronoUnit.DAYS.between(startDate, LocalDate.now());
    if (daysSinceStart > 0) {
      var expectedSizeBytes = getExpectedSizeBytes(startDate, daysSinceStart, sizeInBytes);
      log.debug(
          "FRED file size validation passed: {} bytes for {} days (expected ~{} bytes)",
          sizeInBytes,
          daysSinceStart,
          expectedSizeBytes);
    }
  }

  private static long getExpectedSizeBytes(
      LocalDate startDate, long daysSinceStart, long sizeInBytes) {
    var expectedSizeBytes = daysSinceStart * BYTES_PER_DAY_ESTIMATE;
    var maxExpectedSizeBytes = (long) (expectedSizeBytes * SIZE_TOLERANCE_FACTOR);

    if (sizeInBytes > maxExpectedSizeBytes) {
      throw new IllegalStateException(
          String.format(
              "FRED file size (%d bytes) is disproportionate to requested date range. "
                  + "Requested data from %s (%d days) but file size suggests full historical series. "
                  + "Expected approximately %d bytes, got %d bytes.",
              sizeInBytes, startDate, daysSinceStart, expectedSizeBytes, sizeInBytes));
    }
    return expectedSizeBytes;
  }
}
