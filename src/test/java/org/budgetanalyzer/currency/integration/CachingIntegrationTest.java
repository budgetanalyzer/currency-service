package org.budgetanalyzer.currency.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.budgetanalyzer.currency.base.AbstractIntegrationTest;
import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.fixture.ExchangeRateTestBuilder;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.ExchangeRateImportService;
import org.budgetanalyzer.currency.service.ExchangeRateService;
import org.budgetanalyzer.currency.service.dto.ExchangeRateData;

/**
 * Integration tests for Redis distributed caching behavior.
 *
 * <p>Tests verify application-specific caching logic:
 *
 * <ul>
 *   <li>Cache key format: {@code {currency}:{startDate}:{endDate}}
 *   <li>Multi-currency cache isolation (separate cache entries per currency)
 *   <li>Cache eviction strategy: {@code @CacheEvict(allEntries=true)} after imports
 * </ul>
 *
 * <p><b>Cache Configuration:</b> This test explicitly enables Redis cache via
 * {@code @TestPropertySource}. Most tests have cache disabled by default for performance.
 *
 * <p><b>Framework behavior not tested:</b> Spring Cache population, cache hits, and serialization
 * are framework responsibilities covered by Spring's own test suite.
 *
 * @see org.budgetanalyzer.currency.config.CacheConfig
 * @see org.budgetanalyzer.currency.service.ExchangeRateService#getExchangeRates
 */
