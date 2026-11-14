package org.budgetanalyzer.currency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;

import org.budgetanalyzer.currency.base.AbstractIntegrationTest;

/**
 * Smoke test to verify Spring Boot application context loads successfully with all TestContainers
 * infrastructure (PostgreSQL, Redis, RabbitMQ).
 *
 * <p>This test validates:
 *
 * <ul>
 *   <li>All Spring beans can be instantiated
 *   <li>Database migrations (Flyway) execute successfully
 *   <li>TestContainers start and connect properly
 *   <li>Application properties are correctly configured
 *   <li>The default test cache type {@code none} keeps Spring Boot's no-op cache manager
 * </ul>
 */
@DisplayName("Application Smoketest")
class CurrencyServiceApplicationIntegrationTest extends AbstractIntegrationTest {

  @Autowired private CacheManager cacheManager;

  @Test
  void contextLoads() {
    // If this test passes, Spring Boot context loaded successfully
    // with all infrastructure components (database, cache, messaging)
  }

  @Test
  void shouldUseNoOpCacheManagerWhenCacheTypeIsNone() {
    assertThat(cacheManager).isInstanceOf(NoOpCacheManager.class);
  }
}
