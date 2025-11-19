package org.budgetanalyzer.currency.base;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.budgetanalyzer.currency.config.TestContainersConfig;
import org.budgetanalyzer.service.security.test.TestSecurityConfig;

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
 *   <li>Mock JWT decoder for OAuth2 resource server testing
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
 * <p><b>JWT Authentication in Tests:</b>
 *
 * <p>By default, all tests are authenticated with a standard test JWT (subject: "test-user",
 * scopes: "openid profile email"). Tests can customize authentication using {@link JwtTestBuilder}:
 *
 * <pre>{@code
 * // Default authentication (most tests)
 * class MyIntegrationTest extends AbstractIntegrationTest {
 *     // Automatically authenticated as "test-user"
 * }
 *
 * // Custom authentication for authorization testing
 * class MyAuthTest extends AbstractIntegrationTest {
 *     @BeforeEach
 *     void setupCustomAuth() {
 *         Jwt customJwt = JwtTestBuilder.user("admin")
 *             .withScopes("read:rates", "write:rates", "admin:currencies")
 *             .build();
 *         setCustomJwt(customJwt);
 *     }
 * }
 * }</pre>
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * // Most tests: Cache disabled by default, default JWT authentication
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
 * @see JwtTestBuilder
 * @see org.springframework.boot.testcontainers.service.connection.ServiceConnection
 * @see org.testcontainers.junit.jupiter.Testcontainers
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestContainersConfig.class)
public abstract class AbstractIntegrationTest {
  // All container configuration is in TestContainersConfiguration
  // JWT decoder mock configuration is in TestSecurityConfig (imported by TestContainersConfig)

  @Autowired protected TestDatabaseHelper testDatabaseHelper;

  /**
   * Sets a custom JWT for the current test.
   *
   * <p>The custom JWT will be used for all authenticated requests in the test. Automatically
   * cleared before the next test.
   *
   * <p><b>Example:</b>
   *
   * <pre>{@code
   * @BeforeEach
   * void setupCustomAuth() {
   *     Jwt adminJwt = JwtTestBuilder.admin()
   *         .withScopes("read:rates", "write:rates", "admin:currencies")
   *         .build();
   *     setCustomJwt(adminJwt);
   * }
   * }</pre>
   *
   * @param jwt the custom JWT to use
   */
  protected void setCustomJwt(Jwt jwt) {
    TestSecurityConfig.CUSTOM_JWT.set(jwt);
  }

  /**
   * Clears any custom JWT and resets to default authentication.
   *
   * <p>Called automatically before each test. Can also be called manually within a test to reset
   * authentication.
   */
  protected void clearCustomJwt() {
    TestSecurityConfig.CUSTOM_JWT.remove();
  }

  /**
   * Cleans up all test data before each test and restores seed data.
   *
   * <p>This method deletes all rows from test tables in the correct order to avoid foreign key
   * constraint violations, then re-runs the V6 migration to restore the 23 default currency series.
   * This ensures tests have a clean, consistent starting state with seed data available.
   *
   * <p>Also clears any custom JWT set in the previous test to ensure test isolation.
   *
   * <p>Called automatically before each test when test containers are reused across multiple Spring
   * contexts.
   */
  @BeforeEach
  protected void resetSeedDatabase() {
    testDatabaseHelper.cleanupAllTables();
    testDatabaseHelper.restoreSeedData();
    clearCustomJwt(); // Reset to default authentication
  }
}
