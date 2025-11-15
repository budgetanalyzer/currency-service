package org.budgetanalyzer.currency.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Utility class for testing ShedLock distributed locking behavior.
 *
 * <p>Provides helper methods for:
 *
 * <ul>
 *   <li>Verifying lock acquisition and release
 *   <li>Querying lock information from database
 *   <li>Simulating lock expiration scenarios
 *   <li>Async waiting for lock state changes
 * </ul>
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * SchedulerTestHelper lockHelper = new SchedulerTestHelper(jdbcTemplate);
 *
 * // Wait for lock acquisition
 * lockHelper.waitForLockAcquisition("exchangeRateImport", Duration.ofSeconds(5));
 *
 * // Verify lock state
 * lockHelper.verifyLockAcquired("exchangeRateImport");
 *
 * // Clean up between tests
 * lockHelper.clearShedLock();
 * }</pre>
 */
public class SchedulerTestHelper {

  private final JdbcTemplate jdbcTemplate;

  public SchedulerTestHelper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Verify lock acquired for given lock name.
   *
   * <p>Checks that:
   *
   * <ul>
   *   <li>Lock exists in shedlock table
   *   <li>lock_until is in the future (lock is active)
   * </ul>
   */
  public void verifyLockAcquired(String lockName) {
    LockInfo lock = getLockInfo(lockName);
    assertThat(lock).isNotNull();
    assertThat(lock.lockUntil()).isAfter(Instant.now());
  }

  /**
   * Verify lock released (lock_until in past).
   *
   * <p>A released lock either:
   *
   * <ul>
   *   <li>Does not exist in the table (row deleted)
   *   <li>Exists but lock_until is in the past (expired)
   * </ul>
   */
  public void verifyLockReleased(String lockName) {
    LockInfo lock = getLockInfo(lockName);
    if (lock != null) {
      assertThat(lock.lockUntil()).isBefore(Instant.now());
    }
  }

  /**
   * Simulate lock expiration by updating lock_until to past.
   *
   * <p>Useful for testing lock expiration scenarios where a stale lock needs to be reclaimed.
   */
  public void simulateLockExpiration(String lockName) {
    jdbcTemplate.update(
        "UPDATE shedlock SET lock_until = ? WHERE name = ?",
        Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
        lockName);
  }

  /**
   * Get lock information from database.
   *
   * @return LockInfo if lock exists, null otherwise
   */
  public LockInfo getLockInfo(String lockName) {
    return jdbcTemplate
        .query(
            "SELECT name, lock_until, locked_at, locked_by FROM shedlock WHERE name = ?",
            (rs, rowNum) ->
                new LockInfo(
                    rs.getString("name"),
                    rs.getTimestamp("lock_until").toInstant(),
                    rs.getTimestamp("locked_at").toInstant(),
                    rs.getString("locked_by")),
            lockName)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /**
   * Insert expired lock for testing.
   *
   * <p>Creates a lock entry with:
   *
   * <ul>
   *   <li>lock_until: 1 hour in the past
   *   <li>locked_at: 2 hours in the past
   *   <li>locked_by: specified value
   * </ul>
   */
  public void insertExpiredLock(String lockName, String lockedBy) {
    Instant now = Instant.now();
    jdbcTemplate.update(
        "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES (?, ?, ?, ?)",
        lockName,
        Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
        Timestamp.from(now.minus(2, ChronoUnit.HOURS)),
        lockedBy);
  }

  /**
   * Clear all locks from shedlock table.
   *
   * <p>Should be called in {@code @BeforeEach} to ensure clean state between tests.
   */
  public void clearShedLock() {
    jdbcTemplate.update("DELETE FROM shedlock");
  }

  /**
   * Wait for lock acquisition with timeout.
   *
   * <p>Uses Awaitility to poll database until lock appears. Fails if timeout is exceeded.
   *
   * @param lockName The name of the lock to wait for
   * @param timeout Maximum time to wait
   */
  public void waitForLockAcquisition(String lockName, Duration timeout) {
    await()
        .atMost(timeout)
        .pollInterval(Duration.ofMillis(100))
        .until(() -> getLockInfo(lockName) != null);
  }

  /**
   * Wait for lock release with timeout.
   *
   * <p>Uses Awaitility to poll database until lock is released (lock_until in past or row deleted).
   *
   * @param lockName The name of the lock to wait for
   * @param timeout Maximum time to wait
   */
  public void waitForLockRelease(String lockName, Duration timeout) {
    await()
        .atMost(timeout)
        .pollInterval(Duration.ofMillis(100))
        .until(
            () -> {
              LockInfo lock = getLockInfo(lockName);
              return lock == null || lock.lockUntil().isBefore(Instant.now());
            });
  }

  /**
   * Record class for lock information.
   *
   * @param name Lock name
   * @param lockUntil Timestamp when lock expires
   * @param lockedAt Timestamp when lock was acquired
   * @param lockedBy Identifier of lock holder (hostname + thread)
   */
  public record LockInfo(String name, Instant lockUntil, Instant lockedAt, String lockedBy) {}
}
