package org.budgetanalyzer.currency.fixture;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;

/**
 * Test constants for integration tests.
 *
 * <p>Provides commonly used test data including valid/invalid ISO 4217 currency codes, FRED series
 * IDs, test dates, and sample exchange rates.
 */
public final class TestConstants {

  // ===========================================================================================
  // Valid ISO 4217 Currency Codes
  // ===========================================================================================

  /** Valid currency code: Euro. */
  public static final String VALID_CURRENCY_EUR = "EUR";

  /** Valid currency code: Thai Baht. */
  public static final String VALID_CURRENCY_THB = "THB";

  /** Valid currency code: British Pound Sterling. */
  public static final String VALID_CURRENCY_GBP = "GBP";

  /** Valid currency code: Japanese Yen. */
  public static final String VALID_CURRENCY_JPY = "JPY";

  /** Valid currency code: Canadian Dollar. */
  public static final String VALID_CURRENCY_CAD = "CAD";

  /** Valid currency code: Australian Dollar. */
  public static final String VALID_CURRENCY_AUD = "AUD";

  /** Valid currency code: Swiss Franc. */
  public static final String VALID_CURRENCY_CHF = "CHF";

  /** Valid currency code: US Dollar (base currency). */
  public static final String VALID_CURRENCY_USD = "USD";

  /** Valid currency code: South African Rand (used for testing queries with no data). */
  public static final String VALID_CURRENCY_ZAR = "ZAR";

  // ===========================================================================================
  // Invalid Currency Codes (for validation testing)
  // ===========================================================================================

  /** Invalid currency code: too short (2 characters). */
  public static final String INVALID_CURRENCY_TOO_SHORT = "US";

  /** Invalid currency code: too long (4 characters). */
  public static final String INVALID_CURRENCY_TOO_LONG = "USDA";

  /** Invalid currency code: lowercase letters. */
  public static final String INVALID_CURRENCY_LOWERCASE = "usd";

  /** Invalid currency code: contains numbers. */
  public static final String INVALID_CURRENCY_WITH_NUMBERS = "US1";

  /** Invalid currency code: contains special characters. */
  public static final String INVALID_CURRENCY_WITH_SPECIAL_CHARS = "US$";

  /** Invalid currency code: non-existent in ISO 4217. */
  public static final String INVALID_CURRENCY = "XXX";

  /** Invalid currency code: another non-existent code. */
  public static final String INVALID_CURRENCY_ZZZ = "ZZZ";

  // ===========================================================================================
  // FRED Series IDs
  // ===========================================================================================

  /** FRED series ID for EUR/USD exchange rate. */
  public static final String FRED_SERIES_EUR = "DEXUSEU";

  /** FRED series ID for THB/USD exchange rate. */
  public static final String FRED_SERIES_THB = "DEXTHUS";

  /** FRED series ID for GBP/USD exchange rate. */
  public static final String FRED_SERIES_GBP = "DEXUSUK";

  /** FRED series ID for JPY/USD exchange rate. */
  public static final String FRED_SERIES_JPY = "DEXJPUS";

  /** FRED series ID for CAD/USD exchange rate. */
  public static final String FRED_SERIES_CAD = "DEXCAUS";

  /** FRED series ID for AUD/USD exchange rate. */
  public static final String FRED_SERIES_AUD = "DEXUSAL";

  /** FRED series ID for CHF/USD exchange rate. */
  public static final String FRED_SERIES_CHF = "DEXSZUS";

  /** Invalid FRED series ID for testing error scenarios. */
  public static final String FRED_SERIES_INVALID = "INVALID_SERIES_ID";

  /** Non-existent FRED series ID for 404 testing. */
  public static final String FRED_SERIES_NOT_FOUND = "NOTFOUND123";

  // ===========================================================================================
  // Base Currency
  // ===========================================================================================

  /** Base currency: US Dollar. All exchange rates in this service use USD as base. */
  public static final Currency BASE_CURRENCY_USD = Currency.getInstance("USD");

  // ===========================================================================================
  // Currency Instances (for repository tests)
  // ===========================================================================================

  /** Currency instance: Euro. */
  public static final Currency CURRENCY_EUR = Currency.getInstance(VALID_CURRENCY_EUR);

  /** Currency instance: Thai Baht. */
  public static final Currency CURRENCY_THB = Currency.getInstance(VALID_CURRENCY_THB);

  /** Currency instance: British Pound Sterling. */
  public static final Currency CURRENCY_GBP = Currency.getInstance(VALID_CURRENCY_GBP);

  /** Currency instance: Japanese Yen. */
  public static final Currency CURRENCY_JPY = Currency.getInstance(VALID_CURRENCY_JPY);

  /** Currency instance: Canadian Dollar. */
  public static final Currency CURRENCY_CAD = Currency.getInstance(VALID_CURRENCY_CAD);

  /** Currency instance: Australian Dollar. */
  public static final Currency CURRENCY_AUD = Currency.getInstance(VALID_CURRENCY_AUD);

  /** Currency instance: Swiss Franc. */
  public static final Currency CURRENCY_CHF = Currency.getInstance(VALID_CURRENCY_CHF);

