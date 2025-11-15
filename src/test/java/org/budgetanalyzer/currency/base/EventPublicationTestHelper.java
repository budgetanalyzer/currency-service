package org.budgetanalyzer.currency.base;

import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Test helper for Spring Modulith event publication table queries.
 *
 * <p>Isolates integration tests from direct SQL coupling to the event_publication table schema. If
 * Spring Modulith changes the schema in future versions, only this helper needs to be updated.
 *
 * <p>This is a Spring component that can be autowired in integration tests.
 *
 * @see org.springframework.modulith.events.EventPublication
 */
@Component
public class EventPublicationTestHelper {

  private final JdbcTemplate jdbcTemplate;

  public EventPublicationTestHelper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Clears all events from the event_publication table. Useful for test isolation in @BeforeEach
   * methods.
   */
  public void clearAllEvents() {
    jdbcTemplate.execute("DELETE FROM event_publication");
  }

  /**
   * Counts events by type pattern.
   *
   * @param eventTypePattern SQL LIKE pattern, e.g., "%CurrencyCreatedEvent"
   * @return number of matching events
   */
  public long countEventsByType(String eventTypePattern) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE ?",
        Long.class,
        eventTypePattern);
  }

  /**
   * Counts completed events (those with completion_date set).
   *
   * @return number of completed events
   */
  public long countCompletedEvents() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NOT NULL", Long.class);
  }

  /**
   * Retrieves the payload of the most recent event.
   *
   * @return serialized event JSON, or empty if no events exist
   */
  public Optional<String> getLatestEventPayload() {
    try {
      String payload =
          jdbcTemplate.queryForObject(
              "SELECT serialized_event FROM event_publication "
                  + "ORDER BY publication_date DESC LIMIT 1",
              String.class);
      return Optional.ofNullable(payload);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /**
   * Total count of all events in the table.
   *
   * @return total event count
   */
  public long countAllEvents() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event_publication", Long.class);
  }
}
