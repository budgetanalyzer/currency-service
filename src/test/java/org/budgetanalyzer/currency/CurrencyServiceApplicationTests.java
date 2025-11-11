package org.budgetanalyzer.currency;

import org.junit.jupiter.api.Test;

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
 * </ul>
 */
class CurrencyServiceApplicationTests extends AbstractIntegrationTest {

  @Test
  void contextLoads() {
    // If this test passes, Spring Boot context loaded successfully
    // with all infrastructure components (database, cache, messaging)
  }
}
