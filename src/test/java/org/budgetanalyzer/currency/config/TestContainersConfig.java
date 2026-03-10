package org.budgetanalyzer.currency.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Centralized TestContainers configuration for all integration tests.
 *
 * <p>Provides all infrastructure Docker containers:
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
 * <p><b>Note:</b> This configuration only manages Docker containers via TestContainers. For
 * WireMock server configuration (used for mocking external HTTP APIs), see {@link WireMockConfig}.
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
 * @see WireMockConfig
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {

  /**
   * PostgreSQL container for database operations.
   *
   * <p>Flyway migrations are automatically applied on startup. Schema version is validated against
   * migration scripts.
   */
  static PostgreSQLContainer<?> postgresContainer =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
          .withCommand(
              "postgres", "-c", "max_connections=50") // prevent tests from overflowing hikari
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
}
