package org.budgetanalyzer.currency.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import org.springframework.stereotype.Service;

import org.budgetanalyzer.currency.config.CacheConfig;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.service.dto.ExchangeRateData;
import org.budgetanalyzer.service.exception.BusinessException;

/**
 * Service for querying exchange rates.
 *
 * <p>Slices requested date ranges from the cached per-currency series provided by {@link
 * ExchangeRateSeriesService}.
 */
@Service
public class ExchangeRateService {

  private final CurrencySeriesRepository currencySeriesRepository;
  private final ExchangeRateSeriesService exchangeRateSeriesService;
  private final Clock clock;

  /**
   * Constructs a new ExchangeRateService.
   *
   * @param currencySeriesRepository The currency series data access repository
   * @param exchangeRateSeriesService The service providing cached per-currency series
   * @param clock The clock that determines today's date in UTC
   */
  public ExchangeRateService(
      CurrencySeriesRepository currencySeriesRepository,
      ExchangeRateSeriesService exchangeRateSeriesService,
      Clock clock) {
    this.currencySeriesRepository = currencySeriesRepository;
    this.exchangeRateSeriesService = exchangeRateSeriesService;
    this.clock = clock;
  }

  /**
   * Retrieves daily exchange rates for a target currency within a date range.
   *
   * <p>The result has one entry per calendar day. Days without a source rate carry the previous
   * source rate and its published date.
   *
   * <p><b>Date semantics:</b>
   *
   * <ul>
   *   <li>A null {@code startDate} means the date of the first source rate.
   *   <li>The effective end date is today (UTC, from the injected {@link Clock}) when {@code
   *       endDate} is null, otherwise the earlier of {@code endDate} and tomorrow (today plus one
   *       day). After the last source rate, the last rate is forward-filled up to the effective end
   *       date.
   *   <li>The result is empty when the effective start date is after the effective end date, or
   *       when no source rate falls within the effective range.
   * </ul>
   *
   * <p><b>Caching:</b> This method is not cached. The enabled check runs on every call, and the
   * per-currency series comes from the {@link CacheConfig#EXCHANGE_RATE_SERIES_CACHE} cache keyed
   * by currency code, so request date ranges never create cache keys. The cache fails open: when
   * Redis is unavailable, the series is loaded from the database. A series read from the database
   * before an import committed can be cached after that import's eviction; the one-hour TTL ({@link
   * CacheConfig#EXCHANGE_RATES_TTL}) bounds how long such a stale series is served.
   *
   * @param targetCurrency the currency to convert USD to
   * @param startDate optional start date (inclusive)
   * @param endDate optional end date (inclusive)
   * @return list of exchange rates with gaps filled using forward-fill logic
   * @throws IllegalArgumentException if both dates are supplied and {@code startDate} is after
   *     {@code endDate}
   * @throws BusinessException with {@code CURRENCY_NOT_ENABLED}, {@code
   *     NO_EXCHANGE_RATE_DATA_AVAILABLE} or {@code START_DATE_OUT_OF_RANGE}
   */
  public List<ExchangeRateData> getExchangeRates(
      Currency targetCurrency, LocalDate startDate, LocalDate endDate) {
    // Defensive programming: validate the original date range before the end-date cap
    if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
      throw new IllegalArgumentException("Start date must be before or equal to end date");
    }

    var currencyCode = targetCurrency.getCurrencyCode();
    if (currencySeriesRepository.findByCurrencyCodeAndEnabledTrue(currencyCode).isEmpty()) {
      throw new BusinessException(
          "Currency is not enabled: " + currencyCode,
          CurrencyServiceError.CURRENCY_NOT_ENABLED.name());
    }

    var exchangeRateSeries = exchangeRateSeriesService.getExchangeRateSeries(currencyCode);

    if (startDate != null && startDate.isBefore(exchangeRateSeries.startDate())) {
      throw new BusinessException(
          "Exchange rates for "
              + currencyCode
              + " not available before "
              + exchangeRateSeries.startDate(),
          CurrencyServiceError.START_DATE_OUT_OF_RANGE.name());
    }

    var today = LocalDate.now(clock);
    // transaction-service accepts transaction dates through tomorrow
    var latestEndDate = today.plusDays(1);
    LocalDate effectiveEndDate;
    if (endDate == null) {
      effectiveEndDate = today;
    } else if (endDate.isAfter(latestEndDate)) {
      effectiveEndDate = latestEndDate;
    } else {
      effectiveEndDate = endDate;
    }
    var effectiveStartDate = startDate != null ? startDate : exchangeRateSeries.startDate();

    // nothing available for the date range isn't the same as NO_EXCHANGE_RATE_DATA_AVAILABLE
    if (effectiveStartDate.isAfter(effectiveEndDate)
        || !exchangeRateSeries.containsSourceRateBetween(effectiveStartDate, effectiveEndDate)) {
      return List.of();
    }

    var exchangeRates = new ArrayList<ExchangeRateData>();
    for (var date = effectiveStartDate; !date.isAfter(effectiveEndDate); date = date.plusDays(1)) {
      exchangeRates.add(exchangeRateSeries.exchangeRateOn(date));
    }

    return exchangeRates;
  }
}
