package org.budgetanalyzer.currency.service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Compact, forward-filled daily exchange-rate series for one currency.
 *
 * <p>The series covers every calendar day from {@code startDate}, the date of the first source
 * rate, to the date of the last source rate. The constant fields are stored once. For each day,
 * {@code rates} holds the rate already normalized to USD as base and {@code publishedDateOffsets}
 * holds the number of days since that rate was published, so {@code 0} marks a day with a source
 * rate.
 *
 * <p>This is the cached value of the exchange-rate series cache. It contains only types that
 * Jackson writes and reads without type ids.
 *
 * @param baseCurrency base currency, always USD
 * @param targetCurrency target (foreign) currency
 * @param providerSeriesId provider series ID (e.g., DEXTHUS, DEXUSAL)
 * @param startDate date of the first source rate and of the first element
 * @param rates one USD-based rate per day, starting at {@code startDate}
 * @param publishedDateOffsets days between each day and the published date of its rate
 */
public record ExchangeRateSeries(
    Currency baseCurrency,
    Currency targetCurrency,
    String providerSeriesId,
    LocalDate startDate,
    List<BigDecimal> rates,
    List<Integer> publishedDateOffsets) {

  /**
   * Compact constructor with validation.
   *
   * @param baseCurrency base currency, always USD
   * @param targetCurrency target (foreign) currency
   * @param providerSeriesId provider series ID
   * @param startDate date of the first source rate
   * @param rates one USD-based rate per day
   * @param publishedDateOffsets days between each day and the published date of its rate
   */
  public ExchangeRateSeries {
    Objects.requireNonNull(baseCurrency, "baseCurrency cannot be null");
    Objects.requireNonNull(targetCurrency, "targetCurrency cannot be null");
    Objects.requireNonNull(providerSeriesId, "providerSeriesId cannot be null");
    Objects.requireNonNull(startDate, "startDate cannot be null");
    rates = List.copyOf(rates);
    publishedDateOffsets = List.copyOf(publishedDateOffsets);
    if (rates.isEmpty() || rates.size() != publishedDateOffsets.size()) {
      throw new IllegalArgumentException(
          "rates and publishedDateOffsets must be non-empty and the same size");
    }
  }

  /**
   * Returns the date of the last source rate, which is the date of the last element.
   *
   * @return last date covered by the series
   */
  public LocalDate endDate() {
    return startDate.plusDays(rates.size() - 1L);
  }

  /**
   * Returns whether a source rate was published on any day of the given range.
   *
   * @param fromDate first day of the range (inclusive)
   * @param toDate last day of the range (inclusive)
   * @return true if the range contains a day whose published-date offset is 0
   */
  public boolean containsSourceRateBetween(LocalDate fromDate, LocalDate toDate) {
    var firstIndex = Math.max(0L, ChronoUnit.DAYS.between(startDate, fromDate));
    var lastIndex = Math.min(rates.size() - 1L, ChronoUnit.DAYS.between(startDate, toDate));
    for (var index = firstIndex; index <= lastIndex; index++) {
      if (publishedDateOffsets.get((int) index) == 0) {
        return true;
      }
    }

    return false;
  }

  /**
   * Returns the exchange rate for one day on or after {@code startDate}.
   *
   * <p>Days after the end of the series receive the last element, with its published date.
   *
   * @param date day to return (must not be before {@code startDate})
   * @return exchange rate data for the day
   */
  public ExchangeRateData exchangeRateOn(LocalDate date) {
    var index = (int) Math.min(rates.size() - 1L, ChronoUnit.DAYS.between(startDate, date));
    var publishedDate = startDate.plusDays(index - (long) publishedDateOffsets.get(index));

    return new ExchangeRateData(
        baseCurrency, targetCurrency, date, rates.get(index), publishedDate, providerSeriesId);
  }
}
