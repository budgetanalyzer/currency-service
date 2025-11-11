package org.budgetanalyzer.currency.fixture;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.domain.ExchangeRate;

/**
 * Fluent builder for creating {@link ExchangeRate} test data.
 *
 * <p>Provides sensible defaults and convenience methods for common test scenarios. All builder
 * methods return {@code this} for method chaining.
 *
 * <p><b>Defaults:</b>
 *
 * <ul>
 *   <li>Base Currency: USD
 *   <li>Target Currency: EUR
 *   <li>Date: 2024-01-02 (weekday)
 *   <li>Rate: 0.8500
 *   <li>Currency Series: null (must be set via {@link #withCurrencySeries} or {@link #forSeries})
 *   <li>ID: null (new entity)
 *   <li>Audit Timestamps: null (set by JPA on persist)
 * </ul>
 *
 * <p><b>Example Usage:</b>
 *
 * <pre>{@code
 * // Create exchange rate for a specific series
 * CurrencySeries eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
 * ExchangeRate eurRate = ExchangeRateTestBuilder.forSeries(eurSeries)
 *     .withDate(LocalDate.of(2024, 1, 15))
 *     .withRate(new BigDecimal("0.8600"))
 *     .build();
 *
 * // Create USD→THB rate
 * ExchangeRate thbRate = ExchangeRateTestBuilder.usdToThb(
 *     LocalDate.of(2024, 1, 2),
 *     new BigDecimal("32.6800")
 * );
 *
 * // Create date range of rates
 * List<ExchangeRate> rates = ExchangeRateTestBuilder.buildDateRange(
 *     eurSeries,
 *     LocalDate.of(2024, 1, 1),
 *     LocalDate.of(2024, 1, 10),
 *     new BigDecimal("0.8500")
 * );
 * }</pre>
 */
public class ExchangeRateTestBuilder {

  private Long id;
  private CurrencySeries currencySeries;
  private Currency baseCurrency = TestConstants.BASE_CURRENCY_USD;
  private Currency targetCurrency = Currency.getInstance(TestConstants.VALID_CURRENCY_EUR);
  private LocalDate date = TestConstants.DATE_2024_JAN_02;
  private BigDecimal rate = TestConstants.RATE_EUR_USD;

  /**
   * Creates a new builder with default values.
   *
   * @see #forSeries(CurrencySeries)
   * @see #usdToEur(LocalDate, BigDecimal)
   * @see #usdToThb(LocalDate, BigDecimal)
   */
  public ExchangeRateTestBuilder() {}

  /**
   * Sets the entity ID.
   *
   * <p>Use this for persisted entities in tests that require an existing database record.
   *
   * @param id the entity ID
   * @return this builder
   */
  public ExchangeRateTestBuilder withId(Long id) {
    this.id = id;
    return this;
  }

  /**
   * Sets the currency series and automatically updates the target currency to match.
   *
   * <p>This ensures consistency between the series and denormalized target currency field.
   *
   * @param currencySeries the currency series
   * @return this builder
   */
  public ExchangeRateTestBuilder withCurrencySeries(CurrencySeries currencySeries) {
    this.currencySeries = currencySeries;
    if (currencySeries != null && currencySeries.getCurrencyCode() != null) {
      this.targetCurrency = Currency.getInstance(currencySeries.getCurrencyCode());
    }
    return this;
  }

  /**
   * Sets the base currency.
   *
   * <p>Note: In this service, base currency is always USD for FRED data.
   *
   * @param baseCurrency the base currency
   * @return this builder
   */
  public ExchangeRateTestBuilder withBaseCurrency(Currency baseCurrency) {
    this.baseCurrency = baseCurrency;
    return this;
  }

  /**
   * Sets the target currency.
   *
   * <p>Note: This should match the currency series currency code for consistency.
   *
   * @param targetCurrency the target currency
   * @return this builder
   */
  public ExchangeRateTestBuilder withTargetCurrency(Currency targetCurrency) {
    this.targetCurrency = targetCurrency;
    return this;
  }

  /**
   * Sets the target currency using a currency code string.
   *
   * @param currencyCode the ISO 4217 currency code (e.g., "EUR", "THB")
   * @return this builder
   */
  public ExchangeRateTestBuilder withTargetCurrency(String currencyCode) {
    this.targetCurrency = Currency.getInstance(currencyCode);
    return this;
  }

