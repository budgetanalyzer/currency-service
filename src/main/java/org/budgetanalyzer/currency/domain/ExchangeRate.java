package org.budgetanalyzer.currency.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

import org.budgetanalyzer.core.domain.AuditableEntity;

/**
 * Exchange rate entity representing historical currency exchange rates.
 *
 * <p>Design: Denormalized Relationship for Performance
 *
 * <p>This entity uses a DENORMALIZED design pattern where both a foreign key relationship
 * (currencySeries) AND a direct currency field (targetCurrency) coexist. While this creates slight
 * data redundancy, it provides critical performance benefits:
 *
 * <p><b>Why Denormalization?</b>
 *
 * <ul>
 *   <li><b>Query Performance:</b> API queries filter by targetCurrency (WHERE target_currency =
 *       'THB'). With denormalization, we get direct index lookup. Without it, we'd need expensive
 *       JOINs to currency_series table on every query.
 *   <li><b>Lazy Loading Avoidance:</b> API responses only need the currency code.
 *       getTargetCurrency() reads from local column (no database query). Without denormalization,
 *       getCurrencySeries().getCurrencyCode() would trigger lazy loading (N+1 query problem).
 *   <li><b>Index Efficiency:</b> Existing compound indexes use targetCurrency. These would be
 *       useless without the denormalized column.
 *   <li><b>Backward Compatibility:</b> Existing queries work unchanged.
 * </ul>
 *
 * <p><b>When to Use Each Field:</b>
 *
 * <ul>
 *   <li><b>targetCurrency:</b> Use in API responses, queries, filters (hot path, no lazy load)
 *   <li><b>currencySeries:</b> Use in admin/audit queries when you need provider metadata (JOIN
 *       needed)
 * </ul>
 *
 * <p><b>Trade-off:</b> ~3 bytes of storage per row in exchange for 10-100x query performance
 * improvement.
 */
@Entity
@Table(
    name = "exchange_rate",
    uniqueConstraints =
        @UniqueConstraint(columnNames = {"base_currency", "target_currency", "date"}))
public class ExchangeRate extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * Foreign key to currency_series table.
   *
   * <p>Links this exchange rate to its source currency series configuration, which contains:
   *
   * <ul>
   *   <li>Provider series ID (e.g., 'DEXTHUS' for THB from FRED)
   *   <li>Currency code (e.g., 'THB')
   *   <li>Enabled status
   * </ul>
   *
   * <p><b>Fetch Strategy: LAZY (default)</b>
   *
   * <p>This relationship uses lazy loading because:
   *
   * <ul>
   *   <li>API responses only need targetCurrency (available without JOIN)
   *   <li>Only admin/audit queries need full series data
   *   <li>Prevents unnecessary JOINs in 99% of queries
   * </ul>
   *
   * <p><b>WARNING:</b> Do NOT call getCurrencySeries() in hot paths (API responses, list
   * operations). This triggers lazy loading and causes N+1 query problems. Use targetCurrency field
   * instead.
   *
   * <p><b>When to access currencySeries:</b>
   *
   * <ul>
   *   <li>Admin pages showing provider metadata
   *   <li>Audit trails
   *   <li>Explicit JOIN FETCH queries
   * </ul>
   */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "currency_series_id", nullable = false)
  @NotNull
  private CurrencySeries currencySeries;

  @NotNull private Currency baseCurrency;

  /**
   * ISO 4217 target currency code.
   *
   * <p><b>Denormalized Field for Performance</b>
   *
   * <p>This field duplicates currencySeries.currencyCode, but exists for critical performance
   * reasons:
   *
   * <ul>
   *   <li><b>Direct Access:</b> API responses can read currency code without lazy loading
   *       currencySeries
   *   <li><b>Query Performance:</b> Queries filter by this column using indexes, avoiding JOINs
   *   <li><b>Index Utilization:</b> Compound indexes use this field (idx_exchange_rate_target_date)
   * </ul>
   *
   * <p><b>Consistency:</b> This field should always match currencySeries.currencyCode. The
   * ExchangeRateImportService ensures consistency by setting both fields during import.
   *
   * <p><b>Always use this field for:</b>
   *
   * <ul>
   *   <li>API responses
   *   <li>Query filters
   *   <li>Display purposes
   * </ul>
   */
  @NotNull private Currency targetCurrency;

  @NotNull private LocalDate date;

  @Column(precision = 38, scale = 4)
  private BigDecimal rate;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public CurrencySeries getCurrencySeries() {
    return currencySeries;
  }

  public void setCurrencySeries(CurrencySeries currencySeries) {
    this.currencySeries = currencySeries;
  }

  public Currency getBaseCurrency() {
    return baseCurrency;
  }

  public void setBaseCurrency(Currency baseCurrency) {
    this.baseCurrency = baseCurrency;
  }

  public Currency getTargetCurrency() {
    return targetCurrency;
  }

  public void setTargetCurrency(Currency targetCurrency) {
    this.targetCurrency = targetCurrency;
  }

  public LocalDate getDate() {
    return date;
  }

  public void setDate(LocalDate date) {
    this.date = date;
  }

  public BigDecimal getRate() {
    return rate;
  }

  public void setRate(BigDecimal rate) {
    this.rate = rate;
  }
}
