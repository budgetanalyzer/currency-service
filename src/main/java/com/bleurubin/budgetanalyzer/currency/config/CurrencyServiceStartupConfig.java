package com.bleurubin.budgetanalyzer.currency.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateImportService;
import com.bleurubin.core.logging.SafeLogger;
import com.bleurubin.service.http.HttpLoggingProperties;

@Component
public class CurrencyServiceStartupConfig {

  private static final Logger log = LoggerFactory.getLogger(CurrencyServiceStartupConfig.class);

  private final CurrencyServiceProperties currencyServiceProperties;
  private final HttpLoggingProperties httpLoggingProperties;
  private final ExchangeRateImportService exchangeRateImportService;

  public CurrencyServiceStartupConfig(
      CurrencyServiceProperties properties,
      HttpLoggingProperties httpLoggingProperties,
      ExchangeRateImportService exchangeRateImportService) {
    this.currencyServiceProperties = properties;
    this.httpLoggingProperties = httpLoggingProperties;
    this.exchangeRateImportService = exchangeRateImportService;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    logConfiguration();
    importIfNeeded();
  }

  private void importIfNeeded() {
    if (!currencyServiceProperties.getExchangeRateImport().isImportOnStartup()) {
      log.info("Startup import is disabled");
      return;
    }

    try {
      var results = exchangeRateImportService.importMissingExchangeRates();
      log.info(
          "Successfully completed startup exchange rate import for {} currencies: {}",
          results.size(),
          SafeLogger.toJson(results));
    } catch (Exception e) {
      log.error("CRITICAL: failed to import exchange rates on startup, exiting...", e);
      throw new IllegalStateException(
          "Application cannot start without exchange rate data. "
              + "Initial import failed: "
              + e.getMessage(),
          e);
    }
  }

  private void logConfiguration() {
    log.info("Currency Service Configuration:\n{}", SafeLogger.toJson(currencyServiceProperties));
    log.info("Http Logging Configuration:\n{}", SafeLogger.toJson(httpLoggingProperties));
  }
}
