package org.budgetanalyzer.currency.repository;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.domain.ExchangeRate;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

  Optional<ExchangeRate> findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(
      Currency baseCurrency, Currency targetCurrency);

  Optional<ExchangeRate> findByBaseCurrencyAndTargetCurrencyAndDate(
      Currency baseCurrency, Currency targetCurrency, LocalDate date);

  /**
   * Finds every exchange rate of a foreign currency in ascending date order.
   *
   * <p>This method works correctly for both FRED series patterns by querying via the currency
   * series's currency code:
   *
   * <ul>
   *   <li>DEXUS* series (e.g., EUR): stored as base=foreign, target=USD
   *   <li>DEX*US series (e.g., THB): stored as base=USD, target=foreign
   * </ul>
   *
   * @param currencyCode The ISO 4217 currency code of the foreign currency
   * @return exchange rates of the currency ordered by date, or an empty list if none exist
   */
  List<ExchangeRate> findByCurrencySeriesCurrencyCodeOrderByDateAsc(String currencyCode);

  /**
   * Count the number of exchange rates for a given currency series.
   *
   * @param currencySeries The currency series to count exchange rates for
   * @return The count of exchange rates
   */
  long countByCurrencySeries(CurrencySeries currencySeries);
}
