package org.budgetanalyzer.currency.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.budgetanalyzer.currency.config.CacheConfig;
import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.domain.ExchangeRate;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.dto.ExchangeRateImportResult;
import org.budgetanalyzer.currency.service.provider.ExchangeRateProvider;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.service.exception.ServiceException;

/**
 * Service for importing exchange rates from external data providers.
 *
 * <p>Handles fetching exchange rates from providers, deduplication, and persisting to the database
 * with cache invalidation.
 */
@Service
public class ExchangeRateImportService {

  private static final Logger log = LoggerFactory.getLogger(ExchangeRateImportService.class);

  // The base currency will always be USD but i wanted to keep the column in the database
  // in case the design needs to change in the future.
  private static final Currency BASE_CURRENCY = Currency.getInstance("USD");

  private final ExchangeRateProvider exchangeRateProvider;
  private final ExchangeRateRepository exchangeRateRepository;
  private final CurrencySeriesRepository currencySeriesRepository;

  /**
   * Constructs a new ExchangeRateImportService.
   *
   * @param exchangeRateProvider The provider to fetch exchange rates from
   * @param exchangeRateRepository The exchange rate data access repository
   * @param currencySeriesRepository The currency series data access repository
   */
  public ExchangeRateImportService(
      ExchangeRateProvider exchangeRateProvider,
      ExchangeRateRepository exchangeRateRepository,
      CurrencySeriesRepository currencySeriesRepository) {
    this.exchangeRateProvider = exchangeRateProvider;
    this.exchangeRateRepository = exchangeRateRepository;
    this.currencySeriesRepository = currencySeriesRepository;
  }

  /**
   * Imports exchange rates only for enabled currency series that are missing data. Called at
   * startup by CurrencyServiceStartupConfig.
   *
   * <p>This method intelligently checks each enabled currency series and only imports data for
   * those that have no exchange rates in the database. This is more efficient than importing for
   * all currencies when most already have data.
   *
   * <p><b>Cache Eviction Strategy (allEntries = true):</b> This method evicts the entire cache
   * rather than using targeted eviction for specific currencies. While this approach is more
   * aggressive than necessary (e.g., importing THB evicts EUR cache entries), it was chosen for:
   *
   * <ul>
   *   <li><b>Simplicity:</b> No custom wildcard eviction logic required
   *   <li><b>Consistency guarantee:</b> Eliminates any possibility of stale data
   *   <li><b>Acceptable trade-off:</b> This runs at startup when cache is already empty
   * </ul>
   *
   * <p><b>Performance Impact:</b> First queries after startup will experience cache misses
   * (50-200ms vs 1-3ms), but subsequent queries benefit from cached results. This trade-off is
   * acceptable since startup is infrequent and users don't typically query immediately after
   * deployment.
   *
   * @return list of import results, one per currency series
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.EXCHANGE_RATES_CACHE, allEntries = true)
  public List<ExchangeRateImportResult> importMissingExchangeRates() {
    log.info("Checking if all enabled currency series have exchange rate data...");

    var seriesToImport = getSeriesWithMissingData();
    return importExchangeRatesForSeries(seriesToImport);
  }

  /**
   * Imports the latest exchange rates from the external provider for all enabled currencies. Called
   * from a daily scheduled job in ExchangeRateImportScheduler.
   *
   * <p><b>Cache Eviction Strategy (allEntries = true):</b> This method evicts the entire cache
   * rather than using targeted eviction for specific currencies. This approach is appropriate here
   * because:
   *
   * <ul>
   *   <li><b>Batch operation:</b> Imports ALL enabled currencies simultaneously, so most cache
   *       entries would be evicted anyway
   *   <li><b>Simplicity:</b> No custom wildcard eviction logic or currency-by-currency tracking
   *   <li><b>Timing:</b> Runs once per day
   *   <li><b>Consistency guarantee:</b> Eliminates any possibility of stale data across currencies
   * </ul>
   *
   * <p><b>Performance Impact:</b> First queries after the scheduled import will experience cache
   * misses (50-200ms vs 1-3ms), but this is acceptable since the job runs once per day and the
   * cache quickly warms up with morning traffic.
   *
   * @return list of import results, one per currency series
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.EXCHANGE_RATES_CACHE, allEntries = true)
  public List<ExchangeRateImportResult> importLatestExchangeRates() {
    var seriesToImport = getAllEnabledSeries();
    return importExchangeRatesForSeries(seriesToImport);
  }

  /**
   * Gets all enabled currency series for import.
   *
   * @return List of all enabled currency series
   */
  private List<CurrencySeries> getAllEnabledSeries() {
    var enabledCurrencies = currencySeriesRepository.findByEnabledTrue();
    log.info("Found {} enabled currency series for import", enabledCurrencies.size());

    return enabledCurrencies;
  }

