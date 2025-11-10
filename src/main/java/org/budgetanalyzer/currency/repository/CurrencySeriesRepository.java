package org.budgetanalyzer.currency.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import org.budgetanalyzer.currency.domain.CurrencySeries;

/** Repository for managing CurrencySeries entities. */
public interface CurrencySeriesRepository extends JpaRepository<CurrencySeries, Long> {

  /**
   * Find all enabled currency series.
   *
   * @return List of enabled currency series
   */
  List<CurrencySeries> findByEnabledTrue();

  /**
   * Find a currency series by currency code and enabled status.
   *
   * @param currencyCode The ISO 4217 currency code
   * @return Optional containing the currency series if found and enabled
   */
  Optional<CurrencySeries> findByCurrencyCodeAndEnabledTrue(String currencyCode);

  /**
   * Find a currency series by currency code (regardless of enabled status).
   *
   * @param currencyCode The ISO 4217 currency code
   * @return Optional containing the currency series if found
   */
  Optional<CurrencySeries> findByCurrencyCode(String currencyCode);
}
