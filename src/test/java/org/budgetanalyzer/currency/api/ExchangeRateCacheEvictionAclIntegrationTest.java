package org.budgetanalyzer.currency.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import org.budgetanalyzer.currency.base.AbstractControllerTest;
import org.budgetanalyzer.currency.config.CacheConfig;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;

/**
 * Verifies that import eviction clears the exchange rate cache under a Redis user without {@code
 * KEYS}.
 *
 * <p>The application connects to its own Redis container as the {@code currency-service} ACL user,
 * whose rules match the orchestration grant without {@code +keys}. The shared test Redis container
 * is not used because other tests read it with {@code KEYS}.
 */
@TestPropertySource(properties = "spring.cache.type=redis")
class ExchangeRateCacheEvictionAclIntegrationTest extends AbstractControllerTest {

  private static final String ACL_FILE_PATH = "/etc/redis/users.acl";

  private static final String EXCHANGE_RATES_KEY_PATTERN = "currency-service:exchangeRates::*";

  private static final String UNRELATED_SERVICE_KEY = "currency-service:unrelated";

  /** Spans several {@code SCAN} and {@code DEL} batches. */
  private static final int CACHED_ENTRY_COUNT = CacheConfig.CACHE_CLEAR_BATCH_SIZE * 2 + 1;

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
                      + TestConstants.REDIS_CURRENCY_SERVICE_RULES_WITHOUT_KEYS
                      + "\n"),
              ACL_FILE_PATH)
          .withCommand("redis-server", "--aclfile", ACL_FILE_PATH);

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Autowired private CacheManager cacheManager;

  @Autowired private StringRedisTemplate stringRedisTemplate;

  @BeforeEach
  void setUp() {
    setTestClaims(
        ClaimsHeaderTestBuilder.user(TestConstants.WRITER_USER_ID)
            .withPermissions(TestConstants.PERMISSION_CURRENCIES_WRITE));
    cacheManager.getCache(CacheConfig.EXCHANGE_RATES_CACHE).clear();
    stringRedisTemplate.delete(UNRELATED_SERVICE_KEY);
  }

  @Test
  void shouldDenyKeysCommandToServiceUser() {
    assertThatThrownBy(() -> stringRedisTemplate.keys(EXCHANGE_RATES_KEY_PATTERN))
        .rootCause()
        .hasMessageContaining("NOPERM");
  }

  @Test
  void shouldEvictEveryCachedEntryAfterImportCommits() throws Exception {
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().build());
    var exchangeRatesCache = cacheManager.getCache(CacheConfig.EXCHANGE_RATES_CACHE);
    for (var entryIndex = 0; entryIndex < CACHED_ENTRY_COUNT; entryIndex++) {
      exchangeRatesCache.put(TestConstants.VALID_CURRENCY_EUR + ":" + entryIndex, entryIndex);
    }
    stringRedisTemplate.opsForValue().set(UNRELATED_SERVICE_KEY, UNRELATED_SERVICE_KEY);
    assertThat(countExchangeRatesKeys()).isEqualTo(CACHED_ENTRY_COUNT);

    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(new FredApiStubs.Observation("2024-01-01", "0.8500")));

    performPost("/v1/exchange-rates/import", "").andExpect(status().isOk());

    assertThat(countExchangeRatesKeys()).isZero();
    assertThat(stringRedisTemplate.hasKey(UNRELATED_SERVICE_KEY)).isTrue();
  }

  private long countExchangeRatesKeys() {
    var scanOptions =
        ScanOptions.scanOptions()
            .match(EXCHANGE_RATES_KEY_PATTERN)
            .count(CacheConfig.CACHE_CLEAR_BATCH_SIZE)
            .build();
    try (var cursor = stringRedisTemplate.scan(scanOptions)) {
      return cursor.stream().count();
    }
  }

  /** Connects the application to the ACL-restricted Redis container as the service user. */
  @TestConfiguration(proxyBeanMethods = false)
  static class RestrictedRedisConfig {

    @Bean
    @Primary
    DataRedisConnectionDetails restrictedRedisConnectionDetails() {
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