  /**
   * Gets only the enabled currency series that are missing exchange rate data.
   *
   * @return List of enabled currency series with no existing exchange rates
   */
  private List<CurrencySeries> getSeriesWithMissingData() {
    var enabledCurrencies = currencySeriesRepository.findByEnabledTrue();

    return enabledCurrencies.stream()
        .filter(
            series -> {
              if (!hasExchangeRateData(series)) {
                log.info(
                    "Currency series {} is missing exchange rate data - importing",
                    series.getCurrencyCode());
                return true;
              } else {
                log.info(
                    "Currency series {} already has exchange rate data - skipping",
                    series.getCurrencyCode());
                return false;
              }
            })
        .toList();
  }

  /**
   * Imports exchange rates for a list of currency series.
   *
   * <p>This method handles the common import logic for both missing data and latest exchange rate
   * imports. It iterates through the provided series and imports exchange rates for each, returning
   * individual results.
   *
   * @param seriesToImport List of currency series to import exchange rates for
   * @return List of import results, one per currency series
   */
  private List<ExchangeRateImportResult> importExchangeRatesForSeries(
      List<CurrencySeries> seriesToImport) {
    if (seriesToImport.isEmpty()) {
      log.warn("No currency series to import - skipping");
      return List.of();
    }

    var results =
        seriesToImport.stream()
            .map(
                currencySeries -> {
                  var targetCurrency = Currency.getInstance(currencySeries.getCurrencyCode());
                  var startDate = determineStartDate(targetCurrency);
                  return importExchangeRates(currencySeries, startDate);
                })
            .toList();

    // Log summary
    var totalNew = results.stream().mapToInt(ExchangeRateImportResult::newRecords).sum();
    var totalUpdated = results.stream().mapToInt(ExchangeRateImportResult::updatedRecords).sum();
    var totalSkipped = results.stream().mapToInt(ExchangeRateImportResult::skippedRecords).sum();

    log.info(
        "Import complete: {} currencies processed, {} new, {} updated, {} skipped",
        results.size(),
        totalNew,
        totalUpdated,
        totalSkipped);

    return results;
  }

