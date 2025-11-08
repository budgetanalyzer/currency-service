package com.bleurubin.budgetanalyzer.currency.service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;

import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;

/**
 * Internal DTO for exchange rate data transfer between service layers.
 *
 * <p>This record is used for internal service-to-service communication and should not be exposed in
 * the API layer.
 */
public record ExchangeRateData(
    Currency baseCurrency,
    Currency targetCurrency,
    LocalDate date,
    BigDecimal rate,
    LocalDate publishedDate) {

  /**
   * Compact constructor with validation.
   *
   * @param baseCurrency Base currency
   * @param targetCurrency Target currency
   * @param date Exchange rate date
   * @param rate Exchange rate value
   * @param publishedDate Published date
   */
  public ExchangeRateData {
    Objects.requireNonNull(baseCurrency, "baseCurrency cannot be null");
    Objects.requireNonNull(targetCurrency, "targetCurrency cannot be null");
    Objects.requireNonNull(date, "date cannot be null");
    Objects.requireNonNull(rate, "rate cannot be null");
    Objects.requireNonNull(publishedDate, "publishedDate cannot be null");
  }

  /**
   * Creates ExchangeRateData from ExchangeRate entity.
   *
   * @param exchangeRate Exchange rate entity
   * @param date Exchange rate date
   * @return ExchangeRateData instance
   */
  public static ExchangeRateData from(ExchangeRate exchangeRate, LocalDate date) {
    return new ExchangeRateData(
        exchangeRate.getBaseCurrency(),
        exchangeRate.getTargetCurrency(),
        date,
        exchangeRate.getRate(),
        exchangeRate.getDate());
  }
}