  /** Currency instance: South African Rand (used for testing queries with no data). */
  public static final Currency CURRENCY_ZAR_NOT_IN_DB = Currency.getInstance(VALID_CURRENCY_ZAR);

  // ===========================================================================================
  // Test Dates
  // ===========================================================================================

  /** Test date: January 1, 2024 (Monday, New Year's Day). */
  public static final LocalDate DATE_2024_JAN_01 = LocalDate.of(2024, 1, 1);

  /** Test date: January 2, 2024 (Tuesday, typical weekday). */
  public static final LocalDate DATE_2024_JAN_02 = LocalDate.of(2024, 1, 2);

  /** Test date: January 5, 2024 (Friday, end of week). */
  public static final LocalDate DATE_2024_JAN_05 = LocalDate.of(2024, 1, 5);

  /** Test date: January 6, 2024 (Saturday, weekend - no FRED data). */
  public static final LocalDate DATE_2024_JAN_06_WEEKEND = LocalDate.of(2024, 1, 6);

  /** Test date: January 7, 2024 (Sunday, weekend - no FRED data). */
  public static final LocalDate DATE_2024_JAN_07_WEEKEND = LocalDate.of(2024, 1, 7);

  /** Test date: January 15, 2024 (Monday, mid-month). */
  public static final LocalDate DATE_2024_JAN_15 = LocalDate.of(2024, 1, 15);

  /** Test date: February 29, 2024 (Thursday, leap year day). */
  public static final LocalDate DATE_2024_FEB_29_LEAP_YEAR = LocalDate.of(2024, 2, 29);

  /** Test date: June 15, 2024 (Saturday, mid-year). */
  public static final LocalDate DATE_2024_JUN_15 = LocalDate.of(2024, 6, 15);

  /** Test date: December 31, 2024 (Tuesday, end of year). */
  public static final LocalDate DATE_2024_DEC_31 = LocalDate.of(2024, 12, 31);

  /** Test date: Very old date (January 1, 1971 - earliest FRED data). */
  public static final LocalDate DATE_1971_JAN_01 = LocalDate.of(1971, 1, 1);

  /** Test date: Future date (January 1, 2030). */
  public static final LocalDate DATE_2030_JAN_01 = LocalDate.of(2030, 1, 1);

  // ===========================================================================================
  // Sample Exchange Rates
  // ===========================================================================================

  /** Sample exchange rate: EUR/USD (Euro stronger than USD). */
  public static final BigDecimal RATE_EUR_USD = new BigDecimal("0.8500");

  /** Sample exchange rate: THB/USD (Thai Baht weaker than USD). */
  public static final BigDecimal RATE_THB_USD = new BigDecimal("32.6800");

  /** Sample exchange rate: GBP/USD (British Pound stronger than USD). */
  public static final BigDecimal RATE_GBP_USD = new BigDecimal("0.7800");

  /** Sample exchange rate: JPY/USD (Japanese Yen much weaker than USD). */
  public static final BigDecimal RATE_JPY_USD = new BigDecimal("140.5000");

  /** Sample exchange rate: CAD/USD (Canadian Dollar slightly weaker than USD). */
  public static final BigDecimal RATE_CAD_USD = new BigDecimal("1.3500");

  /** Sample exchange rate: Very small rate (close to parity). */
  public static final BigDecimal RATE_VERY_SMALL = new BigDecimal("0.0001");

  /** Sample exchange rate: Very large rate (extreme case). */
  public static final BigDecimal RATE_VERY_LARGE = new BigDecimal("9999.9999");

  /** Sample exchange rate: Parity (1:1 ratio). */
  public static final BigDecimal RATE_PARITY = new BigDecimal("1.0000");

  // ===========================================================================================
  // FRED API Configuration
  // ===========================================================================================

  /** FRED API base path for series observations. */
  public static final String FRED_API_PATH_OBSERVATIONS = "/series/observations";

  /** FRED API query parameter: series_id. */
  public static final String FRED_PARAM_SERIES_ID = "series_id";

  /** FRED API query parameter: observation_start. */
  public static final String FRED_PARAM_OBSERVATION_START = "observation_start";

  /** FRED API query parameter: api_key. */
  public static final String FRED_PARAM_API_KEY = "api_key";

  /** FRED API missing data indicator (returned for weekends/holidays). */
  public static final String FRED_MISSING_DATA_VALUE = ".";

  // ===========================================================================================
  // HTTP Status Codes
  // ===========================================================================================

  /** HTTP 400 Bad Request. */
  public static final int HTTP_BAD_REQUEST = 400;

  /** HTTP 404 Not Found. */
  public static final int HTTP_NOT_FOUND = 404;

  /** HTTP 429 Too Many Requests. */
  public static final int HTTP_TOO_MANY_REQUESTS = 429;

  /** HTTP 500 Internal Server Error. */
  public static final int HTTP_INTERNAL_SERVER_ERROR = 500;

  private TestConstants() {
    throw new UnsupportedOperationException("Utility class - do not instantiate");
  }
}
