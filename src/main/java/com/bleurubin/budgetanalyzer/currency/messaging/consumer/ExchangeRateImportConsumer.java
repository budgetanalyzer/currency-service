package com.bleurubin.budgetanalyzer.currency.messaging.consumer;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.bleurubin.budgetanalyzer.currency.messaging.message.CurrencyCreatedMessage;
import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateImportService;

/**
 * Consumer for currency-related messages.
 *
 * <p>Processes currency created messages by triggering asynchronous exchange rate imports.
 */
@Configuration
public class ExchangeRateImportConsumer {

  private static final Logger log = LoggerFactory.getLogger(ExchangeRateImportConsumer.class);

  private final ExchangeRateImportService exchangeRateImportService;

  public ExchangeRateImportConsumer(ExchangeRateImportService exchangeRateImportService) {
    this.exchangeRateImportService = exchangeRateImportService;
  }

  /**
   * Consumer function for currency created messages.
   *
   * <p>Bean name "importExchangeRates" creates binding "importExchangeRates-in-0". Errors are
   * logged but not thrown - Spring Cloud Stream will retry via RabbitMQ.
   *
   * @return Consumer function processing CurrencyCreatedMessage
   */
  @Bean
  public Consumer<CurrencyCreatedMessage> importExchangeRates() {
    return message -> {
      try {
        log.info(
            "Received currency created message: currencySeriesId={}, currencyCode={}",
            message.currencySeriesId(),
            message.currencyCode());

        var result =
            exchangeRateImportService.importExchangeRatesForSeries(message.currencySeriesId());

        log.info(
            "Exchange rate import completed: currencyCode={}, new={}, updated={}, skipped={}",
            message.currencyCode(),
            result.newRecords(),
            result.updatedRecords(),
            result.skippedRecords());
      } catch (Exception e) {
        log.error(
            "Failed to import exchange rates for currency series: {}",
            message.currencySeriesId(),
            e);
        // Don't throw - let Spring Cloud Stream retry mechanism handle it
      }
    };
  }
}
