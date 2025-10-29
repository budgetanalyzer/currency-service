package com.bleurubin.budgetanalyzer.currency.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;

import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;
import com.bleurubin.budgetanalyzer.currency.service.CurrencyServiceError;
import com.bleurubin.core.csv.CsvRow;
import com.bleurubin.service.exception.BusinessException;

public class CsvExchangeRateMapper {

  private static final String DATE_KEY = "observation_date";
  private static final String RATE_KEY = "DEXTHUS";

  public Optional<ExchangeRate> map(CsvRow csvRow, Currency baseCurrency, Currency targetCurrency) {
    var rowMap = csvRow.values();
    var dateString = rowMap.get(DATE_KEY);
    var rateString = rowMap.get(RATE_KEY);

    // some rows in the FRED files have dates and no rates, ignore as empty
    if (dateString == null || dateString.isBlank() || rateString == null || rateString.isBlank()) {
      return Optional.empty();
    }

    try {
      var rv = new ExchangeRate();
      rv.setBaseCurrency(baseCurrency);
      rv.setTargetCurrency(targetCurrency);
      rv.setDate(LocalDate.parse(dateString));
      rv.setRate(new BigDecimal(rateString));

      return Optional.of(rv);
    } catch (Exception e) {
      throw new BusinessException(
          String.format("Invalid value at line %d: '%s'", csvRow.lineNumber(), rowMap),
          CurrencyServiceError.CSV_PARSING_ERROR.name());
    }
  }
}
