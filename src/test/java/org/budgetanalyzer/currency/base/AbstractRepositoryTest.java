package org.budgetanalyzer.currency.base;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for repository integration tests with PostgreSQL TestContainer.
 *
 * <p>Uses {@code @DataJpaTest} for lightweight repository testing:
 *
 * <ul>
 *   <li>Only loads JPA components (repositories, entities, EntityManager)
 *   <li>Disables full application context (@Service, @Controller, @Component)
 *   <li>Configures transactional test execution with automatic rollback
 *   <li>Faster test execution compared to {@code @SpringBootTest}
 * </ul>
 *
 * <p>PostgreSQL container ensures tests run against real database with:
 *
 * <ul>
 *   <li>Flyway migrations applied automatically
 *   <li>Database constraints validated (unique, foreign keys, etc.)
 *   <li>Native SQL query behavior (not in-memory H2)
 * </ul>
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * class CurrencySeriesRepositoryTest extends AbstractRepositoryTest {
 *
 *     @Autowired
 *     private CurrencySeriesRepository repository;
 *
 *     @Test
 *     void shouldFindByCurrencyCode() {
 *         // Arrange
 *         var series = new CurrencySeries();
 *         series.setCurrencyCode("EUR");
 *         repository.save(series);
 *
 *         // Act
 *         var result = repository.findByCurrencyCode("EUR");
 *
 *         // Assert
 *         assertThat(result).isPresent();
 *     }
 * }
 * }</pre>
 *
 * <p><b>Performance Note:</b> Repository tests are significantly faster than full integration tests
 * because they don't load service layer, controllers, messaging, caching, or scheduling
 * infrastructure.
 *
 * @see org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
 * @see org.springframework.transaction.annotation.Transactional
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class AbstractRepositoryTest {

  /**
   * PostgreSQL container for repository tests.
   *
   * <p>Shared across all repository test classes for performance. Container reuse is enabled to
   * avoid repeated container startup.
   *
   * <p>Flyway migrations are automatically applied by Spring Boot's {@code FlywayAutoConfiguration}
   * on test context startup.
   */
  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgresContainer =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
          .withDatabaseName("currency_test")
          .withUsername("test")
          .withPassword("test")
          .withReuse(true);
}
