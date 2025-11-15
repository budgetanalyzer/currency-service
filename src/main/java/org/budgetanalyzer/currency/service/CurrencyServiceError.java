package org.budgetanalyzer.currency.service;

/** Error codes for currency service business exceptions. */
public enum CurrencyServiceError {
  /** Currency code already exists in the system. */
  DUPLICATE_CURRENCY_CODE,

  /** Currency code is not a valid ISO 4217 code. */
  INVALID_ISO_4217_CODE,

  /** Provider series ID does not exist in the external provider. */
  INVALID_PROVIDER_SERIES_ID,

  /** No exchange rate data exists for the requested currency. */
  NO_EXCHANGE_RATE_DATA_AVAILABLE,

  /** Start date is before the earliest available exchange rate data. */
  START_DATE_OUT_OF_RANGE,

  /** Currency is not enabled for exchange rate access. */
  CURRENCY_NOT_ENABLED,
}
