package com.bleurubin.budgetanalyzer.currency.scheduler;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.bleurubin.budgetanalyzer.currency.config.CurrencyServiceProperties;
import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateImportService;
import com.bleurubin.core.logging.SafeLogger;

@Component
public class ExchangeRateImportScheduler {

  private static final Logger log = LoggerFactory.getLogger(ExchangeRateImportScheduler.class);

  private final TaskScheduler taskScheduler;
  private final MeterRegistry meterRegistry;
  private final CurrencyServiceProperties properties;
  private final ExchangeRateImportService exchangeRateImportService;

  public ExchangeRateImportScheduler(
      TaskScheduler taskScheduler,
      MeterRegistry meterRegistry,
      CurrencyServiceProperties properties,
      ExchangeRateImportService exchangeRateImportService) {

    this.taskScheduler = taskScheduler;
    this.meterRegistry = meterRegistry;
    this.properties = properties;
    this.exchangeRateImportService = exchangeRateImportService;
  }

  @Scheduled(cron = "${currency-service.exchange-rate-import.cron:0 0 23 * * ?}", zone = "UTC")
  @SchedulerLock(name = "exchangeRateImport", lockAtMostFor = "15m", lockAtLeastFor = "1m")
  public void importDailyRates() {
    var retryConfig = properties.getExchangeRateImport().getRetry();

    log.info(
        "Starting scheduled exchange rate import (max attempts: {}, delay: {} minutes)",
        retryConfig.getMaxAttempts(),
        retryConfig.getDelayMinutes());

    executeImport(1);
  }

  private void executeImport(int attemptNumber) {
    var sample = Timer.start(meterRegistry);

    try {
      var results = exchangeRateImportService.importLatestExchangeRates();
      log.info(
          "Successfully completed exchange rate import on attempt {} for {} currencies: {}",
          attemptNumber,
          results.size(),
          SafeLogger.toJson(results));

      recordSuccess(sample, attemptNumber);
    } catch (Exception e) {
      var maxAttempts = properties.getExchangeRateImport().getRetry().getMaxAttempts();

      log.error(
          "Failed to import exchange rates on attempt {}/{}: {}",
          attemptNumber,
          maxAttempts,
          e.getMessage(),
          e);

      recordFailure(sample, attemptNumber, e);

      if (attemptNumber < maxAttempts) {
        scheduleRetry(attemptNumber + 1);
      } else {
        log.error("All retry attempts exhausted for exchange rate import");
        recordExhausted();
        // TODO: Send alert/notification
      }
    }
  }

  private void scheduleRetry(int attemptNumber) {
    var delayMinutes = calculateDelayMinutes(attemptNumber);
    var retryTime = Instant.now().plus(Duration.ofMinutes(delayMinutes));

    log.info(
        "Scheduling retry attempt {} in {} minutes at {}", attemptNumber, delayMinutes, retryTime);

    meterRegistry
        .counter("exchange.rate.import.retry.scheduled", "attempt", String.valueOf(attemptNumber))
        .increment();

    // schedule another attempt so we can track status as a separate job
    taskScheduler.schedule(
        () -> {
          log.info("Executing retry attempt {} (scheduled retry)", attemptNumber);
          executeImport(attemptNumber);
        },
        retryTime);
  }

  private long calculateDelayMinutes(int attemptNumber) {
    var retryConfig = properties.getExchangeRateImport().getRetry();
    // Fixed: always use the configured delay
    return retryConfig.getDelayMinutes();
  }

  private void recordSuccess(Timer.Sample sample, int attemptNumber) {
    sample.stop(
        Timer.builder("exchange.rate.import.duration")
            .tag("status", "success")
            .tag("attempt", String.valueOf(attemptNumber))
            .register(meterRegistry));

    meterRegistry
        .counter(
            "exchange.rate.import.executions",
            "status",
            "success",
            "attempt",
            String.valueOf(attemptNumber))
        .increment();
  }

  private void recordFailure(Timer.Sample sample, int attemptNumber, Exception e) {
    sample.stop(
        Timer.builder("exchange.rate.import.duration")
            .tag("status", "failure")
            .tag("attempt", String.valueOf(attemptNumber))
            .tag("error", e.getClass().getSimpleName())
            .register(meterRegistry));

    meterRegistry
        .counter(
            "exchange.rate.import.executions",
            "status",
            "failure",
            "attempt",
            String.valueOf(attemptNumber),
            "error",
            e.getClass().getSimpleName())
        .increment();
  }

  private void recordExhausted() {
    meterRegistry.counter("exchange.rate.import.exhausted").increment();
  }
}
