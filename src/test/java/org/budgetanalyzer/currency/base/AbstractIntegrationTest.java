package org.budgetanalyzer.currency.base;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
 * <p>Uses Spring Boot 3.1+ {@code @ServiceConnection} for automatic container property binding,
 * eliminating the need for {@code @DynamicPropertySource}.
 *
 * <p>Container reuse is enabled via testcontainers.reuse.enable=true system property for faster
 * test execution during development.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * @SpringBootTest
 * class MyIntegrationTest extends AbstractIntegrationTest {
 *     // Test methods have access to all containers
 * }
 * }</pre>
 *
 * @see org.springframework.boot.testcontainers.service.connection.ServiceConnection
 * @see org.testcontainers.junit.jupiter.Testcontainers
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  /**
   * PostgreSQL container for database operations.
   *
   * <p>Flyway migrations are automatically applied on startup. Schema version is validated against
   * migration scripts.
   */
  @Container @ServiceConnection
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
  @Container @ServiceConnection
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
  @Container @ServiceConnection
  static RabbitMQContainer rabbitMQContainer =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management-alpine")).withReuse(true);
}
