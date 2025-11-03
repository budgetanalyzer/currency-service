package com.bleurubin.budgetanalyzer.currency.service;

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

import com.bleurubin.budgetanalyzer.currency.config.CacheConfig;
import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;
import com.bleurubin.budgetanalyzer.currency.dto.ExchangeRateDTO;
import com.bleurubin.budgetanalyzer.currency.repository.ExchangeRateRepository;
import com.bleurubin.budgetanalyzer.currency.repository.spec.ExchangeRateSpecifications;
import com.bleurubin.service.exception.ResourceNotFoundException;

@Service
public class ExchangeRateService {

  private final ExchangeRateRepository exchangeRateRepository;

  public ExchangeRateService(ExchangeRateRepository exchangeRateRepository) {
    this.exchangeRateRepository = exchangeRateRepository;
  }

  /**
   * Retrieves exchange rates for a target currency within a date range.
   *
   * <p>Results are cached in Redis with a composite key of targetCurrency:startDate:endDate. Cache
   * is automatically evicted when new rates are imported.
   *
   * @param targetCurrency the currency to convert USD to
   * @param startDate optional start date (inclusive)
   * @param endDate optional end date (inclusive)
   * @return list of exchange rates with gaps filled using forward-fill logic
   */
  @Cacheable(
      cacheNames = CacheConfig.EXCHANGE_RATES_CACHE,
      key = "#targetCurrency.currencyCode + ':' + #startDate + ':' + #endDate")
  public List<ExchangeRateDTO> getExchangeRates(
      Currency targetCurrency, LocalDate startDate, LocalDate endDate) {
    var spec = buildSpecification(targetCurrency, startDate, endDate);

    var definedRates = exchangeRateRepository.findAll(spec, Sort.by("date").ascending());
    if (definedRates.isEmpty()) {
      throw new ResourceNotFoundException("No rates found for currency: " + targetCurrency);
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

  private List<ExchangeRateDTO> buildDenseExchangeRates(
      List<ExchangeRate> definedRates, LocalDate effectiveStartDate, LocalDate effectiveEndDate) {
    var ratesByDate =
        definedRates.stream().collect(Collectors.toMap(ExchangeRate::getDate, Function.identity()));

    var rv = new ArrayList<ExchangeRateDTO>();
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

      rv.add(ExchangeRateDTO.from(currentRate, date));
    }

    return rv;
  }
}
