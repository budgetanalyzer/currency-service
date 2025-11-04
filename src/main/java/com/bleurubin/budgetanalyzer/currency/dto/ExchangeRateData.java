package com.bleurubin.budgetanalyzer.currency.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;

import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;

public record ExchangeRateData(
    Currency baseCurrency,
    Currency targetCurrency,
    LocalDate date,
    BigDecimal rate,
    LocalDate publishedDate) {

  public ExchangeRateData {
    Objects.requireNonNull(baseCurrency, "baseCurrency cannot be null");
    Objects.requireNonNull(targetCurrency, "targetCurrency cannot be null");
    Objects.requireNonNull(date, "date cannot be null");
    Objects.requireNonNull(rate, "rate cannot be null");
    Objects.requireNonNull(publishedDate, "publishedDate cannot be null");
  }

  public static ExchangeRateData from(ExchangeRate e, LocalDate date) {
    return new ExchangeRateData(
        e.getBaseCurrency(), e.getTargetCurrency(), date, e.getRate(), e.getDate());
  }
}
