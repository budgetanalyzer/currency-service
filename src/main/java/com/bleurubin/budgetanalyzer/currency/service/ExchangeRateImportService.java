package com.bleurubin.budgetanalyzer.currency.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;
import com.bleurubin.budgetanalyzer.currency.dto.ImportResult;
import com.bleurubin.budgetanalyzer.currency.repository.ExchangeRateRepository;
import com.bleurubin.budgetanalyzer.currency.service.provider.ExchangeRateProvider;
import com.bleurubin.service.exception.BusinessException;

@Service
public class ExchangeRateImportService {

  private static final Logger log = LoggerFactory.getLogger(ExchangeRateImportService.class);

  // The base currency will always be USD but i wanted to keep the column in the database
  // in case the design needs to change in the future.
  private static final Currency BASE_CURRENCY = Currency.getInstance("USD");
  private static final Currency THB = Currency.getInstance("THB");

  private final ExchangeRateProvider exchangeRateProvider;
  private final ExchangeRateRepository exchangeRateRepository;

  public ExchangeRateImportService(
      ExchangeRateProvider exchangeRateProvider, ExchangeRateRepository exchangeRateRepository) {
    this.exchangeRateProvider = exchangeRateProvider;
    this.exchangeRateRepository = exchangeRateRepository;
  }

  public boolean hasExchangeRateData() {
    return exchangeRateRepository
        .findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(BASE_CURRENCY, THB)
        .isPresent();
  }

  @Transactional
  public ImportResult importLatestExchangeRates() {
    // we will take this as a method parameter when we support more currencies
    var targetCurrency = THB;
    var startDate = determineStartDate(targetCurrency);

    return importExchangeRates(targetCurrency, startDate);
  }

  private ImportResult importExchangeRates(Currency targetCurrency, LocalDate startDate) {
    try {
      log.info(
          "Importing exchange rates targetCurrency: {} startDate: {}", targetCurrency, startDate);

      var dateRateMap =
          exchangeRateProvider.getExchangeRates(BASE_CURRENCY, targetCurrency, startDate);

      if (dateRateMap.isEmpty()) {
        log.warn("No exchange rates provided");
        return new ImportResult(0, 0, 0);
      }

      var exchangeRates = buildExchangeRates(dateRateMap, targetCurrency);
      return saveExchangeRates(exchangeRates, targetCurrency);
    } catch (BusinessException businessException) {
      throw businessException;
    } catch (Exception e) {
      throw new BusinessException(
          "Failed to import exchange rates: " + e.getMessage(),
          CurrencyServiceError.CSV_PARSING_ERROR.name(),
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
      log.info("No existing exchange rates found - importing full history");
      return null;
    }

    // Normal case: Start from day after last stored rate
    var lastDate = mostRecent.get().getDate();
    var nextDate = lastDate.plusDays(1);

    log.info("Last exchange rate date: {}, starting import from: {}", lastDate, nextDate);

    return nextDate;
  }

  private List<ExchangeRate> buildExchangeRates(
      Map<LocalDate, BigDecimal> dateRateMap, Currency targetCurrency) {
    return dateRateMap.entrySet().stream()
        .map(rate -> buildExchangeRate(rate.getKey(), rate.getValue(), targetCurrency))
        .toList();
  }

  private ExchangeRate buildExchangeRate(LocalDate date, BigDecimal rate, Currency targetCurrency) {
    var rv = new ExchangeRate();
    rv.setBaseCurrency(BASE_CURRENCY);
    rv.setTargetCurrency(targetCurrency);
    rv.setDate(date);
    rv.setRate(rate);

    return rv;
  }

  private ImportResult saveExchangeRates(List<ExchangeRate> rates, Currency targetCurrency) {
    var newCount = 0;
    var updatedCount = 0;
    var skippedCount = 0;

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

    log.info("Save complete: {} new, {} updated, {} skipped", newCount, updatedCount, skippedCount);

    return new ImportResult(newCount, updatedCount, skippedCount);
  }
}
