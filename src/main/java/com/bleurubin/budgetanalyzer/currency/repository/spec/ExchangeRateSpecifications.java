package com.bleurubin.budgetanalyzer.currency.repository.spec;

import java.time.LocalDate;
import java.util.Currency;

import org.springframework.data.jpa.domain.Specification;

import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;

public class ExchangeRateSpecifications {

  public static Specification<ExchangeRate> hasTargetCurrency(Currency targetCurrency) {
    return (root, query, cb) -> cb.equal(root.get("targetCurrency"), targetCurrency);
  }

  public static Specification<ExchangeRate> dateGreaterThanOrEqual(LocalDate date) {
    return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("date"), date);
  }

  public static Specification<ExchangeRate> dateLessThanOrEqual(LocalDate date) {
    return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("date"), date);
  }
}
