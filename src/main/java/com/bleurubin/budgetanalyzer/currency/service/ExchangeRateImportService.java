package com.bleurubin.budgetanalyzer.currency.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Currency;

import org.springframework.core.io.Resource;

import com.bleurubin.budgetanalyzer.currency.dto.ImportResult;
import com.bleurubin.service.exception.BusinessException;

public interface ExchangeRateImportService {

  boolean hasExchangeRateData();

  ImportResult importExchangeRates(
      InputStream inputStream, String fileName, Currency targetCurrency);

  ImportResult importLatestExchangeRates();

  default ImportResult importExchangeRatesFromResource(Resource resource, Currency targetCurrency) {
    try (InputStream inputStream = resource.getInputStream()) {
      return importExchangeRates(inputStream, resource.getFilename(), targetCurrency);
    } catch (IOException e) {
      throw new BusinessException(
          "Failed to import exchange rates: " + e.getMessage(),
          CurrencyServiceError.CSV_PARSING_ERROR.name(),
          e);
    }
  }
}
