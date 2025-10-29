package com.bleurubin.budgetanalyzer.currency.service;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;
import com.bleurubin.budgetanalyzer.currency.dto.ExchangeRateDTO;

public interface ExchangeRateService {

  List<ExchangeRate> createExchangeRates(List<ExchangeRate> exchangeRates);

  List<ExchangeRateDTO> getExchangeRates(
      Currency targetCurrency, LocalDate startDate, LocalDate endDate);
}
