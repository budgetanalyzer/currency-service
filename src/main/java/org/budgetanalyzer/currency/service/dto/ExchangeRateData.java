package org.budgetanalyzer.currency.service.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;

import org.budgetanalyzer.currency.domain.ExchangeRate;
import org.budgetanalyzer.currency.service.CurrencyConstants;

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
    LocalDate publishedDate,
    String providerSeriesId) {

  /**
   * Compact constructor with validation.
   *
   * @param baseCurrency Base currency
   * @param targetCurrency Target currency
   * @param date Exchange rate date
   * @param rate Exchange rate value
   * @param publishedDate Published date
   * @param providerSeriesId Provider series ID (e.g., DEXTHUS, DEXUSAL)
   */
  public ExchangeRateData {
    Objects.requireNonNull(baseCurrency, "baseCurrency cannot be null");
    Objects.requireNonNull(targetCurrency, "targetCurrency cannot be null");
    Objects.requireNonNull(date, "date cannot be null");
    Objects.requireNonNull(rate, "rate cannot be null");
    Objects.requireNonNull(publishedDate, "publishedDate cannot be null");
    Objects.requireNonNull(providerSeriesId, "providerSeriesId cannot be null");
  }

  /**
   * Creates ExchangeRateData from ExchangeRate entity, normalizing to USD as base currency.
   *
   * <p>When the stored baseCurrency is not USD (e.g., for DEXUS* series where base=foreign), this
   * method inverts the rate so that API responses always show USD as base.
   *
   * <p><b>Example:</b> AUD stored as base=AUD, target=USD, rate=0.66 becomes base=USD, target=AUD,
   * rate=1.5152 (1/0.66)
   *
   * @param exchangeRate Exchange rate entity
   * @param date Exchange rate date (may differ from publishedDate for gap-filled dates)
   * @param providerSeriesId Provider series ID (e.g., DEXTHUS, DEXUSAL)
   * @return ExchangeRateData instance with USD as base currency
   */
  public static ExchangeRateData from(
      ExchangeRate exchangeRate, LocalDate date, String providerSeriesId) {
    var needsInversion = !exchangeRate.getBaseCurrency().equals(CurrencyConstants.USD);

    // When base is not USD, invert rate to normalize to USD-as-base
    var rate =
        needsInversion
            ? BigDecimal.ONE.divide(exchangeRate.getRate(), 4, RoundingMode.HALF_UP)
            : exchangeRate.getRate();

    // Always return with USD as base, foreign as target
    var targetCurrency =
        needsInversion
            ? exchangeRate.getBaseCurrency() // Foreign was stored as base
            : exchangeRate.getTargetCurrency(); // Foreign was stored as target

    return new ExchangeRateData(
        CurrencyConstants.USD,
        targetCurrency,
        date,
        rate,
        exchangeRate.getDate(),
        providerSeriesId);
  }
}
