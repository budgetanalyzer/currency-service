package org.budgetanalyzer.currency.scheduler;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.TaskScheduler;

/**
 * Test configuration that replaces TaskScheduler with a version that executes scheduled tasks
 * immediately.
 *
 * <p><b>Purpose:</b> Eliminate real time delays in integration tests that involve async retries.
 *
 * <p><b>Problem:</b> The production scheduler uses {@code TaskScheduler.schedule(task, instant)} to
 * schedule retries 1-2 minutes in the future. Integration tests using {@code @SpringBootTest} would
 * need to wait real time for these scheduled tasks to execute, causing tests to hang or timeout.
 *
 * <p><b>Solution:</b> This test configuration provides a TaskScheduler that executes scheduled
 * tasks immediately instead of waiting for the scheduled time. This allows tests to verify retry
 * logic without real time delays.
 *
 * <p><b>Implementation:</b> Uses {@link ImmediateTaskScheduler} which overrides {@code
 * schedule(Runnable, Instant)} to execute tasks immediately using {@code run()} instead of
 * scheduling for future execution.
 *
 * <p><b>Usage:</b> Import this configuration in integration tests that test retry logic:
 *
 * <pre>{@code
 * @SpringBootTest
 * @Import(TestTaskSchedulerConfig.class)
 * class ExchangeRateImportSchedulerIntegrationTest {
 *     // Tests will now execute retries immediately
 * }
 * }</pre>
 *
 * <p><b>Trade-offs:</b>
 *
 * <ul>
 *   <li>Pro: Tests run in seconds instead of minutes
 *   <li>Pro: No Awaitility timeouts or flaky timing issues
 *   <li>Con: Cannot verify exact retry delay timing (test separately at unit level)
 *   <li>Con: Tasks execute synchronously instead of async (acceptable for integration tests)
 * </ul>
 *
 * @see org.springframework.scheduling.TaskScheduler
 * @see ExchangeRateImportScheduler#scheduleRetry(int)
 */
@TestConfiguration
public class TestTaskSchedulerConfig {

  /**
   * Provides a TaskScheduler that executes scheduled tasks immediately.
   *
   * <p>Marked with {@code @Primary} to override the default TaskScheduler bean in the Spring
   * context.
   *
   * @return TaskScheduler that executes tasks immediately
   */
  @Bean
  @Primary
  public TaskScheduler immediateTaskScheduler() {
    return new ImmediateTaskScheduler();
  }
}
