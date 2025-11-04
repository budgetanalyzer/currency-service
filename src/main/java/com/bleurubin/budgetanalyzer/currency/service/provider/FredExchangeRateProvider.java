package com.bleurubin.budgetanalyzer.currency.service.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bleurubin.budgetanalyzer.currency.client.fred.FredClient;
import com.bleurubin.budgetanalyzer.currency.client.fred.response.FredSeriesObservationsResponse;

/**
 * FRED (Federal Reserve Economic Data) implementation of ExchangeRateProvider.
 *
 * <p>Fetches exchange rate data from the St. Louis Federal Reserve FRED API. Currently supports USD
 * to THB exchange rates.
 */
@Service
public class FredExchangeRateProvider implements ExchangeRateProvider {

  private static final Logger log = LoggerFactory.getLogger(FredExchangeRateProvider.class);

  // The base currency will always be USD but i wanted to keep the column in the database
  // in case the design needs to change in the future.
  private static final Currency BASE_CURRENCY = Currency.getInstance("USD");
  private static final Currency THB = Currency.getInstance("THB");

  // if we decide to support multiple currencies we'll create a map of currency -> seriesId
  private static final String THB_SERIES_ID = "DEXTHUS";

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
      Currency baseCurrency, Currency targetCurrency, LocalDate startDate) {
    if (!THB.equals(targetCurrency)) {
      throw new IllegalArgumentException("Unsupported targetCurrency " + targetCurrency);
    }

    var response = fredClient.getSeriesObservationsData(THB_SERIES_ID, startDate);
    var observations = response.observations();

    return observations.stream()
        .filter(FredSeriesObservationsResponse.Observation::hasValue)
        .collect(
            Collectors.toMap(
                FredSeriesObservationsResponse.Observation::date,
                FredSeriesObservationsResponse.Observation::getValueAsBigDecimal));
  }
}
