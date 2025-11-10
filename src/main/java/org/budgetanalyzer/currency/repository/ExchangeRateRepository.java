package org.budgetanalyzer.currency.repository;

import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.domain.ExchangeRate;

public interface ExchangeRateRepository
    extends JpaRepository<ExchangeRate, Long>, JpaSpecificationExecutor<ExchangeRate> {

  Optional<ExchangeRate> findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(
      Currency baseCurrency, Currency targetCurrency);

  Optional<ExchangeRate> findByBaseCurrencyAndTargetCurrencyAndDate(
      Currency baseCurrency, Currency targetCurrency, LocalDate date);

  Optional<ExchangeRate> findTopByTargetCurrencyAndDateLessThanOrderByDateDesc(
      Currency targetCurrency, LocalDate date);

  /**
   * Finds the earliest date for which exchange rate data exists for a given target currency.
   *
   * @param targetCurrency The target currency to find the earliest date for
   * @return Optional containing the earliest date, or empty if no data exists
   */
  @Query("SELECT MIN(e.date) FROM ExchangeRate e WHERE e.targetCurrency = :targetCurrency")
  Optional<LocalDate> findEarliestDateByTargetCurrency(
      @Param("targetCurrency") Currency targetCurrency);

  /**
   * Count the number of exchange rates for a given currency series.
   *
   * @param currencySeries The currency series to count exchange rates for
   * @return The count of exchange rates
   */
  long countByCurrencySeries(CurrencySeries currencySeries);
}
