package org.budgetanalyzer.currency.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Centralized TestContainers configuration for all integration tests.
 *
 * <p>Provides all infrastructure containers:
 *
 * <ul>
 *   <li>PostgreSQL container for database operations with Flyway migrations
 *   <li>Redis container for distributed caching
 *   <li>RabbitMQ container for event-driven messaging
 * </ul>
 *
 * <p>Uses Spring Boot 3.1+ {@code @ServiceConnection} for automatic container property binding,
 * eliminating the need for {@code @DynamicPropertySource} for TestContainers.
 *
 * <p>Container reuse is enabled via testcontainers.reuse.enable=true system property for faster
 * test execution during development.
 *
 * <p>WireMock server for mocking external FRED API is configured in {@link
 * org.budgetanalyzer.currency.base.AbstractControllerTest} since only controller tests require it.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * @SpringBootTest
 * @Import(TestContainersConfiguration.class)
 * class MyIntegrationTest {
 *     // Test methods have access to all containers
 * }
 * }</pre>
 *
 * @see org.springframework.boot.testcontainers.service.connection.ServiceConnection
 * @see org.testcontainers.junit.jupiter.Testcontainers
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfiguration {

  /**
   * PostgreSQL container for database operations.
   *
   * <p>Flyway migrations are automatically applied on startup. Schema version is validated against
   * migration scripts.
   */
  static PostgreSQLContainer<?> postgresContainer =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
          .withDatabaseName("currency_test")
          .withUsername("test")
          .withPassword("test")
          .withReuse(true);

  /**
   * Redis container for distributed caching.
   *
   * <p>Used by {@code ExchangeRateService} for caching exchange rate queries. Cache keys follow
   * format: {@code {targetCurrency}:{startDate}:{endDate}}.
   */
  static GenericContainer<?> redisContainer =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withReuse(true);

  /**
   * RabbitMQ container for event-driven messaging.
   *
   * <p>Used by Spring Modulith transactional outbox pattern for guaranteed message delivery. Events
   * are published to {@code currency.created} topic.
   */
  static RabbitMQContainer rabbitMQContainer =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management-alpine")).withReuse(true);

  /**
   * WireMock server for mocking FRED API responses.
   *
   * <p>Runs on random port to avoid conflicts. Shared across all test classes for performance.
   * Initialized statically to ensure it's available before Spring context loads.
   *
   * <p>Bean configuration is in {@link org.budgetanalyzer.currency.base.AbstractControllerTest}
   * since only controller tests require WireMock.
   *
   * @return WireMock server instance (may be null if not yet initialized)
   */
  static WireMockServer wireMockServer;

  static {
    // Start WireMock server in static initializer to ensure it's available
    // before @DynamicPropertySource is evaluated in AbstractControllerTest
    wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wireMockServer.start();
  }

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return postgresContainer;
  }

  @Bean
  @ServiceConnection(name = "redis")
  GenericContainer<?> redisContainer() {
    return redisContainer;
  }

  @Bean
  @ServiceConnection
  RabbitMQContainer rabbitMQContainer() {
    return rabbitMQContainer;
  }

  /**
   * Returns the WireMock server instance for controller tests.
   *
   * <p>Used by {@link org.budgetanalyzer.currency.base.AbstractControllerTest} to access the shared
   * WireMock server.
   *
   * @return WireMock server instance
   */
  public static WireMockServer getWireMockServer() {
    return wireMockServer;
  }
}