  /**
   * Sets the exchange rate date.
   *
   * @param date the date
   * @return this builder
   */
  public ExchangeRateTestBuilder withDate(LocalDate date) {
    this.date = date;
    return this;
  }

  /**
   * Sets the exchange rate value.
   *
   * @param rate the exchange rate (e.g., 0.8500 for EUR/USD)
   * @return this builder
   */
  public ExchangeRateTestBuilder withRate(BigDecimal rate) {
    this.rate = rate;
    return this;
  }

  /**
   * Sets the exchange rate value from a string.
   *
   * @param rate the exchange rate as string (e.g., "0.8500")
   * @return this builder
   */
  public ExchangeRateTestBuilder withRate(String rate) {
    this.rate = new BigDecimal(rate);
    return this;
  }

  /**
   * Sets the exchange rate to null.
   *
   * <p>Use this to simulate missing FRED data (value=".").
   *
   * @return this builder
   */
  public ExchangeRateTestBuilder withNullRate() {
    this.rate = null;
    return this;
  }

  /**
   * Builds a new exchange rate entity.
   *
   * <p>The returned entity has no ID and no audit timestamps (JPA will set them on persist).
   *
   * @return a new ExchangeRate instance
   */
  public ExchangeRate build() {
    var exchangeRate = new ExchangeRate();
    exchangeRate.setId(id);
    exchangeRate.setCurrencySeries(currencySeries);
    exchangeRate.setBaseCurrency(baseCurrency);
    exchangeRate.setTargetCurrency(targetCurrency);
    exchangeRate.setDate(date);
    exchangeRate.setRate(rate);
    return exchangeRate;
  }

  /**
   * Builds a persisted exchange rate entity with ID.
   *
   * <p>Use this to simulate entities that already exist in the database. The entity will have:
   *
   * <ul>
   *   <li>ID: auto-incremented starting from 1000
   * </ul>
   *
   * <p>Note: Audit timestamps (createdAt, updatedAt) are managed by JPA and will be set when the
   * entity is actually persisted to the database.
   *
   * @return an ExchangeRate instance with ID
   */
  public ExchangeRate buildPersisted() {
    // Use a high starting ID to avoid conflicts with test data
    if (id == null) {
      id = System.nanoTime() % 1_000_000 + 1000L;
    }
    return build();
  }

  // ===========================================================================================
  // Convenience Factory Methods
  // ===========================================================================================

  /**
   * Creates a builder for an exchange rate associated with a specific currency series.
   *
   * <p>Automatically sets the target currency to match the series currency code.
   *
   * @param series the currency series
   * @return a new builder configured for the series
   */
  public static ExchangeRateTestBuilder forSeries(CurrencySeries series) {
    return new ExchangeRateTestBuilder().withCurrencySeries(series);
  }

  /**
   * Creates a USD→EUR exchange rate for a specific date and rate.
   *
   * <p>Note: Currency series is NOT set - use {@link #forSeries(CurrencySeries)} if needed.
   *
   * @param date the exchange rate date
   * @param rate the exchange rate value
   * @return a new ExchangeRate instance
   */
  public static ExchangeRate usdToEur(LocalDate date, BigDecimal rate) {
    return new ExchangeRateTestBuilder()
        .withTargetCurrency(TestConstants.VALID_CURRENCY_EUR)
        .withDate(date)
        .withRate(rate)
        .build();
  }

  /**
   * Creates a USD→THB exchange rate for a specific date and rate.
   *
   * <p>Note: Currency series is NOT set - use {@link #forSeries(CurrencySeries)} if needed.
   *
   * @param date the exchange rate date
   * @param rate the exchange rate value
   * @return a new ExchangeRate instance
   */
  public static ExchangeRate usdToThb(LocalDate date, BigDecimal rate) {
    return new ExchangeRateTestBuilder()
        .withTargetCurrency(TestConstants.VALID_CURRENCY_THB)
        .withDate(date)
        .withRate(rate)
        .build();
  }

  /**
   * Creates a USD→GBP exchange rate for a specific date and rate.
   *
   * <p>Note: Currency series is NOT set - use {@link #forSeries(CurrencySeries)} if needed.
   *
   * @param date the exchange rate date
   * @param rate the exchange rate value
   * @return a new ExchangeRate instance
   */
  public static ExchangeRate usdToGbp(LocalDate date, BigDecimal rate) {
    return new ExchangeRateTestBuilder()
        .withTargetCurrency(TestConstants.VALID_CURRENCY_GBP)
        .withDate(date)
        .withRate(rate)
        .build();
  }

