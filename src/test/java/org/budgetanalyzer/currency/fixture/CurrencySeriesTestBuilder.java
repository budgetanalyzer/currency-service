package org.budgetanalyzer.currency.fixture;

import org.budgetanalyzer.currency.domain.CurrencySeries;

/**
 * Fluent builder for creating {@link CurrencySeries} test data.
 *
 * <p>Provides sensible defaults and convenience methods for common test scenarios. All builder
 * methods return {@code this} for method chaining.
 *
 * <p><b>Defaults:</b>
 *
 * <ul>
 *   <li>Currency Code: EUR
 *   <li>Provider Series ID: DEXUSEU
 *   <li>Enabled: true
 *   <li>ID: null (new entity)
 *   <li>Audit Timestamps: null (set by JPA on persist)
 * </ul>
 *
 * <p><b>Example Usage:</b>
 *
 * <pre>{@code
 * // Default EUR series
 * CurrencySeries eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
 *
 * // Custom disabled THB series
 * CurrencySeries thbSeries = new CurrencySeriesTestBuilder()
 *     .withCurrencyCode("THB")
 *     .withProviderSeriesId("DEXTHUS")
 *     .enabled(false)
 *     .build();
 *
 * // Persisted entity with ID and timestamps
 * CurrencySeries persisted = CurrencySeriesTestBuilder.defaultGbp()
 *     .buildPersisted();
 * }</pre>
 */
public class CurrencySeriesTestBuilder {

  private Long id;
  private String currencyCode = TestConstants.VALID_CURRENCY_EUR;
  private String providerSeriesId = TestConstants.FRED_SERIES_EUR;
  private boolean enabled = true;

  /**
   * Creates a new builder with default values.
   *
   * @see #defaultEur()
   * @see #defaultThb()
   * @see #defaultGbp()
   */
  public CurrencySeriesTestBuilder() {}

  /**
   * Sets the entity ID.
   *
   * <p>Use this for persisted entities in tests that require an existing database record.
   *
   * @param id the entity ID
   * @return this builder
   */
  public CurrencySeriesTestBuilder withId(Long id) {
    this.id = id;
    return this;
  }

  /**
   * Sets the currency code.
   *
   * @param currencyCode the ISO 4217 currency code (e.g., "EUR", "THB")
   * @return this builder
   * @see TestConstants for valid currency codes
   */
  public CurrencySeriesTestBuilder withCurrencyCode(String currencyCode) {
    this.currencyCode = currencyCode;
    return this;
  }

  /**
   * Sets the provider series ID.
   *
   * @param providerSeriesId the FRED series ID (e.g., "DEXUSEU")
   * @return this builder
   * @see TestConstants for valid FRED series IDs
   */
  public CurrencySeriesTestBuilder withProviderSeriesId(String providerSeriesId) {
    this.providerSeriesId = providerSeriesId;
    return this;
  }

  /**
   * Sets the enabled flag.
   *
   * @param enabled whether the currency series is enabled
   * @return this builder
   */
  public CurrencySeriesTestBuilder enabled(boolean enabled) {
    this.enabled = enabled;
    return this;
  }

  /**
   * Builds a new (unsaved) currency series entity.
   *
   * <p>The returned entity has no ID and no audit timestamps (JPA will set them on persist).
   *
   * @return a new CurrencySeries instance
   */
  public CurrencySeries build() {
    var series = new CurrencySeries();
    series.setId(id);
    series.setCurrencyCode(currencyCode);
    series.setProviderSeriesId(providerSeriesId);
    series.setEnabled(enabled);
    return series;
  }

  /**
   * Builds a persisted currency series entity with ID.
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
   * @return a CurrencySeries instance with ID
   */
  public CurrencySeries buildPersisted() {
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
   * Creates a builder for EUR currency series (Euro).
   *
   * <p>Pre-configured with:
   *
   * <ul>
   *   <li>Currency Code: EUR
   *   <li>Provider Series ID: DEXUSEU
   *   <li>Enabled: true
   * </ul>
   *
   * @return a new builder for EUR
   */
  public static CurrencySeriesTestBuilder defaultEur() {
    return new CurrencySeriesTestBuilder()
        .withCurrencyCode(TestConstants.VALID_CURRENCY_EUR)
        .withProviderSeriesId(TestConstants.FRED_SERIES_EUR);
  }

  /**
   * Creates a builder for THB currency series (Thai Baht).
   *
   * <p>Pre-configured with:
   *
   * <ul>
   *   <li>Currency Code: THB
   *   <li>Provider Series ID: DEXTHUS
   *   <li>Enabled: true
   * </ul>
   *
   * @return a new builder for THB
   */
  public static CurrencySeriesTestBuilder defaultThb() {
    return new CurrencySeriesTestBuilder()
        .withCurrencyCode(TestConstants.VALID_CURRENCY_THB)
        .withProviderSeriesId(TestConstants.FRED_SERIES_THB);
  }

  /**
   * Creates a builder for GBP currency series (British Pound).
   *
   * <p>Pre-configured with:
   *
   * <ul>
   *   <li>Currency Code: GBP
   *   <li>Provider Series ID: DEXUSUK
   *   <li>Enabled: true
   * </ul>
   *
   * @return a new builder for GBP
   */
  public static CurrencySeriesTestBuilder defaultGbp() {
    return new CurrencySeriesTestBuilder()
        .withCurrencyCode(TestConstants.VALID_CURRENCY_GBP)
        .withProviderSeriesId(TestConstants.FRED_SERIES_GBP);
  }

  /**
   * Creates a builder for JPY currency series (Japanese Yen).
   *
   * <p>Pre-configured with:
   *
   * <ul>
   *   <li>Currency Code: JPY
   *   <li>Provider Series ID: DEXJPUS
   *   <li>Enabled: true
   * </ul>
   *
   * @return a new builder for JPY
   */
  public static CurrencySeriesTestBuilder defaultJpy() {
    return new CurrencySeriesTestBuilder()
        .withCurrencyCode(TestConstants.VALID_CURRENCY_JPY)
        .withProviderSeriesId(TestConstants.FRED_SERIES_JPY);
  }

  /**
   * Creates a builder for CAD currency series (Canadian Dollar).
   *
   * <p>Pre-configured with:
   *
   * <ul>
   *   <li>Currency Code: CAD
   *   <li>Provider Series ID: DEXCAUS
   *   <li>Enabled: true
   * </ul>
   *
   * @return a new builder for CAD
   */
  public static CurrencySeriesTestBuilder defaultCad() {
    return new CurrencySeriesTestBuilder()
        .withCurrencyCode(TestConstants.VALID_CURRENCY_CAD)
        .withProviderSeriesId(TestConstants.FRED_SERIES_CAD);
  }

  /**
   * Creates a builder for a disabled currency series.
   *
   * <p>Uses EUR as the default currency but sets enabled to false.
   *
   * @return a new builder with enabled=false
   */
  public static CurrencySeriesTestBuilder disabled() {
    return defaultEur().enabled(false);
  }
}
