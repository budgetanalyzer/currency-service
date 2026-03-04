package org.budgetanalyzer.currency.repository.spec;

import java.time.LocalDate;

import org.springframework.data.jpa.domain.Specification;

import org.budgetanalyzer.currency.domain.ExchangeRate;

public class ExchangeRateSpecifications {

  /**
   * Creates a specification to filter exchange rates by foreign currency code.
   *
   * <p>This specification works correctly for both FRED series patterns by filtering via the
   * currency series's currency code.
   *
   * @param currencyCode The ISO 4217 currency code of the foreign currency
   * @return Specification that filters by foreign currency
   */
  public static Specification<ExchangeRate> hasForeignCurrency(String currencyCode) {
    return (root, query, cb) ->
        cb.equal(root.get("currencySeries").get("currencyCode"), currencyCode);
  }

  public static Specification<ExchangeRate> dateGreaterThanOrEqual(LocalDate date) {
    return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("date"), date);
  }

  public static Specification<ExchangeRate> dateLessThanOrEqual(LocalDate date) {
    return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("date"), date);
  }
}
