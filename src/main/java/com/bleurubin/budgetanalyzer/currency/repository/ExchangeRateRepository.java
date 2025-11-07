package com.bleurubin.budgetanalyzer.currency.repository;

import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.bleurubin.budgetanalyzer.currency.domain.CurrencySeries;
import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;

public interface ExchangeRateRepository
    extends JpaRepository<ExchangeRate, Long>, JpaSpecificationExecutor<ExchangeRate> {

  Optional<ExchangeRate> findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(
      Currency baseCurrency, Currency targetCurrency);

  Optional<ExchangeRate> findByBaseCurrencyAndTargetCurrencyAndDate(
      Currency baseCurrency, Currency targetCurrency, LocalDate date);

  Optional<ExchangeRate> findTopByTargetCurrencyAndDateLessThanOrderByDateDesc(
      Currency targetCurrency, LocalDate date);

  /**
   * Count the number of exchange rates for a given currency series.
   *
   * @param currencySeries The currency series to count exchange rates for
   * @return The count of exchange rates
   */
  long countByCurrencySeries(CurrencySeries currencySeries);
}
