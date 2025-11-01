package com.bleurubin.budgetanalyzer.currency.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

import com.bleurubin.core.domain.AuditableEntity;

@Entity
@Table(
    name = "exchange_rate",
    uniqueConstraints =
        @UniqueConstraint(columnNames = {"base_currency", "target_currency", "date"}))
public class ExchangeRate extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @NotNull private Currency baseCurrency;

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
