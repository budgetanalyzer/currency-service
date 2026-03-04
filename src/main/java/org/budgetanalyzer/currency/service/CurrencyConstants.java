package org.budgetanalyzer.currency.service;

import java.util.Currency;

/**
 * Shared currency constants for the service layer.
 *
 * <p>All API responses normalize to USD as the base currency, regardless of how data is stored
 * internally.
 */
public final class CurrencyConstants {

  /** Base currency: US Dollar. All API responses normalize to USD as base. */
  public static final Currency USD = Currency.getInstance("USD");

  private CurrencyConstants() {}
}
