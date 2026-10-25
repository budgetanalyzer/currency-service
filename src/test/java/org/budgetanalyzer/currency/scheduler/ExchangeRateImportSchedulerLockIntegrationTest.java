package org.budgetanalyzer.currency.scheduler;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;

/**
 * Integration tests for the {@link ExchangeRateImportScheduler} distributed lock configuration.
 *
 * <p>Uses the application's ShedLock-proxied scheduler bean against the Flyway-managed {@code
 * shedlock} table in Testcontainers PostgreSQL.
 */
class ExchangeRateImportSchedulerLockIntegrationTest extends AbstractWireMockTest {

  private static final String EXCHANGE_RATE_IMPORT_LOCK_NAME = "exchangeRateImport";
  private static final Duration LOCK_AT_LEAST_FOR = Duration.ofMinutes(1);
  private static final long CLOCK_TOLERANCE_SECONDS = 5;

  @Autowired private ExchangeRateImportScheduler exchangeRateImportScheduler;

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Test
  void shouldHoldDatabaseTimedLockAndSkipImmediateSecondRun() {
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().build());
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);

    exchangeRateImportScheduler.importDailyRates();

    var databaseUtcTime = testDatabaseHelper.currentDatabaseUtcTime();
    var shedLockRow = testDatabaseHelper.findShedLock(EXCHANGE_RATE_IMPORT_LOCK_NAME).orElseThrow();
    assertThat(shedLockRow.lockedAt())
        .isCloseTo(databaseUtcTime, within(CLOCK_TOLERANCE_SECONDS, ChronoUnit.SECONDS));
    assertThat(shedLockRow.lockUntil())
        .isCloseTo(
            shedLockRow.lockedAt().plus(LOCK_AT_LEAST_FOR),
            within(CLOCK_TOLERANCE_SECONDS, ChronoUnit.SECONDS));
    wireMockServer.verify(
        1, getRequestedFor(urlPathEqualTo(TestConstants.FRED_API_PATH_OBSERVATIONS)));

    exchangeRateImportScheduler.importDailyRates();

    wireMockServer.verify(
        1, getRequestedFor(urlPathEqualTo(TestConstants.FRED_API_PATH_OBSERVATIONS)));
    assertThat(testDatabaseHelper.findShedLock(EXCHANGE_RATE_IMPORT_LOCK_NAME))
        .contains(shedLockRow);
  }
}
