package org.budgetanalyzer.currency.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
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
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import org.budgetanalyzer.currency.base.AbstractControllerTest;
import org.budgetanalyzer.currency.config.CacheConfig;
import org.budgetanalyzer.currency.config.FailOpenCache;
import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.ExchangeRateTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.dto.ExchangeRateSeries;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;

/**
 * Integration tests for the Redis-backed exchange rate cache.
 *
 * <p>Requires Redis cache to be enabled to verify cache reads, writes, key namespacing, entry
 * expiry, serialization, and eviction after import through the API. The cache holds one
 * exchange-rate series per currency, keyed by currency code.
 */
@TestPropertySource(properties = "spring.cache.type=redis")
class ExchangeRateImportCacheIntegrationTest extends AbstractControllerTest {

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @Autowired private CacheManager cacheManager;

  @Autowired private StringRedisTemplate stringRedisTemplate;

  @BeforeEach
  void setUp() {
    exchangeRateRepository.deleteAll();
    currencySeriesRepository.deleteAll();
    setTestClaims(currenciesWriteClaims());

    var cache = cacheManager.getCache(CacheConfig.EXCHANGE_RATE_SERIES_CACHE);
    if (cache != null) {
      cache.clear();
    }
  }

  private static ClaimsHeaderTestBuilder currenciesWriteClaims() {
    return ClaimsHeaderTestBuilder.user("usr_writer")
        .withPermissions("currencies:read", "currencies:write");
  }

  @Test
  void shouldDecorateRedisCacheAsTransactionAwareOverFailOpen() {
    assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
    assertThat(cacheManager.getCache(CacheConfig.EXCHANGE_RATE_SERIES_CACHE))
        .asInstanceOf(type(TransactionAwareCacheDecorator.class))
        .extracting(TransactionAwareCacheDecorator::getTargetCache)
        .isInstanceOf(FailOpenCache.class);
  }

  @Test
  void shouldEvictCacheAfterImport() throws Exception {
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eurSeries);

