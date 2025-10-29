package com.bleurubin.budgetanalyzer.currency.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;
import com.bleurubin.budgetanalyzer.currency.service.CsvService;
import com.bleurubin.budgetanalyzer.currency.service.CurrencyServiceError;
import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateService;
import com.bleurubin.core.csv.CsvData;
import com.bleurubin.core.csv.CsvParser;
import com.bleurubin.service.exception.BusinessException;

@Service
public class CsvServiceImpl implements CsvService {

  private static final Logger log = LoggerFactory.getLogger(CsvServiceImpl.class);

  private static final Currency DEFAULT_BASE_CURRENCY = Currency.getInstance("USD");

  private static final String FRED_FORMAT = "FRED";

  private final CsvParser csvParser;

  private final CsvExchangeRateMapper exchangeRateMapper;

  private final ExchangeRateService exchangeRateService;

  public CsvServiceImpl(CsvParser csvParser, ExchangeRateService exchangeRateService) {
    this.csvParser = csvParser;
    this.exchangeRateService = exchangeRateService;
    this.exchangeRateMapper = new CsvExchangeRateMapper();
  }

  @Override
  public List<ExchangeRate> importExchangeRates(MultipartFile file, Currency targetCurrency) {
    try {
      return importExchangeRates(file.getInputStream(), file.getOriginalFilename(), targetCurrency);
    } catch (IOException e) {
      throw new BusinessException(
          "Failed to import exchange rates: " + e.getMessage(),
          CurrencyServiceError.CSV_PARSING_ERROR.name(),
          e);
    }
  }

  public List<ExchangeRate> importExchangeRates(
      InputStream inputStream, String fileName, Currency targetCurrency) {
    try {
      log.info("Importing csv file: {} targetCurrency: {}", fileName, targetCurrency);

      var csvData = csvParser.parseCsvInputStream(inputStream, fileName, FRED_FORMAT);
      var importedExchangeRates = createExchangeRates(csvData, targetCurrency);

      log.info(
          "Successfully imported: {} total exchangeRates fileName: {} targetCurrency {}",
          importedExchangeRates.size(),
          fileName,
          targetCurrency);

      return importedExchangeRates;
    } catch (BusinessException businessException) {
      throw businessException;
    } catch (Exception e) {
      throw new BusinessException(
          "Failed to import exchange rates: " + e.getMessage(),
          CurrencyServiceError.CSV_PARSING_ERROR.name(),
          e);
    }
  }

  private List<ExchangeRate> createExchangeRates(CsvData csvData, Currency targetCurrency) {
    // some rows in the FRED files have dates and no rates, mapper returns optional so we can filter
    // those out
    var exchangeRates =
        csvData.rows().stream()
            .map(csvRow -> exchangeRateMapper.map(csvRow, DEFAULT_BASE_CURRENCY, targetCurrency))
            .flatMap(Optional::stream)
            .toList();

    return exchangeRateService.createExchangeRates(exchangeRates);
  }
}
