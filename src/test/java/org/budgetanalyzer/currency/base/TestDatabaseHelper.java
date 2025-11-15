package org.budgetanalyzer.currency.base;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Centralized utility class for raw SQL operations in integration tests.
 *
 * <p>Provides methods for:
 *
 * <ul>
 *   <li>Database cleanup - Deleting test data in FK-safe order
 *   <li>Database setup - Loading seed data from migration files
 *   <li>Verification queries - Counting records for test assertions
 * </ul>
 */
@Component
public class TestDatabaseHelper {

  private final JdbcTemplate jdbcTemplate;

  public TestDatabaseHelper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Deletes all data from test tables in FK-safe order.
   *
   * <p>Deletion order is critical to avoid FK constraint violations: - exchange_rate has FK to
   * currency_series, so delete it first - event_publication and shedlock have no FK constraints
   */
  public void cleanupAllTables() {
    jdbcTemplate.execute("DELETE FROM exchange_rate");
    jdbcTemplate.execute("DELETE FROM currency_series");
    jdbcTemplate.execute("DELETE FROM event_publication");
    jdbcTemplate.execute("DELETE FROM shedlock");
  }

  /**
   * Restores the 23 default currency series from V6 Flyway migration.
   *
   * <p>Loads the INSERT statement from V6__insert_default_currencies.sql and executes it to restore
   * the seed data used by many integration tests.
   *
   * @throws RuntimeException if the migration file cannot be read
   */
  public void restoreSeedData() {
    try {
      var resource = new ClassPathResource("db/migration/V6__insert_default_currencies.sql");
      var sql = resource.getContentAsString(StandardCharsets.UTF_8);

      // Extract only the INSERT statement (skip ALTER TABLE which already exists in schema)
      var insertStartIndex = sql.indexOf("INSERT INTO currency_series");
      if (insertStartIndex == -1) {
        throw new IllegalStateException(
            "INSERT statement not found in V6__insert_default_currencies.sql");
      }
      var insertStatement = sql.substring(insertStartIndex);

      jdbcTemplate.execute(insertStatement);
    } catch (IOException e) {
      throw new RuntimeException("Failed to load seed data from V6 migration file", e);
    }
  }

  /**
   * Counts the number of completed events in the Spring Modulith event_publication table.
   *
   * <p>Completed events have a non-null completion_date. This is useful for verifying that domain
   * events were successfully processed by event listeners.
   *
   * @return the count of completed events
   */
  public Long countCompletedEvents() {
    String sql = "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NOT NULL";
    return jdbcTemplate.queryForObject(sql, Long.class);
  }
}