    var cache = cacheManager.getCache(CacheConfig.EXCHANGE_RATE_SERIES_CACHE);
    cache.put(
        TestConstants.EXCHANGE_RATE_SERIES_CACHE_KEY_EUR, TestConstants.EXCHANGE_RATE_SERIES_EUR);
    assertThat(
            cache.get(TestConstants.EXCHANGE_RATE_SERIES_CACHE_KEY_EUR, ExchangeRateSeries.class))
        .isEqualTo(TestConstants.EXCHANGE_RATE_SERIES_EUR);

    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(new FredApiStubs.Observation("2024-01-01", "0.8500")));

    performPost("/v1/exchange-rates/import", "").andExpect(status().isOk());

    assertThat(cache.get(TestConstants.EXCHANGE_RATE_SERIES_CACHE_KEY_EUR)).isNull();
  }

  @Test
  void shouldServeEmptyResultFromCachedSeriesWhenRangeHasNoRates() throws Exception {
    saveEurRates(TestConstants.DATE_2024_JAN_01, TestConstants.DATE_2024_JAN_05);
    var startDate = TestConstants.DATE_2024_JUN_15;
    var endDate = TestConstants.DATE_2024_JUN_15.plusDays(10);

    performGetEurExchangeRates(startDate, endDate)
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));

    assertThat(stringRedisTemplate.hasKey(TestConstants.EXCHANGE_RATE_SERIES_REDIS_KEY_EUR))
        .isTrue();

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

    assertThat(stringRedisTemplate.hasKey(TestConstants.EXCHANGE_RATE_SERIES_REDIS_KEY_EUR))
        .isTrue();

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

    assertThat(stringRedisTemplate.keys("*" + TestConstants.EXCHANGE_RATE_SERIES_CACHE_KEY_EUR))
        .containsExactly(TestConstants.EXCHANGE_RATE_SERIES_REDIS_KEY_EUR);
  }

  @Test
  void shouldCreateOneCacheKeyForDifferentRangesOfOneCurrency() throws Exception {
    saveEurRates(TestConstants.DATE_2024_JAN_01, TestConstants.DATE_2024_JAN_15);

    performGetEurExchangeRates(TestConstants.DATE_2024_JAN_01, TestConstants.DATE_2024_JAN_05)
        .andExpect(status().isOk());
    performGetEurExchangeRates(TestConstants.DATE_2024_JAN_02, TestConstants.DATE_2024_JAN_15)
        .andExpect(status().isOk());
    performGetEurExchangeRates(TestConstants.DATE_2024_JAN_05, TestConstants.DATE_2030_JAN_01)
        .andExpect(status().isOk());
    performGet("/v1/exchange-rates?targetCurrency={currency}", TestConstants.VALID_CURRENCY_EUR)
        .andExpect(status().isOk());

    assertThat(stringRedisTemplate.keys(TestConstants.EXCHANGE_RATE_SERIES_REDIS_KEY_PATTERN))
        .containsExactly(TestConstants.EXCHANGE_RATE_SERIES_REDIS_KEY_EUR);
  }

  @Test
  void shouldRejectCurrencyDisabledAfterItsSeriesWasCached() throws Exception {
    var eurSeries = saveEurRates(TestConstants.DATE_2024_JAN_01, TestConstants.DATE_2024_JAN_05);
    performGetEurExchangeRates(TestConstants.DATE_2024_JAN_01, TestConstants.DATE_2024_JAN_05)
        .andExpect(status().isOk());
    assertThat(stringRedisTemplate.hasKey(TestConstants.EXCHANGE_RATE_SERIES_REDIS_KEY_EUR))
        .isTrue();

    eurSeries.setEnabled(false);
    currencySeriesRepository.save(eurSeries);

    performGetEurExchangeRates(TestConstants.DATE_2024_JAN_01, TestConstants.DATE_2024_JAN_05)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("CURRENCY_NOT_ENABLED"));
  }

  @Test
  void shouldRoundTripCachedSeriesThroughConfiguredSerializer() {
    var cache = cacheManager.getCache(CacheConfig.EXCHANGE_RATE_SERIES_CACHE);

    cache.put(
        TestConstants.EXCHANGE_RATE_SERIES_CACHE_KEY_EUR, TestConstants.EXCHANGE_RATE_SERIES_EUR);

    var storedJson =
        stringRedisTemplate.opsForValue().get(TestConstants.EXCHANGE_RATE_SERIES_REDIS_KEY_EUR);
    // No type ids (class names) and no pretty-printing newlines in the compact cached value
    assertThat(storedJson)
        .startsWith("{")
        .doesNotContain("@class", "org.budgetanalyzer", "java.util", "\n");
    assertThat(
            cache.get(TestConstants.EXCHANGE_RATE_SERIES_CACHE_KEY_EUR, ExchangeRateSeries.class))
        .isEqualTo(TestConstants.EXCHANGE_RATE_SERIES_EUR);
  }

  @Test
  void shouldCacheDenseSeriesReadableThroughConfiguredSerializer() throws Exception {
    saveEurRates(TestConstants.DATE_2024_JAN_01, TestConstants.DATE_2024_JAN_05);

    performGetEurExchangeRates(TestConstants.DATE_2024_JAN_02, TestConstants.DATE_2024_JAN_02)
        .andExpect(status().isOk());

    var cachedSeries =
        cacheManager
            .getCache(CacheConfig.EXCHANGE_RATE_SERIES_CACHE)
            .get(TestConstants.EXCHANGE_RATE_SERIES_CACHE_KEY_EUR, ExchangeRateSeries.class);
    assertThat(cachedSeries).isNotNull();
    assertThat(cachedSeries.startDate()).isEqualTo(TestConstants.DATE_2024_JAN_01);
    assertThat(cachedSeries.endDate()).isEqualTo(TestConstants.DATE_2024_JAN_05);
    assertThat(cachedSeries.publishedDateOffsets()).containsExactly(0, 0, 0, 0, 0);
  }

  @Test
  void shouldExpireCacheEntryWithinConfiguredTtl() throws Exception {
    var startDate = TestConstants.DATE_2024_JAN_01;
    var endDate = TestConstants.DATE_2024_JAN_05;
    saveEurRates(startDate, endDate);

    performGetEurExchangeRates(startDate, endDate).andExpect(status().isOk());

    var expirySeconds =
        stringRedisTemplate.getExpire(
            TestConstants.EXCHANGE_RATE_SERIES_REDIS_KEY_EUR, TimeUnit.SECONDS);
    assertThat(expirySeconds)
        .isPositive()
        .isLessThanOrEqualTo(CacheConfig.EXCHANGE_RATES_TTL.toSeconds());
  }

  private CurrencySeries saveEurRates(LocalDate startDate, LocalDate endDate) {
    var eurSeries = currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().build());
    exchangeRateRepository.saveAll(
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries, startDate, endDate, TestConstants.RATE_EUR_USD));
    return eurSeries;
  }

  private ResultActions performGetEurExchangeRates(LocalDate startDate, LocalDate endDate)
      throws Exception {
    return performGet(
        "/v1/exchange-rates?targetCurrency={currency}&startDate={startDate}&endDate={endDate}",
        TestConstants.VALID_CURRENCY_EUR,
        startDate,
        endDate);
  }
}
