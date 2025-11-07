package com.bleurubin.budgetanalyzer.currency.service.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import com.bleurubin.budgetanalyzer.currency.domain.CurrencySeries;

/** Provider interface for fetching exchange rates from external data sources. */
public interface ExchangeRateProvider {

  /**
   * Retrieves exchange rates from the data source.
   *
   * @param currencySeries The currency series containing currency code and provider series ID
   * @param startDate The start date for the exchange rate data (null = fetch all)
   * @return Map of dates to exchange rates
   */
  Map<LocalDate, BigDecimal> getExchangeRates(CurrencySeries currencySeries, LocalDate startDate);

  /**
   * Validates that a provider series ID exists in the external data source.
   *
   * @param providerSeriesId The provider series ID to validate
   * @return true if the series exists, false if it does not exist
   * @throws com.bleurubin.service.exception.ClientException if there is a network error or API
   *     failure (not a "series not found" error)
   */
  boolean validateSeriesExists(String providerSeriesId);
}
