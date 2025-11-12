package org.budgetanalyzer.currency.base;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import org.budgetanalyzer.currency.config.TestContainersConfiguration;

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
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestContainersConfiguration.class)
public abstract class AbstractRepositoryTest {
  // PostgreSQL container is imported from TestContainersConfiguration
  // This ensures a single container instance is shared across all test classes
}