@TestPropertySource(properties = "spring.cache.type=redis")
class CachingIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ExchangeRateService exchangeRateService;

  @Autowired private ExchangeRateImportService exchangeRateImportService;

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @MockitoSpyBean private ExchangeRateRepository exchangeRateRepositorySpy;

  @Autowired private CacheManager cacheManager;

  private CurrencySeries thbSeries;
  private CurrencySeries eurSeries;
  private CurrencySeries gbpSeries;

  @BeforeEach
  void setUp() {
    // Find existing currency series from seed data (restored by AbstractIntegrationTest)
    thbSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_THB).orElseThrow();
    eurSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_EUR).orElseThrow();
    gbpSeries =
        currencySeriesRepository.findByCurrencyCode(TestConstants.VALID_CURRENCY_GBP).orElseThrow();

    // Enable the series for testing
    thbSeries.setEnabled(true);
    eurSeries.setEnabled(true);
    gbpSeries.setEnabled(true);
    currencySeriesRepository.saveAll(List.of(thbSeries, eurSeries, gbpSeries));

    // Create test exchange rates for THB (Thai Baht)
    var thbRates =
        ExchangeRateTestBuilder.buildDateRange(
            thbSeries,
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 10),
            TestConstants.RATE_THB_USD);
    exchangeRateRepository.saveAll(thbRates);

    // Create test exchange rates for EUR (Euro)
    var eurRates =
        ExchangeRateTestBuilder.buildDateRange(
            eurSeries,
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 10),
            TestConstants.RATE_EUR_USD);
    exchangeRateRepository.saveAll(eurRates);

    // Create test exchange rates for GBP (British Pound)
    var gbpRates =
        ExchangeRateTestBuilder.buildDateRange(
            gbpSeries,
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 10),
            TestConstants.RATE_GBP_USD);
    exchangeRateRepository.saveAll(gbpRates);

    // Clear cache before each test to ensure clean state
    clearAllCaches();
  }

  @Test
  void shouldFollowCacheKeyFormat() {
    // Given: Multiple queries with different parameters
    var thbCurrency = Currency.getInstance(TestConstants.VALID_CURRENCY_THB);
    var startDate1 = LocalDate.of(2024, 1, 1);
    var endDate1 = LocalDate.of(2024, 1, 5);
    var startDate2 = LocalDate.of(2024, 1, 6);
    var endDate2 = LocalDate.of(2024, 1, 10);

    // When: Execute queries
    exchangeRateService.getExchangeRates(thbCurrency, startDate1, endDate1);
    exchangeRateService.getExchangeRates(thbCurrency, startDate2, endDate2);
    exchangeRateService.getExchangeRates(thbCurrency, null, null);

    // Then: Verify cache keys exist with expected format
    var cache = cacheManager.getCache("exchangeRates");
    assertThat(cache).isNotNull();

    // Key format: {currency}:{startDate}:{endDate}
    var key1 = thbCurrency.getCurrencyCode() + ":" + startDate1 + ":" + endDate1;
    var key2 = thbCurrency.getCurrencyCode() + ":" + startDate2 + ":" + endDate2;
    var key3 = thbCurrency.getCurrencyCode() + ":null:null";

    assertThat(cache.get(key1)).isNotNull();
    assertThat(cache.get(key2)).isNotNull();
    assertThat(cache.get(key3)).isNotNull();

    // Verify each cache entry contains correct data
    var cachedData1 = this.<ExchangeRateData>getCachedList(key1);
    assertThat(cachedData1).hasSize(5).allMatch(rate -> rate.targetCurrency().equals(thbCurrency));

    var cachedData2 = this.<ExchangeRateData>getCachedList(key2);
    assertThat(cachedData2).hasSize(5).allMatch(rate -> rate.targetCurrency().equals(thbCurrency));
  }

  @Test
  void shouldClearEntireCacheOnImport() {
    // Given: Cache populated with data for multiple currencies
    var thbCurrency = Currency.getInstance(TestConstants.VALID_CURRENCY_THB);
    var eurCurrency = Currency.getInstance(TestConstants.VALID_CURRENCY_EUR);
    var startDate = LocalDate.of(2024, 1, 1);
    var endDate = LocalDate.of(2024, 1, 5);

    // Populate cache for both currencies
    exchangeRateService.getExchangeRates(thbCurrency, startDate, endDate);
    exchangeRateService.getExchangeRates(eurCurrency, startDate, endDate);

    var cache = cacheManager.getCache("exchangeRates");
    var thbKey = thbCurrency.getCurrencyCode() + ":" + startDate + ":" + endDate;
    var eurKey = eurCurrency.getCurrencyCode() + ":" + startDate + ":" + endDate;

    // Verify both entries are cached
    assertThat(cache.get(thbKey)).isNotNull();
    assertThat(cache.get(eurKey)).isNotNull();

    // When: Import new exchange rates (triggers @CacheEvict(allEntries = true))
    // We'll simulate this by adding a new rate and calling the import service
    var newRate =
        ExchangeRateTestBuilder.forSeries(thbSeries)
            .withDate(LocalDate.of(2024, 1, 11))
            .withRate(new BigDecimal("33.0000"))
            .build();
    exchangeRateRepository.save(newRate);

    // Trigger cache eviction by calling a method with @CacheEvict
    // Note: We can't easily test the actual import without mocking FRED,
    // so we'll directly test the eviction behavior
    exchangeRateImportService.importMissingExchangeRates();

    // Then: Cache is cleared for ALL currencies (not just THB)
    assertThat(cache.get(thbKey)).as("THB cache entry should be evicted after import").isNull();
    assertThat(cache.get(eurKey))
        .as("EUR cache entry should be evicted after import (allEntries=true)")
        .isNull();
  }

  @Test
  @SuppressWarnings("checkstyle:VariableDeclarationUsageDistance")
  void shouldIsolateCacheDataBetweenDifferentCurrencies() {
    // Given: Same date range for different currencies
    var thbCurrency = Currency.getInstance(TestConstants.VALID_CURRENCY_THB);
    var eurCurrency = Currency.getInstance(TestConstants.VALID_CURRENCY_EUR);
    var gbpCurrency = Currency.getInstance(TestConstants.VALID_CURRENCY_GBP);
    var startDate = LocalDate.of(2024, 1, 1);
    var endDate = LocalDate.of(2024, 1, 5);

    // When: Query all three currencies
    var thbResults = exchangeRateService.getExchangeRates(thbCurrency, startDate, endDate);
    exchangeRateService.getExchangeRates(eurCurrency, startDate, endDate);
    exchangeRateService.getExchangeRates(gbpCurrency, startDate, endDate);

    // Then: Each currency has separate cache entry
    var cache = cacheManager.getCache("exchangeRates");
    var thbKey = thbCurrency.getCurrencyCode() + ":" + startDate + ":" + endDate;
    var eurKey = eurCurrency.getCurrencyCode() + ":" + startDate + ":" + endDate;
    var gbpKey = gbpCurrency.getCurrencyCode() + ":" + startDate + ":" + endDate;

    assertThat(cache.get(thbKey)).isNotNull();
    assertThat(cache.get(eurKey)).isNotNull();
    assertThat(cache.get(gbpKey)).isNotNull();

    // And: Each cache entry contains correct currency data
    var cachedThb = this.<ExchangeRateData>getCachedList(thbKey);
    assertThat(cachedThb)
        .hasSize(5)
        .allMatch(rate -> rate.targetCurrency().equals(thbCurrency))
        .allMatch(rate -> rate.rate().equals(TestConstants.RATE_THB_USD));

    var cachedEur = this.<ExchangeRateData>getCachedList(eurKey);
    assertThat(cachedEur)
        .hasSize(5)
        .allMatch(rate -> rate.targetCurrency().equals(eurCurrency))
        .allMatch(rate -> rate.rate().equals(TestConstants.RATE_EUR_USD));

    var cachedGbp = this.<ExchangeRateData>getCachedList(gbpKey);
    assertThat(cachedGbp)
        .hasSize(5)
        .allMatch(rate -> rate.targetCurrency().equals(gbpCurrency))
        .allMatch(rate -> rate.rate().equals(TestConstants.RATE_GBP_USD));

    // And: Querying one currency doesn't affect others' cache
    clearInvocations(exchangeRateRepositorySpy);
    var thbResultsAgain = exchangeRateService.getExchangeRates(thbCurrency, startDate, endDate);
    assertThat(thbResultsAgain).isEqualTo(thbResults);

    // Repository should not be called (cache hit)
    verify(exchangeRateRepositorySpy, never()).findAll(any(Specification.class), any(Sort.class));
  }

  /**
   * Retrieves a cached list with type safety.
   *
   * <p>This helper method encapsulates the unchecked cast from {@code Object} to {@code List<T>}
   * that is inherent in Spring's Cache API. The cast is safe in our test context because we control
   * what data is cached.
   *
   * @param <T> the type of elements in the cached list
   * @param cacheKey the key to retrieve from cache
   * @return the cached list, or {@code null} if not found
   */
  @SuppressWarnings("unchecked")
  private <T> List<T> getCachedList(String cacheKey) {
    var cache = cacheManager.getCache("exchangeRates");
    if (cache == null) {
      return null;
    }
    var cachedValue = cache.get(cacheKey);
    return cachedValue != null ? (List<T>) cachedValue.get() : null;
  }

  /**
   * Clears all caches in the cache manager.
   *
   * <p>This is called before each test to ensure cache isolation. Redis container is reused across
   * tests, so explicit clearing is necessary.
   */
  private void clearAllCaches() {
    if (cacheManager instanceof RedisCacheManager redisCacheManager) {
      redisCacheManager
          .getCacheNames()
          .forEach(
              cacheName -> {
                var cache = redisCacheManager.getCache(cacheName);
                if (cache != null) {
                  cache.clear();
                }
              });
    }
  }
}