  // ===========================================================================================
  // Bulk Creation Methods
  // ===========================================================================================

  /**
   * Creates exchange rates for a date range (inclusive).
   *
   * <p>Generates one exchange rate per day with the same rate value for all dates.
   *
   * <p><b>Example:</b>
   *
   * <pre>{@code
   * CurrencySeries eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
   * List<ExchangeRate> rates = ExchangeRateTestBuilder.buildDateRange(
   *     eurSeries,
   *     LocalDate.of(2024, 1, 1),
   *     LocalDate.of(2024, 1, 10),
   *     new BigDecimal("0.8500")
   * );
   * // Returns 10 exchange rates (Jan 1-10)
   * }</pre>
   *
   * @param series the currency series
   * @param startDate the start date (inclusive)
   * @param endDate the end date (inclusive)
   * @param rate the exchange rate value for all dates
   * @return list of exchange rates
   */
  public static List<ExchangeRate> buildDateRange(
      CurrencySeries series, LocalDate startDate, LocalDate endDate, BigDecimal rate) {
    var rates = new ArrayList<ExchangeRate>();
    var currentDate = startDate;

    while (!currentDate.isAfter(endDate)) {
      var exchangeRate =
          ExchangeRateTestBuilder.forSeries(series).withDate(currentDate).withRate(rate).build();
      rates.add(exchangeRate);
      currentDate = currentDate.plusDays(1);
    }

    return rates;
  }

  /**
   * Creates exchange rates for a date range with varying rates.
   *
   * <p>Generates one exchange rate per day, incrementing the rate by a fixed amount each day.
   *
   * <p><b>Example:</b>
   *
   * <pre>{@code
   * CurrencySeries thbSeries = CurrencySeriesTestBuilder.defaultThb().build();
   * List<ExchangeRate> rates = ExchangeRateTestBuilder.buildDateRangeWithIncrement(
   *     thbSeries,
   *     LocalDate.of(2024, 1, 1),
   *     LocalDate.of(2024, 1, 5),
   *     new BigDecimal("32.0000"),
   *     new BigDecimal("0.1000")
   * );
   * // Returns: 32.0000, 32.1000, 32.2000, 32.3000, 32.4000
   * }</pre>
   *
   * @param series the currency series
   * @param startDate the start date (inclusive)
   * @param endDate the end date (inclusive)
   * @param startRate the starting exchange rate value
   * @param increment the amount to add each day
   * @return list of exchange rates
   */
  public static List<ExchangeRate> buildDateRangeWithIncrement(
      CurrencySeries series,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal startRate,
      BigDecimal increment) {
    var rates = new ArrayList<ExchangeRate>();
    var currentDate = startDate;
    var currentRate = startRate;

    while (!currentDate.isAfter(endDate)) {
      var exchangeRate =
          ExchangeRateTestBuilder.forSeries(series)
              .withDate(currentDate)
              .withRate(currentRate)
              .build();
      rates.add(exchangeRate);
      currentDate = currentDate.plusDays(1);
      currentRate = currentRate.add(increment);
    }

    return rates;
  }

  /**
   * Creates exchange rates for weekdays only in a date range.
   *
   * <p>Skips weekends (Saturday and Sunday) to simulate FRED data availability.
   *
   * @param series the currency series
   * @param startDate the start date (inclusive)
   * @param endDate the end date (inclusive)
   * @param rate the exchange rate value for all dates
   * @return list of exchange rates (weekdays only)
   */
  public static List<ExchangeRate> buildWeekdaysOnly(
      CurrencySeries series, LocalDate startDate, LocalDate endDate, BigDecimal rate) {
    var rates = new ArrayList<ExchangeRate>();
    var currentDate = startDate;

    while (!currentDate.isAfter(endDate)) {
      var dayOfWeek = currentDate.getDayOfWeek();
      // Only create rates for Monday-Friday
      if (dayOfWeek.getValue() <= 5) {
        var exchangeRate =
            ExchangeRateTestBuilder.forSeries(series).withDate(currentDate).withRate(rate).build();
        rates.add(exchangeRate);
      }
      currentDate = currentDate.plusDays(1);
    }

    return rates;
  }
}
