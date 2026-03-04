package org.budgetanalyzer.currency.service.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;

import org.junit.jupiter.api.Test;

import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.domain.ExchangeRate;
import org.budgetanalyzer.currency.fixture.TestConstants;

/**
 * Unit tests for {@link ExchangeRateData}.
 *
 * <p>Tests the rate inversion logic that normalizes all rates to USD as base currency.
 */
class ExchangeRateDataTest {

  private static final LocalDate DATE = LocalDate.of(2024, 1, 15);

  // ===========================================================================================
  // Rate Inversion Tests
  // ===========================================================================================

  @Test
  void fromInvertsRateWhenBaseCurrencyIsNotUsd() {
    // AUD stored as base (DEXUS* pattern: "USD per foreign")
    // Stored: 1 AUD = 0.66 USD
    // API should return: 1 USD = 1.5152 AUD
    var exchangeRate =
        createExchangeRate(
            Currency.getInstance("AUD"), // base (stored)
            Currency.getInstance("USD"), // target (stored)
            new BigDecimal("0.6600"));

    var result = ExchangeRateData.from(exchangeRate, DATE, "DEXUSAL");

    assertThat(result.baseCurrency().getCurrencyCode()).isEqualTo("USD");
    assertThat(result.targetCurrency().getCurrencyCode()).isEqualTo("AUD");
    assertThat(result.rate()).isCloseTo(new BigDecimal("1.5152"), within(new BigDecimal("0.0001")));
    assertThat(result.date()).isEqualTo(DATE);
    assertThat(result.publishedDate()).isEqualTo(DATE);
    assertThat(result.providerSeriesId()).isEqualTo("DEXUSAL");
  }

  @Test
  void fromInvertsRateForEurSeries() {
    // EUR stored as base (DEXUS* pattern: "USD per foreign")
    // Stored: 1 EUR = 0.85 USD
    // API should return: 1 USD = 1.1765 EUR
    var exchangeRate =
        createExchangeRate(
            Currency.getInstance("EUR"), // base (stored)
            Currency.getInstance("USD"), // target (stored)
            new BigDecimal("0.8500"));

    var result = ExchangeRateData.from(exchangeRate, DATE, "DEXUSEU");

    assertThat(result.baseCurrency().getCurrencyCode()).isEqualTo("USD");
    assertThat(result.targetCurrency().getCurrencyCode()).isEqualTo("EUR");
    assertThat(result.rate()).isCloseTo(new BigDecimal("1.1765"), within(new BigDecimal("0.0001")));
  }

  @Test
  void fromDoesNotInvertRateWhenBaseCurrencyIsUsd() {
    // THB stored with USD as base (DEX*US pattern: "foreign per USD")
    // Stored: 1 USD = 32.68 THB
    // API should return: 1 USD = 32.68 THB (no change)
    var exchangeRate =
        createExchangeRate(
            Currency.getInstance("USD"), // base (stored)
            Currency.getInstance("THB"), // target (stored)
            new BigDecimal("32.6800"));

    var result = ExchangeRateData.from(exchangeRate, DATE, "DEXTHUS");

    assertThat(result.baseCurrency().getCurrencyCode()).isEqualTo("USD");
    assertThat(result.targetCurrency().getCurrencyCode()).isEqualTo("THB");
    assertThat(result.rate()).isEqualByComparingTo(new BigDecimal("32.6800"));
    assertThat(result.date()).isEqualTo(DATE);
    assertThat(result.publishedDate()).isEqualTo(DATE);
    assertThat(result.providerSeriesId()).isEqualTo("DEXTHUS");
  }

  @Test
  void fromDoesNotInvertRateForJpySeries() {
    // JPY stored with USD as base (DEX*US pattern: "foreign per USD")
    // Stored: 1 USD = 140.50 JPY
    // API should return: 1 USD = 140.50 JPY (no change)
    var exchangeRate =
        createExchangeRate(
            Currency.getInstance("USD"), // base (stored)
            Currency.getInstance("JPY"), // target (stored)
            new BigDecimal("140.5000"));

    var result = ExchangeRateData.from(exchangeRate, DATE, "DEXJPUS");

    assertThat(result.baseCurrency().getCurrencyCode()).isEqualTo("USD");
    assertThat(result.targetCurrency().getCurrencyCode()).isEqualTo("JPY");
    assertThat(result.rate()).isEqualByComparingTo(new BigDecimal("140.5000"));
  }

  // ===========================================================================================
  // Date Handling Tests
  // ===========================================================================================

  @Test
  void fromUsesProvidedDateNotPublishedDate() {
    // When gap-filling, the requested date differs from the published date
    var publishedDate = LocalDate.of(2024, 1, 12); // Friday
    var requestedDate = LocalDate.of(2024, 1, 14); // Sunday (gap-filled)

    var exchangeRate =
        createExchangeRateWithDate(
            Currency.getInstance("USD"),
            Currency.getInstance("THB"),
            new BigDecimal("32.6800"),
            publishedDate);

    var result = ExchangeRateData.from(exchangeRate, requestedDate, "DEXTHUS");

    assertThat(result.date()).isEqualTo(requestedDate);
    assertThat(result.publishedDate()).isEqualTo(publishedDate);
  }

  // ===========================================================================================
  // Helper Methods
  // ===========================================================================================

  private static ExchangeRate createExchangeRate(
      Currency baseCurrency, Currency targetCurrency, BigDecimal rate) {
    return createExchangeRateWithDate(baseCurrency, targetCurrency, rate, DATE);
  }

  private static ExchangeRate createExchangeRateWithDate(
      Currency baseCurrency, Currency targetCurrency, BigDecimal rate, LocalDate date) {
    var exchangeRate = new ExchangeRate();
    exchangeRate.setBaseCurrency(baseCurrency);
    exchangeRate.setTargetCurrency(targetCurrency);
    exchangeRate.setRate(rate);
    exchangeRate.setDate(date);

    // Create a minimal currency series for the test
    var currencySeries = new CurrencySeries();
    currencySeries.setCurrencyCode(
        baseCurrency.equals(TestConstants.BASE_CURRENCY_USD)
            ? targetCurrency.getCurrencyCode()
            : baseCurrency.getCurrencyCode());
    exchangeRate.setCurrencySeries(currencySeries);

    return exchangeRate;
  }
}
