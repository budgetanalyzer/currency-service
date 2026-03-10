package org.budgetanalyzer.currency.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.TestPropertySource;

import org.budgetanalyzer.currency.base.AbstractControllerTest;
import org.budgetanalyzer.currency.config.CacheConfig;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;

/**
 * Integration tests for cache eviction after exchange rate import.
 *
 * <p>Requires Redis cache to be enabled to verify cache behavior through the API.
 */
@TestPropertySource(properties = "spring.cache.type=redis")
class ExchangeRateImportCacheTest extends AbstractControllerTest {

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @Autowired private CacheManager cacheManager;

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
}
