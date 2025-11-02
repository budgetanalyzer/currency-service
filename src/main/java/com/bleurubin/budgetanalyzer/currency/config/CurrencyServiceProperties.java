package com.bleurubin.budgetanalyzer.currency.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "currency-service")
@Validated
public class CurrencyServiceProperties {

  @Valid private ExchangeRateImport exchangeRateImport = new ExchangeRateImport();

  public ExchangeRateImport getExchangeRateImport() {
    return exchangeRateImport;
  }

  public void setExchangeRateImport(ExchangeRateImport exchangeRateImport) {
    this.exchangeRateImport = exchangeRateImport;
  }

  public static class ExchangeRateImport {

    /** Cron expression for scheduled import job */
    private String cron = "0 0 23 * * ?";

    /** Whether to run import on application startup if no data exists */
    private boolean importOnStartup = true;

    @Valid private Fred fred = new Fred();

    @Valid private Retry retry = new Retry();

    public String getCron() {
      return cron;
    }

    public void setCron(String cron) {
      this.cron = cron;
    }

    public boolean isImportOnStartup() {
      return importOnStartup;
    }

    public void setImportOnStartup(boolean importOnStartup) {
      this.importOnStartup = importOnStartup;
    }

    public Fred getFred() {
      return fred;
    }

    public void setFred(Fred fred) {
      this.fred = fred;
    }

    public Retry getRetry() {
      return retry;
    }

    public void setRetry(Retry retry) {
      this.retry = retry;
    }

    public static class Fred {
      /** FRED API base URL */
      private String baseUrl = "https://fred.stlouisfed.org";

      /** FRED series ID for USD/THB exchange rate */
      private String seriesId = "DEXTHUS";

      public String getBaseUrl() {
        return baseUrl;
      }

      public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
      }

      public String getSeriesId() {
        return seriesId;
      }

      public void setSeriesId(String seriesId) {
        this.seriesId = seriesId;
      }

      /** Builds the full CSV download URL */
      public String buildCsvUrl(String cosd) {
        String url = baseUrl + "/graph/fredgraph.csv?id=" + seriesId;
        if (cosd != null) {
          url += "&cosd=" + cosd;
        }
        return url;
      }
    }

    public static class Retry {
      /**
       * Maximum number of retry attempts (including initial attempt) Example: max-attempts=3 means
       * 1 initial + 2 retries
       */
      @Min(1)
      @Max(10)
      private int maxAttempts = 3;

      /** Delay between retries in minutes */
      @Min(1)
      @Max(60)
      private long delayMinutes = 5;

      public int getMaxAttempts() {
        return maxAttempts;
      }

      public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
      }

      public long getDelayMinutes() {
        return delayMinutes;
      }

      public void setDelayMinutes(long delayMinutes) {
        this.delayMinutes = delayMinutes;
      }
    }
  }
}
