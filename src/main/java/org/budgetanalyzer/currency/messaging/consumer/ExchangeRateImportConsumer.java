package org.budgetanalyzer.currency.messaging.consumer;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.budgetanalyzer.currency.messaging.message.CurrencyCreatedMessage;
import org.budgetanalyzer.currency.service.ExchangeRateImportService;
import org.budgetanalyzer.service.servlet.http.CorrelationIdFilter;

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
   * <p>Bean name "importExchangeRates" creates binding "importExchangeRates-in-0".
   *
   * <p><b>Error Handling:</b> Exceptions are allowed to propagate to Spring Cloud Stream's retry
   * mechanism. After exhausting retries (configured in application.yml), failed messages are sent
   * to the Dead Letter Queue.
   *
   * <p><b>Distributed Tracing:</b> Uses MDC (Mapped Diagnostic Context) - all log statements within
   * this consumer automatically include correlation ID and event type.
   *
   * @return Consumer function processing CurrencyCreatedMessage
   */
  @Bean
  public Consumer<CurrencyCreatedMessage> importExchangeRates() {
    return message -> {
      MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, message.correlationId());
      MDC.put("eventType", "currency_created");

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
      } finally {
        MDC.clear();
      }
    };
  }
}
