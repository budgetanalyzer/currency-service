package org.budgetanalyzer.currency.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import org.budgetanalyzer.currency.base.AbstractControllerTest;
import org.budgetanalyzer.currency.config.CacheConfig;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.ExchangeRateTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;

/**
 * Integration tests for the Redis-backed exchange rate cache.
 *
 * <p>Requires Redis cache to be enabled to verify cache reads, writes, key namespacing, entry
 * expiry, and eviction after import through the API.
 */
@TestPropertySource(properties = "spring.cache.type=redis")
class ExchangeRateImportCacheIntegrationTest extends AbstractControllerTest {

  private static final String EXCHANGE_RATES_REDIS_KEY_PREFIX = "currency-service:exchangeRates::";

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @Autowired private CacheManager cacheManager;

  @Autowired private StringRedisTemplate stringRedisTemplate;

  @BeforeEach
  void setUp() {
    exchangeRateRepository.deleteAll();
    currencySeriesRepository.deleteAll();
    setTestClaims(currenciesWriteClaims());

    var cache = cacheManager.getCache(CacheConfig.EXCHANGE_RATES_CACHE);
    if (cache != null) {
      cache.clear();
    }
  }

  private static ClaimsHeaderTestBuilder currenciesWriteClaims() {
    return ClaimsHeaderTestBuilder.user("usr_writer")
        .withPermissions("currencies:read", "currencies:write");
  }

  @Test
  void shouldEvictCacheAfterImport() throws Exception {
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    var cache = cacheManager.getCache(CacheConfig.EXCHANGE_RATES_CACHE);
    cache.put("EUR:2024-01-01:2024-01-05", "some cached data");

    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(new FredApiStubs.Observation("2024-01-01", "0.8500")));

    performPost("/v1/exchange-rates/import", "").andExpect(status().isOk());

    assertThat(cache.get("EUR:2024-01-01:2024-01-05")).isNull();
  }

  @Test
  void shouldServeCachedEmptyResultWhenRangeHasNoRates() throws Exception {
    saveEurRates(TestConstants.DATE_2024_JAN_01, TestConstants.DATE_2024_JAN_05);
    var startDate = TestConstants.DATE_2024_JUN_15;
    var endDate = TestConstants.DATE_2024_JUN_15.plusDays(10);

    performGetEurExchangeRates(startDate, endDate)
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));

    assertThat(stringRedisTemplate.hasKey(eurRedisKey(startDate, endDate))).isTrue();

    performGetEurExchangeRates(startDate, endDate)
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void shouldServeIdenticalCachedResultWhenRangeHasRates() throws Exception {
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_05;
    saveEurRates(startDate, endDate);

    var firstResponseBody =
        performGetEurExchangeRates(startDate, endDate)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(stringRedisTemplate.hasKey(eurRedisKey(startDate, endDate))).isTrue();

    var secondResponseBody =
        performGetEurExchangeRates(startDate, endDate)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(secondResponseBody).isEqualTo(firstResponseBody);
  }

  @Test
  void shouldStoreCacheEntryUnderServiceNamespace() throws Exception {
    saveEurRates(TestConstants.DATE_2024_JAN_01, TestConstants.DATE_2024_JAN_05);

    performGetEurExchangeRates(TestConstants.DATE_2024_JAN_01, TestConstants.DATE_2024_JAN_05)
        .andExpect(status().isOk());

    assertThat(
            stringRedisTemplate.keys(
                "*" + cacheKey(TestConstants.DATE_2024_JAN_01, TestConstants.DATE_2024_JAN_05)))
        .containsExactly("currency-service:exchangeRates::EUR:2024-01-01:2024-01-05");
  }

  @Test
  void shouldExpireCacheEntryWithinConfiguredTtl() throws Exception {
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_05;
    saveEurRates(startDate, endDate);

    performGetEurExchangeRates(startDate, endDate).andExpect(status().isOk());

    var expirySeconds =
        stringRedisTemplate.getExpire(eurRedisKey(startDate, endDate), TimeUnit.SECONDS);
    assertThat(expirySeconds)
        .isPositive()
        .isLessThanOrEqualTo(CacheConfig.EXCHANGE_RATES_TTL.toSeconds());
  }

  private void saveEurRates(LocalDate startDate, LocalDate endDate) {
    var eurSeries = currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().build());
    exchangeRateRepository.saveAll(
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD));
  }

  private ResultActions performGetEurExchangeRates(LocalDate startDate, LocalDate endDate)
      throws Exception {
    return performGet(
        "/v1/exchange-rates?targetCurrency={currency}&startDate={startDate}&endDate={endDate}",
        TestConstants.VALID_CURRENCY_EUR,
        startDate,
        endDate);
  }

  private static String cacheKey(LocalDate startDate, LocalDate endDate) {
    return TestConstants.VALID_CURRENCY_EUR + ":" + startDate + ":" + endDate;
  }

  private static String eurRedisKey(LocalDate startDate, LocalDate endDate) {
    return EXCHANGE_RATES_REDIS_KEY_PREFIX + cacheKey(startDate, endDate);
  }
}