  /**
   * Imports exchange rates for a specific currency series by ID.
   *
   * <p>This method is called by the message consumer when a new currency is created. It fetches the
   * currency series, determines the appropriate start date, and imports exchange rates from the
   * external provider.
   *
   * <p><b>Cache Eviction Strategy (allEntries = true):</b> This method evicts the entire cache
   * rather than targeting only the newly imported currency. While this is more aggressive than
   * necessary (importing CAD evicts EUR/JPY cache entries), it was chosen for:
   *
   * <ul>
   *   <li><b>Simplicity:</b> No custom wildcard eviction logic required
   *   <li><b>Consistency guarantee:</b> Eliminates any possibility of stale data
   *   <li><b>Infrequent operation:</b> New currencies are added rarely (monthly/quarterly), making
   *       the performance impact negligible
   *   <li><b>Code maintainability:</b> Single eviction strategy across all import methods
   * </ul>
   *
   * <p><b>Performance Impact:</b> Existing cached queries for other currencies will experience one
   * cache miss (50-200ms) after a new currency is added, then return to normal cached performance
   * (1-3ms). Given the infrequency of adding new currencies, this trade-off is acceptable.
   *
   * <p><b>Alternative Considered:</b> Targeted eviction for single currency would require custom
   * Redis SCAN/KEYS logic to match patterns like "CAD:*:*". The complexity isn't justified for an
   * operation that occurs rarely.
   *
   * @param currencySeriesId The ID of the currency series to import rates for
   * @return Import result with counts of new, updated, and skipped rates
   * @throws ResourceNotFoundException if currency series not found
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.EXCHANGE_RATES_CACHE, allEntries = true)
  public ExchangeRateImportResult importExchangeRatesForSeries(Long currencySeriesId) {
    var currencySeries =
        currencySeriesRepository
            .findById(currencySeriesId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Currency series not found with id: " + currencySeriesId));

    var targetCurrency = Currency.getInstance(currencySeries.getCurrencyCode());
    var startDate = determineStartDate(targetCurrency);

    return importExchangeRates(currencySeries, startDate);
  }

  /**
   * Imports exchange rates for a specific currency series.
   *
   * <p>This method:
   *
   * <ol>
   *   <li>Fetches exchange rates from the provider using the currency series configuration
   *   <li>Builds ExchangeRate entities with BOTH currencySeries (foreign key) AND targetCurrency
   *       (denormalized)
   *   <li>Saves rates to database with deduplication logic
   * </ol>
   *
   * <p><b>Denormalization:</b> Each ExchangeRate is linked to its CurrencySeries (foreign key) but
   * also stores targetCurrency directly for query performance. Both fields are set to ensure
   * consistency.
   *
   * @param currencySeries The currency series configuration to import rates for
   * @param startDate The date to start importing from (null for full history)
   * @return Import result with counts of new, updated, and skipped rates
   */
  private ExchangeRateImportResult importExchangeRates(
      CurrencySeries currencySeries, LocalDate startDate) {
    var targetCurrency = Currency.getInstance(currencySeries.getCurrencyCode());

    try {
      log.info(
          "Importing exchange rates for {} (series: {}) startDate: {}",
          currencySeries.getCurrencyCode(),
          currencySeries.getProviderSeriesId(),
          startDate);

      var dateRateMap = exchangeRateProvider.getExchangeRates(currencySeries, startDate);

      if (dateRateMap.isEmpty()) {
        log.warn("No exchange rates provided for {}", currencySeries.getCurrencyCode());
        return new ExchangeRateImportResult(
            currencySeries.getCurrencyCode(),
            currencySeries.getProviderSeriesId(),
            0,
            0,
            0,
            null,
            null);
      }

      var exchangeRates = buildExchangeRates(dateRateMap, currencySeries, targetCurrency);
      return saveExchangeRates(exchangeRates, currencySeries);
    } catch (ServiceException serviceException) {
      throw serviceException;
    } catch (Exception e) {
      throw new ServiceException(
          "Failed to import exchange rates for "
              + currencySeries.getCurrencyCode()
              + ": "
              + e.getMessage(),
          e);
    }
  }

  private LocalDate determineStartDate(Currency targetCurrency) {
    // Get the most recent exchange rate date from database
    var mostRecent =
        exchangeRateRepository.findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(
            BASE_CURRENCY, targetCurrency);

    if (mostRecent.isEmpty()) {
      // Edge case 1: No data exists - import everything
      log.info(
          "No existing exchange rates found for currency code: {} - importing full history",
          targetCurrency.getCurrencyCode());
      return null;
    }

    // Normal case: Start from day after last stored rate
    var lastDate = mostRecent.get().getDate();
    var nextDate = lastDate.plusDays(1);

    log.info("Last exchange rate date: {}, starting import from: {}", lastDate, nextDate);

    return nextDate;
  }

  /**
   * Checks if a specific currency series has exchange rate data in the database.
   *
   * @param currencySeries The currency series to check
   * @return true if the currency series has at least one exchange rate, false otherwise
   */
  private boolean hasExchangeRateData(CurrencySeries currencySeries) {
    return exchangeRateRepository.countByCurrencySeries(currencySeries) > 0;
  }

