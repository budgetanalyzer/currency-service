package com.bleurubin.budgetanalyzer.currency.service.impl;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;
import com.bleurubin.budgetanalyzer.currency.dto.ExchangeRateDTO;
import com.bleurubin.budgetanalyzer.currency.repository.ExchangeRateRepository;
import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateService;

@Service
public class ExchangeRateServiceImpl implements ExchangeRateService {

  private static final Currency DEFAULT_BASE_CURRENCY = Currency.getInstance("USD");

  private final ExchangeRateRepository exchangeRateRepository;

  public ExchangeRateServiceImpl(ExchangeRateRepository exchangeRateRepository) {
    this.exchangeRateRepository = exchangeRateRepository;
  }

  @Override
  @Transactional
  public List<ExchangeRate> createExchangeRates(List<ExchangeRate> exchangeRates) {
    // return raw Entity since this is from an import and we want to see createdAt, updatedAt, etc.
    return exchangeRateRepository.saveAll(exchangeRates);
  }

  @Override
  public List<ExchangeRateDTO> getExchangeRates(
      Currency targetCurrency, LocalDate startDate, LocalDate endDate) {
    // FIXME retrieve exchange rates from repository for the given baseCurrency AND targetCurrency,
    // with date between startDate and endDate inclusive.
    return List.of();
  }
}
