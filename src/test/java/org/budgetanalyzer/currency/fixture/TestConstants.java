package org.budgetanalyzer.currency.fixture;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;

import org.budgetanalyzer.currency.service.dto.ExchangeRateSeries;

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

  /**
   * Fixed "today" (UTC) of the integration-test {@link Clock}, after every stored test rate.
   *
   * <p>Exchange-rate queries forward-fill to this date when no end date is supplied and cap a later
   * end date at the day after it.
   */
  public static final LocalDate FIXED_TODAY = LocalDate.of(2025, 1, 15);

  /** Fixed UTC clock whose current date is {@link #FIXED_TODAY}. */
  public static final Clock FIXED_CLOCK =
      Clock.fixed(FIXED_TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

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

  /** Daily rate increment that gives each day of a test range a different rate. */
  public static final BigDecimal RATE_DAILY_INCREMENT = new BigDecimal("0.0100");

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

  // ===========================================================================================
  // RabbitMQ Publisher Confirms
  // ===========================================================================================

  /** Correlation id for a publisher-confirm test message. */
  public static final String PUBLISH_CORRELATION_ID = "test-correlation-id";

  /** Exchange used by the exchange rate import requested binding. */
  public static final String EXCHANGE_RATE_IMPORT_REQUESTED_EXCHANGE =
      "exchange-rate.import.requested";

  /** Routing key for a publisher-confirm test message. */
  public static final String PUBLISH_ROUTING_KEY = "exchange-rate.import.requested";

  /** AMQP reply code for an unroutable mandatory message. */
  public static final int AMQP_REPLY_CODE_NO_ROUTE = 312;

  /** AMQP reply text for an unroutable mandatory message. */
  public static final String AMQP_REPLY_TEXT_NO_ROUTE = "NO_ROUTE";

  /** Reason reported with a broker nack. */
  public static final String PUBLISH_NACK_REASON = "broker internal error";

  /** Confirm wait long enough for an already-completed confirm future. */
  public static final Duration PUBLISH_CONFIRM_TIMEOUT = Duration.ofSeconds(1);

  /** Short confirm wait for a confirm future that never completes. */
  public static final Duration PUBLISH_CONFIRM_TIMEOUT_SHORT = Duration.ofMillis(50);

  /** Prefix of the per-run producer destination used by publisher-confirm broker tests. */
  public static final String PUBLISH_CONFIRM_TEST_DESTINATION_PREFIX =
      "exchange-rate.import.requested.confirm-test-";

  /** Prefix of the queues that publisher-confirm broker tests bind to the test destination. */
  public static final String PUBLISH_CONFIRM_TEST_QUEUE_PREFIX = "publisher-confirm-test-";

  /** Topic binding key that routes every message published to an exchange. */
  public static final String TOPIC_ROUTING_KEY_ALL = "#";

  /** RabbitMQ queue argument that limits the number of ready messages. */
  public static final String RABBIT_ARG_MAX_LENGTH = "x-max-length";

  /** RabbitMQ queue argument that selects the overflow behavior. */
  public static final String RABBIT_ARG_OVERFLOW = "x-overflow";

  /** Overflow behavior that makes the broker nack publishes to a full queue. */
  public static final String RABBIT_OVERFLOW_REJECT_PUBLISH = "reject-publish";

  /** Queue length limit that makes every publish overflow. */
  public static final int RABBIT_MAX_LENGTH_ZERO = 0;

  /** Spring Modulith publication status for a completed listener. */
  public static final String EVENT_PUBLICATION_STATUS_COMPLETED = "COMPLETED";

  /** Spring Modulith publication status for a failed listener. */
  public static final String EVENT_PUBLICATION_STATUS_FAILED = "FAILED";

  /** Maximum wait for a broker confirm or return to settle an outbox publication. */
  public static final Duration PUBLISH_OUTCOME_WAIT = Duration.ofSeconds(5);

  // ===========================================================================================
  // Runtime Security and Configuration
  // ===========================================================================================

  /** Internal user ID of a test caller allowed to change currencies. */
  public static final String WRITER_USER_ID = "usr_writer";

  /** Permission required to change currencies. */
  public static final String PERMISSION_CURRENCIES_WRITE = "currencies:write";

  /** Internal user ID of a test caller allowed to read currencies and exchange rates. */
  public static final String READER_USER_ID = "usr_reader";

  /** Permission required to read currencies and exchange rates. */
  public static final String PERMISSION_CURRENCIES_READ = "currencies:read";

  /** FRED API key for tests that load the main application configuration. */
  public static final String FRED_TEST_API_KEY = "test-key";

  // ===========================================================================================
  // Redis ACL
  // ===========================================================================================

  /** Redis username the service connects as. */
  public static final String REDIS_CURRENCY_SERVICE_USER = "currency-service";

  /** Redis password for the ACL-restricted test user. */
  public static final String REDIS_CURRENCY_SERVICE_TEST_PASSWORD = "currency-service-test";

  /**
   * Redis ACL rules for the service user, matching the orchestration grant without {@code +keys}.
   */
  public static final String REDIS_CURRENCY_SERVICE_RULES_WITHOUT_KEYS =
      "~currency-service:* +get +set +del +scan +ping +auth +hello +info +ttl +pttl +expire"
          + " +exists +type +object";

  /**
   * Redis ACL rules for a service user that may read cache entries but not write, delete, or scan
   * them, so cache puts and clears fail with {@code NOPERM}.
   */
  public static final String REDIS_CURRENCY_SERVICE_READ_ONLY_RULES =
      "~currency-service:* +get +ping +auth +hello +info";

  // ===========================================================================================
  // Exchange-Rate Series Cache
  // ===========================================================================================

  /** Cache key of the EUR exchange-rate series (the currency code). */
  public static final String EXCHANGE_RATE_SERIES_CACHE_KEY_EUR = VALID_CURRENCY_EUR;

  /** Full Redis key of the cached EUR exchange-rate series. */
  public static final String EXCHANGE_RATE_SERIES_REDIS_KEY_EUR =
      "currency-service:exchangeRateSeries::" + EXCHANGE_RATE_SERIES_CACHE_KEY_EUR;

  /** Redis key pattern matching every cached exchange-rate series. */
  public static final String EXCHANGE_RATE_SERIES_REDIS_KEY_PATTERN =
      "currency-service:exchangeRateSeries::*";

  /**
   * Valid cached EUR series: source rates on Friday Jan 5 and Monday Jan 8, 2024, with the weekend
   * forward-filled from Friday.
   */
  public static final ExchangeRateSeries EXCHANGE_RATE_SERIES_EUR =
      new ExchangeRateSeries(
          Currency.getInstance(VALID_CURRENCY_USD),
          CURRENCY_EUR,
          FRED_SERIES_EUR,
          DATE_2024_JAN_05,
          List.of(
              new BigDecimal("1.1765"),
              new BigDecimal("1.1765"),
              new BigDecimal("1.1765"),
              new BigDecimal("1.1628")),
          List.of(0, 1, 2, 0));

  // ===========================================================================================
  // Fail-Open Cache
  // ===========================================================================================

  /** Name of the cache used by fail-open cache unit tests. */
  public static final String FAIL_OPEN_TEST_CACHE_NAME = "failOpenTestCache";

  /** Value stored or loaded by fail-open cache unit tests. */
  public static final String FAIL_OPEN_TEST_VALUE = "cached-value";

  /** Message of the simulated Redis failure in fail-open cache unit tests. */
  public static final String SIMULATED_REDIS_FAILURE_MESSAGE = "Simulated Redis failure";

  /** Message of the simulated value loader failure in fail-open cache unit tests. */
  public static final String SIMULATED_LOADER_FAILURE_MESSAGE = "Simulated loader failure";

  /** Message of the simulated non-cache failure in fail-open cache unit tests. */
  public static final String SIMULATED_UNEXPECTED_FAILURE_MESSAGE = "Simulated unexpected failure";

  /**
   * Returns the start of the warning that the fail-open cache logs when a keyed cache operation
   * fails, up to the exception class.
   *
   * @param operation the failed cache operation, such as {@code get} or {@code put}
   * @param cacheName the name of the cache
   * @param key the cache key
   * @return the start of the formatted warning message
   */
  public static String failOpenCacheWarningPrefix(String operation, String cacheName, Object key) {
    return "Cache "
        + operation
        + " failed for cache '"
        + cacheName
        + "' and key '"
        + key
        + "'; continuing without the cache: ";
  }

  /**
   * Returns the start of the warning that the fail-open cache logs when a cache-wide operation
   * fails, up to the exception class.
   *
   * @param operation the failed cache operation, such as {@code clear}
   * @param cacheName the name of the cache
   * @return the start of the formatted warning message
   */
  public static String failOpenCacheWarningPrefix(String operation, String cacheName) {
    return "Cache "
        + operation
        + " failed for cache '"
        + cacheName
        + "'; continuing without the cache: ";
  }

  /**
   * Returns the warning that the fail-open cache logs when a keyed cache operation fails.
   *
   * @param operation the failed cache operation, such as {@code get} or {@code put}
   * @param cacheName the name of the cache
   * @param key the cache key
   * @param failure the absorbed cache failure, whose class and message end the warning
   * @return the formatted warning message
   */
  public static String failOpenCacheWarning(
      String operation, String cacheName, Object key, Throwable failure) {
    return failOpenCacheWarningPrefix(operation, cacheName, key) + failureSummary(failure);
  }

  /**
   * Returns the warning that the fail-open cache logs when a cache-wide operation fails.
   *
   * @param operation the failed cache operation, such as {@code clear}
   * @param cacheName the name of the cache
   * @param failure the absorbed cache failure, whose class and message end the warning
   * @return the formatted warning message
   */
  public static String failOpenCacheWarning(String operation, String cacheName, Throwable failure) {
    return failOpenCacheWarningPrefix(operation, cacheName) + failureSummary(failure);
  }

  private TestConstants() {
    throw new UnsupportedOperationException("Utility class - do not instantiate");
  }

  private static String failureSummary(Throwable failure) {
    return failure.getClass().getName() + ": " + failure.getMessage();
  }
}
