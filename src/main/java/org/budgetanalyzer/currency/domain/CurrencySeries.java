package org.budgetanalyzer.currency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotNull;

import org.budgetanalyzer.core.domain.AuditableEntity;

/**
 * CurrencySeries entity representing a currency we can fetch exchange rates for from the FRED API.
 */
@Entity
public class CurrencySeries extends AuditableEntity {

  /** Unique identifier for the currencySeries. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** ISO 4217 currency code. */
  @Column(nullable = false, unique = true, length = 3)
  private String currencyCode;

  /** Exchange rate provider series id. */
  @Column(nullable = false, unique = true, length = 50)
  @NotNull
  private String providerSeriesId;

  /** Whether this currency is enabled for exchange rate access. */
  @Column(nullable = false)
  private boolean enabled = true;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getCurrencyCode() {
    return currencyCode;
  }

  public void setCurrencyCode(String currencyCode) {
    this.currencyCode = currencyCode;
  }

  public String getProviderSeriesId() {
    return providerSeriesId;
  }

  public void setProviderSeriesId(String providerSeriesId) {
    this.providerSeriesId = providerSeriesId;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
