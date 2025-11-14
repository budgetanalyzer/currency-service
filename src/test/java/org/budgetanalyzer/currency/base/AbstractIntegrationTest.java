package org.budgetanalyzer.currency.base;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.budgetanalyzer.currency.config.TestContainersConfig;

/**
 * Base class for integration tests with TestContainers infrastructure.
 *
 * <p>Provides:
 *
 * <ul>
 *   <li>PostgreSQL container for database operations with Flyway migrations
 *   <li>Redis container for distributed caching
 *   <li>RabbitMQ container for event-driven messaging
 *   <li>Full Spring Boot application context
 * </ul>
 *
 * <p>All test infrastructure is centralized in {@link TestContainersConfig}, which uses Spring Boot
 * 3.1+ {@code @ServiceConnection} for automatic container property binding.
 *
 * <p>Container reuse is enabled via testcontainers.reuse.enable=true system property for faster
 * test execution during development.
 *
 * <p><b>Cache Configuration:</b>
 *
 * <p>By default, cache is <b>disabled</b> for most tests ({@code spring.cache.type=none}). Tests
 * that verify caching behavior should explicitly enable Redis cache via
 * {@code @TestPropertySource(properties = "spring.cache.type=redis")}.
 *
 * <p>This approach ensures:
 *
 * <ul>
 *   <li>Tests only start infrastructure they actually use
 *   <li>Faster execution for tests that don't need caching
 *   <li>Clear signal when cache is required (annotation indicates "this tests caching")
 * </ul>
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * // Most tests: Cache disabled by default
 * class MyIntegrationTest extends AbstractIntegrationTest {
 *     // No special configuration needed
 * }
 *
 * // Tests that verify caching: Explicitly enable Redis
 * @TestPropertySource(properties = "spring.cache.type=redis")
 * class MyCacheTest extends AbstractIntegrationTest {
 *     // Redis container will start and cache will be enabled
 * }
 * }</pre>
 *
 * @see TestContainersConfig
 * @see org.springframework.boot.testcontainers.service.connection.ServiceConnection
 * @see org.testcontainers.junit.jupiter.Testcontainers
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestContainersConfig.class)
public abstract class AbstractIntegrationTest {
  // All container configuration is in TestContainersConfiguration

  @Autowired(required = false)
  private JdbcTemplate jdbcTemplate;

  /**
   * Cleans up all test data before each test and restores seed data.
   *
   * <p>This method deletes all rows from test tables in the correct order to avoid foreign key
   * constraint violations, then re-runs the V6 migration to restore the 23 default currency series.
   * This ensures tests have a clean, consistent starting state with seed data available.
   *
   * <p>Called automatically before each test when test containers are reused across multiple Spring
   * contexts.
   */
  @BeforeEach
  void cleanupDatabase() {
    if (jdbcTemplate != null) {
      // Delete in correct order to avoid FK constraint violations
      // exchange_rate has FK to currency_series, so delete it first
      jdbcTemplate.execute("DELETE FROM exchange_rate");
      jdbcTemplate.execute("DELETE FROM currency_series");
      jdbcTemplate.execute("DELETE FROM event_publication");

      // Restore seed data from V6 migration
      restoreSeedData();
    }
  }

  /**
   * Restores seed data by executing the INSERT statements from V6 migration.
   *
   * <p>This ensures tests have access to the 23 default currency series without duplicating SQL in
   * test code. The seed data matches exactly what exists in production.
   */
  private void restoreSeedData() {
    try {
      var resource = new ClassPathResource("db/migration/V6__insert_default_currencies.sql");
      var sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

      // Extract only the INSERT statement (skip ALTER TABLE since constraint already exists)
      var insertStart = sql.indexOf("INSERT INTO currency_series");
      if (insertStart != -1) {
        var insertStatement = sql.substring(insertStart);
        jdbcTemplate.execute(insertStatement);
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to restore seed data from V6 migration", e);
    }
  }
}
