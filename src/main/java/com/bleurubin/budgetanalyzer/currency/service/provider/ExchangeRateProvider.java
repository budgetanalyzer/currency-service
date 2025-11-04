package com.bleurubin.budgetanalyzer.currency.service.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;

/** Provider interface for fetching exchange rates from external data sources. */
public interface ExchangeRateProvider {

  /**
   * Retrieves exchange rates from the data source.
   *
   * @param baseCurrency The base currency (e.g., USD)
   * @param targetCurrency The target currency to get exchange rates for
   * @param startDate The start date for the exchange rate data
   * @return Map of dates to exchange rates
   */
  Map<LocalDate, BigDecimal> getExchangeRates(
      Currency baseCurrency, Currency targetCurrency, LocalDate startDate);
}
