package com.bleurubin.budgetanalyzer.currency.service.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bleurubin.budgetanalyzer.currency.client.fred.FredClient;
import com.bleurubin.budgetanalyzer.currency.client.fred.response.FredSeriesObservationsResponse;
import com.bleurubin.budgetanalyzer.currency.domain.CurrencySeries;

/**
 * FRED (Federal Reserve Economic Data) implementation of ExchangeRateProvider.
 *
 * <p>Fetches exchange rate data from the St. Louis Federal Reserve FRED API for any configured
 * currency series.
 */
@Service
public class FredExchangeRateProvider implements ExchangeRateProvider {

  private static final Logger log = LoggerFactory.getLogger(FredExchangeRateProvider.class);

  private final FredClient fredClient;

  /**
   * Constructs a new FredExchangeRateProvider.
   *
   * @param fredClient The FRED API client
   */
  public FredExchangeRateProvider(FredClient fredClient) {
    this.fredClient = fredClient;
  }

  @Override
  public Map<LocalDate, BigDecimal> getExchangeRates(
      CurrencySeries currencySeries, LocalDate startDate) {
    log.info(
        "Fetching exchange rates from FRED - currencyCode: {}, seriesId: {}, startDate: {}",
        currencySeries.getCurrencyCode(),
        currencySeries.getProviderSeriesId(),
        startDate);

    var response =
        fredClient.getSeriesObservationsData(currencySeries.getProviderSeriesId(), startDate);
    var observations = response.observations();

    return observations.stream()
        .filter(FredSeriesObservationsResponse.Observation::hasValue)
        .collect(
            Collectors.toMap(
                FredSeriesObservationsResponse.Observation::date,
                FredSeriesObservationsResponse.Observation::getValueAsBigDecimal));
  }

  @Override
  public boolean validateSeriesExists(String providerSeriesId) {
    log.debug("Validating provider series ID exists: {}", providerSeriesId);
    return fredClient.seriesExists(providerSeriesId);
  }
}
