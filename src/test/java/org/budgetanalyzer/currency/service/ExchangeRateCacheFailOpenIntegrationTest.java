package org.budgetanalyzer.currency.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import ch.qos.logback.classic.Level;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.config.CacheConfig;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.ExchangeRateTestBuilder;
import org.budgetanalyzer.currency.fixture.FailOpenCacheLogCapture;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.dto.ExchangeRateData;

/**
 * Verifies that cache writes and evictions deferred until after commit fail open.
 *
 * <p>The application connects to its own Redis container as the {@code currency-service} ACL user,
 * which may read cache entries but not write, delete, or scan them. Cache reads therefore miss, and
 * every put or clear fails in Redis with {@code NOPERM}, which Spring Data Redis reports as a
 * {@link RedisSystemException}. Each service call runs inside an explicit enclosing transaction, so
 * its cache operation is deferred to that transaction's commit regardless of the order of the
 * transaction and cache interceptors.
 */
@TestPropertySource(properties = "spring.cache.type=redis")
class ExchangeRateCacheFailOpenIntegrationTest extends AbstractWireMockTest {

  private static final String ACL_FILE_PATH = "/etc/redis/users.acl";

  @Container
  private static final GenericContainer<?> REDIS_CONTAINER =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withCopyToContainer(
              Transferable.of(
                  "user default off\n"
                      + "user "
                      + TestConstants.REDIS_CURRENCY_SERVICE_USER
                      + " on >"
                      + TestConstants.REDIS_CURRENCY_SERVICE_TEST_PASSWORD
                      + " "
                      + TestConstants.REDIS_CURRENCY_SERVICE_READ_ONLY_RULES
                      + "\n"),
              ACL_FILE_PATH)
          .withCommand("redis-server", "--aclfile", ACL_FILE_PATH);

  @Autowired private ExchangeRateService exchangeRateService;

  @Autowired private ExchangeRateImportService exchangeRateImportService;

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @Autowired private PlatformTransactionManager platformTransactionManager;

  private FailOpenCacheLogCapture failOpenCacheLogCapture;

  @BeforeEach
  void startLogCapture() {
    failOpenCacheLogCapture = FailOpenCacheLogCapture.start();
  }

  @AfterEach
  void stopLogCapture() {
    failOpenCacheLogCapture.close();
  }

  @Test
  void shouldReturnDatabaseResultWhenDeferredCachePutFailsAfterCommit() {
    var eurSeries = currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().build());
    exchangeRateRepository.saveAll(
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            TestConstants.DATE_2024_JAN_01,
            TestConstants.DATE_2024_JAN_05,
            TestConstants.RATE_EUR_USD));
    var readOnlyTransactionTemplate = readOnlyTransactionTemplate();

    var exchangeRates =
        readOnlyTransactionTemplate.execute(
            transactionStatus -> {
              var exchangeRatesInTransaction =
                  exchangeRateService.getExchangeRates(
                      TestConstants.CURRENCY_EUR,
                      TestConstants.DATE_2024_JAN_01,
                      TestConstants.DATE_2024_JAN_05);
              // The miss was read from Redis; the put waits for the commit
              assertThat(failOpenCacheLogCapture.warnings()).isEmpty();
              return exchangeRatesInTransaction;
            });

    assertThat(failOpenCacheLogCapture.warnings())
        .singleElement()
        .asString()
        .startsWith(
            TestConstants.failOpenCacheWarningPrefix(
                    "put",
                    CacheConfig.EXCHANGE_RATE_SERIES_CACHE,
                    TestConstants.EXCHANGE_RATE_SERIES_CACHE_KEY_EUR)
                + RedisSystemException.class.getName());
    assertThat(failOpenCacheLogCapture.throwableClassNames(Level.WARN)).isEmpty();
    assertThat(exchangeRates)
        .hasSize(5)
        .extracting(ExchangeRateData::date)
        .startsWith(TestConstants.DATE_2024_JAN_01)
        .endsWith(TestConstants.DATE_2024_JAN_05);
  }

  @Test
  void shouldCommitImportWhenDeferredCacheClearFailsAfterCommit() {
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().build());
    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(
            new FredApiStubs.Observation(TestConstants.DATE_2024_JAN_01.toString(), "0.8500"),
            new FredApiStubs.Observation(TestConstants.DATE_2024_JAN_02.toString(), "0.8510")));
    var transactionTemplate = new TransactionTemplate(platformTransactionManager);

    var importResults =
        transactionTemplate.execute(
            transactionStatus -> {
              var importResultsInTransaction =
                  exchangeRateImportService.importLatestExchangeRates();
              // The clear waits for the commit
              assertThat(failOpenCacheLogCapture.warnings()).isEmpty();
              return importResultsInTransaction;
            });

    assertThat(failOpenCacheLogCapture.warnings())
        .singleElement()
        .asString()
        .startsWith(
            TestConstants.failOpenCacheWarningPrefix(
                    "clear", CacheConfig.EXCHANGE_RATE_SERIES_CACHE)
                + RedisSystemException.class.getName());
    assertThat(failOpenCacheLogCapture.throwableClassNames(Level.WARN)).isEmpty();
    assertThat(importResults)
        .singleElement()
        .satisfies(
            importResult -> {
              assertThat(importResult.currencyCode()).isEqualTo(TestConstants.VALID_CURRENCY_EUR);
              assertThat(importResult.newRecords()).isEqualTo(2);
            });
    var committedExchangeRateCount =
        readOnlyTransactionTemplate()
            .execute(
                transactionStatus ->
                    exchangeRateRepository
                        .findByCurrencySeriesCurrencyCodeOrderByDateAsc(
                            TestConstants.VALID_CURRENCY_EUR)
                        .size());
    assertThat(committedExchangeRateCount).isEqualTo(2);
  }

  private TransactionTemplate readOnlyTransactionTemplate() {
    var readOnlyTransactionTemplate = new TransactionTemplate(platformTransactionManager);
    readOnlyTransactionTemplate.setReadOnly(true);
    return readOnlyTransactionTemplate;
  }

  /** Connects the application to the read-only Redis container as the service user. */
  @TestConfiguration(proxyBeanMethods = false)
  static class ReadOnlyRedisConfig {

    @Bean
    @Primary
    DataRedisConnectionDetails readOnlyRedisConnectionDetails() {
      return new DataRedisConnectionDetails() {
        @Override
        public String getUsername() {
          return TestConstants.REDIS_CURRENCY_SERVICE_USER;
        }

        @Override
        public String getPassword() {
          return TestConstants.REDIS_CURRENCY_SERVICE_TEST_PASSWORD;
        }

        @Override
        public Standalone getStandalone() {
          return Standalone.of(REDIS_CONTAINER.getHost(), REDIS_CONTAINER.getFirstMappedPort());
        }
      };
    }
  }
}
