package org.budgetanalyzer.currency.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import org.budgetanalyzer.core.logging.Sensitive;

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

    /** Cron expression for scheduled import job. */
    private String cron = "0 0 23 * * ?";

    /** Whether to run import on application startup if no data exists. */
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
      /** FRED API base URL. */
      private String baseUrl = "https://api.stlouisfed.org/fred";

      /** FRED API key - should be set via environment variable. */
      @NotBlank(message = "FRED API key must be configured")
      @Sensitive(showLast = 4)
      private String apiKey;

      /** Timeout in seconds for FRED API requests. */
      @Min(1)
      @Max(120)
      private int timeoutSeconds = 30;

      public String getBaseUrl() {
        return baseUrl;
      }

      public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
      }

      public String getApiKey() {
        return apiKey;
      }

      public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
      }

      public int getTimeoutSeconds() {
        return timeoutSeconds;
      }

      public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
      }
    }

    public static class Retry {
      /**
       * Maximum number of retry attempts (including initial attempt). Example: max-attempts=3 means
       * 1 initial + 2 retries.
       */
      @Min(1)
      @Max(10)
      private int maxAttempts = 3;

      /** Delay between retries in minutes. */
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
