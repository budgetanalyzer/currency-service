package com.bleurubin.budgetanalyzer.currency.service;

/** Error codes for currency service business exceptions. */
public enum CurrencyServiceError {
  /** Currency code already exists in the system. */
  DUPLICATE_CURRENCY_CODE,

  /** Currency code is not a valid ISO 4217 code. */
  INVALID_ISO_4217_CODE,

  /** Provider series ID does not exist in the external provider. */
  INVALID_PROVIDER_SERIES_ID,
}