  /**
   * Builds a list of ExchangeRate entities from provider data.
   *
   * <p><b>Important - Denormalization:</b> This method sets BOTH:
   *
   * <ul>
   *   <li><b>currencySeries</b> - Foreign key relationship for referential integrity and
   *       traceability
   *   <li><b>targetCurrency</b> - Denormalized currency code for query performance
   * </ul>
   *
   * <p>Both fields must be set to maintain consistency between the relationship and the
   * denormalized field.
   *
   * @param dateRateMap Map of dates to exchange rates from the provider
   * @param currencySeries The currency series configuration (foreign key reference)
   * @param targetCurrency The target currency (denormalized for performance)
   * @return List of ExchangeRate entities ready to save
   */
  private List<ExchangeRate> buildExchangeRates(
      Map<LocalDate, BigDecimal> dateRateMap,
      CurrencySeries currencySeries,
      Currency targetCurrency) {
    return dateRateMap.entrySet().stream()
        .map(
            rate ->
                buildExchangeRate(rate.getKey(), rate.getValue(), currencySeries, targetCurrency))
        .toList();
  }

  /**
   * Builds a single ExchangeRate entity.
   *
   * <p><b>Denormalization Pattern:</b> This method demonstrates the denormalization pattern used
   * throughout the exchange rate system:
   *
   * <pre>
   * exchangeRate.setCurrencySeries(currencySeries);    // Foreign key for integrity
   * exchangeRate.setTargetCurrency(targetCurrency);    // Denormalized for performance
   * </pre>
   *
   * <p><b>Why both fields?</b>
   *
   * <ul>
   *   <li><b>currencySeries:</b> Provides referential integrity, traceability to provider
   *       configuration
   *   <li><b>targetCurrency:</b> Enables fast queries without JOINs, prevents N+1 lazy loading
   * </ul>
   *
   * @param date The date for this exchange rate
   * @param rate The exchange rate value
   * @param currencySeries The currency series (foreign key)
   * @param targetCurrency The target currency (denormalized)
   * @return Populated ExchangeRate entity
   */
  private ExchangeRate buildExchangeRate(
      LocalDate date, BigDecimal rate, CurrencySeries currencySeries, Currency targetCurrency) {
    var exchangeRate = new ExchangeRate();

    // Set foreign key relationship for referential integrity
    exchangeRate.setCurrencySeries(currencySeries);

    exchangeRate.setBaseCurrency(BASE_CURRENCY);

    // Set denormalized currency code for query performance
    // Note: This duplicates currencySeries.currencyCode, but prevents lazy loading in API queries
    exchangeRate.setTargetCurrency(targetCurrency);

    exchangeRate.setDate(date);
    exchangeRate.setRate(rate);

    return exchangeRate;
  }

  private ExchangeRateImportResult saveExchangeRates(
      List<ExchangeRate> rates, CurrencySeries currencySeries) {
    var newCount = 0;
    var updatedCount = 0;
    var skippedCount = 0;
    var targetCurrency = Currency.getInstance(currencySeries.getCurrencyCode());

    // Get the most recent exchange rate date from database, if empty this is an initial import
    var isInitialImport =
        exchangeRateRepository
            .findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(BASE_CURRENCY, targetCurrency)
            .isEmpty();

    // if empty database just saveAll nothing to compare to
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

    // Track earliest and latest dates
    var earliestDate =
        rates.stream().map(ExchangeRate::getDate).min(LocalDate::compareTo).orElse(null);
    var latestDate =
        rates.stream().map(ExchangeRate::getDate).max(LocalDate::compareTo).orElse(null);

    log.info(
        "Save complete: {} new, {} updated, {} skipped, earliest date: {}, latest date: {}",
        newCount,
        updatedCount,
        skippedCount,
        earliestDate,
        latestDate);

    return new ExchangeRateImportResult(
        currencySeries.getCurrencyCode(),
        currencySeries.getProviderSeriesId(),
        newCount,
        updatedCount,
        skippedCount,
        earliestDate,
        latestDate);
  }
}
