package com.bleurubin.budgetanalyzer.currency.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateImportService;
import com.bleurubin.core.util.JsonUtils;

@Component
public class CurrencyServiceStartupConfig {

  private static final Logger log = LoggerFactory.getLogger(CurrencyServiceStartupConfig.class);

  private final ExchangeRateImportService exchangeRateImportService;
  private final CurrencyServiceProperties currencyServiceProperties;

  public CurrencyServiceStartupConfig(
      ExchangeRateImportService importService, CurrencyServiceProperties properties) {
    this.exchangeRateImportService = importService;
    this.currencyServiceProperties = properties;
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

    log.info("Checking if exchange rate data exists...");

    if (exchangeRateImportService.hasExchangeRateData()) {
      log.info("Exchange rate data exists, skipping startup import");
      return;
    }

    log.warn("No exchange rate data found - triggering initial import");
    try {
      var importResult = exchangeRateImportService.importLatestExchangeRates();
      log.info(
          "Successfully completed startup exchange rate import: {}",
          JsonUtils.toJson(importResult));
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
    log.info("Currency Service Configuration:\n{}", JsonUtils.toJson(currencyServiceProperties));
  }
}
