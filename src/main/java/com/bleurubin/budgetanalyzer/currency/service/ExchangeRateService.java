package com.bleurubin.budgetanalyzer.currency.service;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

import com.bleurubin.budgetanalyzer.currency.dto.ExchangeRateDTO;

public interface ExchangeRateService {

  List<ExchangeRateDTO> getExchangeRates(
      Currency targetCurrency, LocalDate startDate, LocalDate endDate);
}
