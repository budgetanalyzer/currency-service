package com.bleurubin.budgetanalyzer.currency.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;

@Schema(description = "Exchange rate ")
public class ExchangeRateResponse {

  @Schema(
      description = "Base currency for conversion (currently only USD supported)",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "USD")
  private Currency baseCurrency;

  @Schema(
      description = "Target currency for conversion (currently only THB supported)",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "THB")
  private Currency targetCurrency;

  @Schema(
      description = "Date for the given rate",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "32.68")
  private LocalDate date;

  @Schema(
      description = "Exchange rate for the given date",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "32.68")
  private BigDecimal rate;

  public ExchangeRateResponse() {}

  private ExchangeRateResponse(Builder builder) {
    this.baseCurrency = builder.baseCurrency;
    this.targetCurrency = builder.targetCurrency;
    this.date = builder.date;
    this.rate = builder.rate;
  }

  public static Builder builder() {
    return new Builder();
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

  public static class Builder {
    private Currency baseCurrency;
    private Currency targetCurrency;
    private LocalDate date;
    private BigDecimal rate;

    public Builder baseCurrency(Currency baseCurrency) {
      this.baseCurrency = baseCurrency;
      return this;
    }

    public Builder targetCurrency(Currency targetCurrency) {
      this.targetCurrency = targetCurrency;
      return this;
    }

    public Builder date(LocalDate date) {
      this.date = date;
      return this;
    }

    public Builder rate(BigDecimal rate) {
      this.rate = rate;
      return this;
    }

    public ExchangeRateResponse build() {
      return new ExchangeRateResponse(this);
    }
  }
}
