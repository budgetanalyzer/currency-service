package org.budgetanalyzer.currency.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import org.budgetanalyzer.currency.config.CacheConfig;
import org.budgetanalyzer.currency.domain.ExchangeRate;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.repository.spec.ExchangeRateSpecifications;
import org.budgetanalyzer.currency.service.dto.ExchangeRateData;
import org.budgetanalyzer.service.exception.BusinessException;

/**
 * Service for querying and managing exchange rates.
 *
 * <p>Provides business logic for retrieving exchange rates with gap-filling logic and Redis-based
 * distributed caching.
 */
@Service
public class ExchangeRateService {

  private final ExchangeRateRepository exchangeRateRepository;

  /**
   * Constructs a new ExchangeRateService.
   *
   * @param exchangeRateRepository The exchange rate data access repository
   */
  public ExchangeRateService(ExchangeRateRepository exchangeRateRepository) {
    this.exchangeRateRepository = exchangeRateRepository;
  }

  /**
   * Retrieves exchange rates for a target currency within a date range.
   *
   * <p><b>Caching Strategy:</b> Results are cached in Redis using a composite key that includes
   * currency code and date range parameters. This enables currency-specific cache isolation where
   * queries for different currencies are cached independently.
   *
   * <p><b>Cache Key Structure:</b>
   *
   * <ul>
   *   <li>Format: {@code {currencyCode}:{startDate}:{endDate}}
   *   <li>Examples:
   *       <ul>
   *         <li>{@code THB:2024-01-01:2024-12-31} - Full year query for Thai Baht
   *         <li>{@code EUR:2024-06-01:null} - Open-ended query from June onwards
   *         <li>{@code JPY:null:null} - All available data for Japanese Yen
   *       </ul>
   *   <li>Full Redis key: {@code currency-service:exchangeRates::THB:2024-01-01:2024-12-31}
   * </ul>
   *
   * <p><b>Cache Eviction:</b> The entire cache is evicted (all currencies) whenever exchange rates
   * are imported via {@code @CacheEvict(allEntries = true)} in ExchangeRateImportService. See
   * method-level documentation in that class for the rationale behind global eviction rather than
   * targeted currency-specific eviction.
   *
   * <p><b>Performance:</b>
   *
   * <ul>
   *   <li>Cache hit: 1-3ms response time
   *   <li>Cache miss: 50-200ms (database query + cache population)
   *   <li>Expected hit rate: 80-95% for common currency pairs
   * </ul>
   *
   * @param targetCurrency the currency to convert USD to
   * @param startDate optional start date (inclusive)
   * @param endDate optional end date (inclusive)
   * @return list of exchange rates with gaps filled using forward-fill logic
   */
  @Cacheable(
      cacheNames = CacheConfig.EXCHANGE_RATES_CACHE,
      key = "#targetCurrency.currencyCode + ':' + #startDate + ':' + #endDate")
  public List<ExchangeRateData> getExchangeRates(
      Currency targetCurrency, LocalDate startDate, LocalDate endDate) {
    // Defensive programming: validate date range constraint
    if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
      throw new IllegalArgumentException("Start date must be before or equal to end date");
    }

    // Business validation: check if data exists for currency
    var earliestDate = exchangeRateRepository.findEarliestDateByTargetCurrency(targetCurrency);
    if (earliestDate.isEmpty()) {
      throw new BusinessException(
          "No exchange rate data available for currency: " + targetCurrency.getCurrencyCode(),
          CurrencyServiceError.NO_EXCHANGE_RATE_DATA_AVAILABLE.name());
    }

    // Business validation: check if startDate is before earliest available data
    if (startDate != null && startDate.isBefore(earliestDate.get())) {
      throw new BusinessException(
          "Exchange rates for "
              + targetCurrency.getCurrencyCode()
              + " not available before "
              + earliestDate.get(),
          CurrencyServiceError.START_DATE_OUT_OF_RANGE.name());
    }

    var spec = buildSpecification(targetCurrency, startDate, endDate);

    var definedRates = exchangeRateRepository.findAll(spec, Sort.by("date").ascending());
    // nothing available for the date range isn't the same as NO_EXCHANGE_RATE_DATA_AVAILABLE
    if (definedRates.isEmpty()) {
      return List.of();
    }

    // If startDate is null, use the date of the first rate
    var effectiveStartDate = startDate != null ? startDate : definedRates.get(0).getDate();

    // If endDate is null, use the date of the last rate
    var effectiveEndDate =
        endDate != null ? endDate : definedRates.get(definedRates.size() - 1).getDate();

    return buildDenseExchangeRates(definedRates, effectiveStartDate, effectiveEndDate);
  }

  private Specification<ExchangeRate> buildSpecification(
      Currency targetCurrency, LocalDate startDate, LocalDate endDate) {
    var rv = ExchangeRateSpecifications.hasTargetCurrency(targetCurrency);

    if (startDate != null) {
      rv = rv.and(ExchangeRateSpecifications.dateGreaterThanOrEqual(startDate));
    }

    if (endDate != null) {
      rv = rv.and(ExchangeRateSpecifications.dateLessThanOrEqual(endDate));
    }

    return rv;
  }

  private List<ExchangeRateData> buildDenseExchangeRates(
      List<ExchangeRate> definedRates, LocalDate effectiveStartDate, LocalDate effectiveEndDate) {
    var ratesByDate =
        definedRates.stream().collect(Collectors.toMap(ExchangeRate::getDate, Function.identity()));

    var rv = new ArrayList<ExchangeRateData>();
    var currentRate = definedRates.get(0);

    // Check if we need a rate before the first defined rate
    if (currentRate.getDate().isAfter(effectiveStartDate)) {
      var previousRate =
          exchangeRateRepository.findTopByTargetCurrencyAndDateLessThanOrderByDateDesc(
              currentRate.getTargetCurrency(), effectiveStartDate);

      if (previousRate.isPresent()) {
        currentRate = previousRate.get();
      }
    }

    // Iterate through all dates in the range
    for (var date = effectiveStartDate; !date.isAfter(effectiveEndDate); date = date.plusDays(1)) {
      if (ratesByDate.containsKey(date)) {
        currentRate = ratesByDate.get(date);
      }

      rv.add(ExchangeRateData.from(currentRate, date));
    }

    return rv;
  }
}
