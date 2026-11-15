package org.budgetanalyzer.currency.service;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.budgetanalyzer.currency.config.CacheConfig;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.dto.ExchangeRateData;
import org.budgetanalyzer.currency.service.dto.ExchangeRateSeries;
import org.budgetanalyzer.service.exception.BusinessException;

/**
 * Builds and caches the forward-filled exchange-rate series of one currency.
 *
 * <p>The cache key is the currency code, so the number of cache entries is bounded by the number of
 * currencies, independent of the date ranges that callers request. {@link ExchangeRateService}
 * slices requested ranges from the cached series. Keeping this cached method on a separate bean
 * lets calls from {@link ExchangeRateService} pass through the caching proxy.
 */
@Service
public class ExchangeRateSeriesService {

  private final ExchangeRateRepository exchangeRateRepository;

  /**
   * Constructs a new ExchangeRateSeriesService.
   *
   * @param exchangeRateRepository The exchange rate data access repository
   */
  public ExchangeRateSeriesService(ExchangeRateRepository exchangeRateRepository) {
    this.exchangeRateRepository = exchangeRateRepository;
  }

  /**
   * Returns the dense exchange-rate series of a currency from its first to its last source rate.
   *
   * <p>Every stored rate is loaded in date order and normalized to USD as base. Days without a
   * source rate, such as weekends and holidays, carry the previous source rate and its published
   * date. The result is cached under the currency code; exceptions are not cached.
   *
   * @param currencyCode ISO 4217 code of the foreign currency
   * @return dense series of the currency
   * @throws BusinessException with {@code NO_EXCHANGE_RATE_DATA_AVAILABLE} if the currency has no
   *     exchange rates
   */
  @Transactional(readOnly = true)
  @Cacheable(cacheNames = CacheConfig.EXCHANGE_RATE_SERIES_CACHE, key = "#currencyCode")
  public ExchangeRateSeries getExchangeRateSeries(String currencyCode) {
    var exchangeRates =
        exchangeRateRepository.findByCurrencySeriesCurrencyCodeOrderByDateAsc(currencyCode);
    if (exchangeRates.isEmpty()) {
      throw new BusinessException(
          "No exchange rate data available for currency: " + currencyCode,
          CurrencyServiceError.NO_EXCHANGE_RATE_DATA_AVAILABLE.name());
    }

    var firstExchangeRate = exchangeRates.getFirst();
    var providerSeriesId = firstExchangeRate.getCurrencySeries().getProviderSeriesId();
    var dayCount =
        (int)
                ChronoUnit.DAYS.between(
                    firstExchangeRate.getDate(), exchangeRates.getLast().getDate())
            + 1;
    var rates = new ArrayList<BigDecimal>(dayCount);
    var publishedDateOffsets = new ArrayList<Integer>(dayCount);

    ExchangeRateData previousExchangeRateData = null;
    for (var exchangeRate : exchangeRates) {
      var exchangeRateData =
          ExchangeRateData.from(exchangeRate, exchangeRate.getDate(), providerSeriesId);
      if (previousExchangeRateData != null) {
        for (var date = previousExchangeRateData.date().plusDays(1);
            date.isBefore(exchangeRateData.date());
            date = date.plusDays(1)) {
          rates.add(previousExchangeRateData.rate());
          publishedDateOffsets.add(
              (int) ChronoUnit.DAYS.between(previousExchangeRateData.date(), date));
        }
      }
      rates.add(exchangeRateData.rate());
      publishedDateOffsets.add(0);
      previousExchangeRateData = exchangeRateData;
    }

    return new ExchangeRateSeries(
        previousExchangeRateData.baseCurrency(),
        previousExchangeRateData.targetCurrency(),
        providerSeriesId,
        firstExchangeRate.getDate(),
        rates,
        publishedDateOffsets);
  }
}
