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

  /**
   * Finds the most recent exchange rate before a given date for a foreign currency.
   *
   * <p>This method works correctly for both FRED series patterns by querying via the currency
   * series's currency code.
   *
   * @param currencyCode The ISO 4217 currency code of the foreign currency
   * @param date The date to look before
   * @return Optional containing the most recent rate before the date, or empty if none exists
   */
  Optional<ExchangeRate> findTopByCurrencySeriesCurrencyCodeAndDateLessThanOrderByDateDesc(
      String currencyCode, LocalDate date);

  /**
   * Finds the earliest date for which exchange rate data exists for a given foreign currency.
   *
   * <p>This method works correctly for both FRED series patterns:
   *
   * <ul>
   *   <li>DEXUS* series (e.g., EUR): stored as base=foreign, target=USD
   *   <li>DEX*US series (e.g., THB): stored as base=USD, target=foreign
   * </ul>
   *
   * @param currencyCode The ISO 4217 currency code of the foreign currency
   * @return Optional containing the earliest date, or empty if no data exists
   */
  @Query(
      "SELECT MIN(e.date) FROM ExchangeRate e "
          + "WHERE e.currencySeries.currencyCode = :currencyCode")
  Optional<LocalDate> findEarliestDateByForeignCurrency(@Param("currencyCode") String currencyCode);

  /**
   * Count the number of exchange rates for a given currency series.
   *
   * @param currencySeries The currency series to count exchange rates for
   * @return The count of exchange rates
   */
  long countByCurrencySeries(CurrencySeries currencySeries);
}
