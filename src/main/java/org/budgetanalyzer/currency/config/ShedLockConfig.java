package org.budgetanalyzer.currency.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * ShedLock configuration for distributed scheduled task coordination.
 *
 * <p>ShedLock ensures that scheduled tasks run only once across multiple application instances in a
 * distributed environment. It uses the PostgreSQL database as the lock provider for coordination.
 *
 * <p><strong>How it works:</strong>
 *
 * <ul>
 *   <li>When a scheduled task is triggered, ShedLock attempts to acquire a distributed lock by
 *       inserting/updating a row in the {@code shedlock} table
 *   <li>If the lock is acquired (successful insert/update), the task executes on that instance
 *   <li>If the lock is already held by another instance, the task is skipped
 *   <li>Locks are time-based and automatically released after the configured duration
 * </ul>
 *
 * <p><strong>Lock configuration:</strong>
 *
 * <ul>
 *   <li><code>lockAtMostFor</code>: Maximum duration the lock can be held (prevents deadlocks if
 *       instance crashes)
 *   <li><code>lockAtLeastFor</code>: Minimum duration the lock must be held (prevents too frequent
 *       executions)
 * </ul>
 *
 * <p><strong>Why JDBC/PostgreSQL?</strong>
 *
 * <ul>
 *   <li>Already part of the infrastructure (each microservice has its own database)
 *   <li>Service independence - no shared infrastructure dependencies
 *   <li>Better failure isolation - database outage only affects one service
 *   <li>Simpler operations - one less system to monitor (no Redis for locking)
 * </ul>
 *
 * <p><strong>Database table:</strong>
 *
 * <p>ShedLock requires a table named {@code shedlock} with the following structure:
 *
 * <pre>
 * CREATE TABLE shedlock (
 *   name VARCHAR(64) PRIMARY KEY,
 *   lock_until TIMESTAMP NOT NULL,
 *   locked_at TIMESTAMP NOT NULL,
 *   locked_by VARCHAR(255) NOT NULL
 * );
 * </pre>
 *
 * <p>This table is created automatically via Flyway migration.
 *
 * @see net.javacrumbs.shedlock.spring.annotation.SchedulerLock
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class ShedLockConfig {

  /**
   * Creates a JDBC-based lock provider for ShedLock.
   *
   * <p>The lock provider uses the application's PostgreSQL database to store lock information in
   * the {@code shedlock} table.
   *
   * <p><strong>Lock storage:</strong>
   *
   * <ul>
   *   <li>Lock name: Stored in {@code name} column (e.g., "exchangeRateImport")
   *   <li>Lock expiration: Stored in {@code lock_until} column
   *   <li>Lock holder: Stored in {@code locked_by} column (hostname + thread)
   *   <li>Lock timestamp: Stored in {@code locked_at} column
   * </ul>
   *
   * @param dataSource The application's data source (auto-configured by Spring)
   * @return LockProvider instance for ShedLock
   */
  @Bean
  public LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(dataSource);
  }
}
