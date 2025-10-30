package com.bleurubin.budgetanalyzer.currency.repository;

import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;

public interface ExchangeRateRepository
    extends JpaRepository<ExchangeRate, Long>, JpaSpecificationExecutor<ExchangeRate> {

  Optional<ExchangeRate> findTopByTargetCurrencyAndDateLessThanOrderByDateDesc(
      Currency targetCurrency, LocalDate date);
}
