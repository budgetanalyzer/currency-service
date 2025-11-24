package org.budgetanalyzer.currency.config;

import org.springframework.boot.autoconfigure.task.TaskSchedulingProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Configuration for scheduled tasks.
 *
 * <p>This configuration provides a dedicated thread pool for {@code @Scheduled} methods (scheduled
 * background jobs).
 *
 * <p><b>Thread Pool Strategy:</b>
 *
 * <ul>
 *   <li><b>Scheduled tasks:</b> Use dedicated {@code taskScheduler} bean (this class)
 *   <li><b>Async tasks:</b> Use Spring Boot's auto-configured {@code applicationTaskExecutor}
 *       (configured via application.yml)
 * </ul>
 *
 * <p><b>Why Separate Thread Pools?</b>
 *
 * <ul>
 *   <li><b>Clear separation of concerns:</b> Scheduled jobs vs async event processing
 *   <li><b>Independent tuning:</b> Different pool sizes and thread naming for different workloads
 *   <li><b>Works with autoconfiguration:</b> Leverages Spring Boot's {@code
 *       TaskExecutionAutoConfiguration}
 * </ul>
 *
 * <p><b>Configuration:</b> All thread pool settings are configured via {@code
 * spring.task.scheduling} properties in application.yml.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig implements SchedulingConfigurer {

  private final TaskSchedulingProperties taskSchedulingProperties;

  public SchedulingConfig(TaskSchedulingProperties taskSchedulingProperties) {
    this.taskSchedulingProperties = taskSchedulingProperties;
  }

  /**
   * Task scheduler for scheduled background jobs.
   *
   * <p>Executes {@code @Scheduled} methods with dedicated thread pool. All settings (pool size,
   * thread name prefix, shutdown behavior) are configured via {@code spring.task.scheduling}
   * properties in application.yml.
   *
   * @return The configured task scheduler
   */
  @Bean
  @NonNull
  public TaskScheduler taskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();

    var pool = taskSchedulingProperties.getPool();
    scheduler.setPoolSize(pool.getSize());

    var shutdown = taskSchedulingProperties.getShutdown();
    scheduler.setWaitForTasksToCompleteOnShutdown(shutdown.isAwaitTermination());
    if (shutdown.getAwaitTerminationPeriod() != null) {
      scheduler.setAwaitTerminationSeconds((int) shutdown.getAwaitTerminationPeriod().getSeconds());
    }

    scheduler.setThreadNamePrefix(taskSchedulingProperties.getThreadNamePrefix());
    scheduler.initialize();

    return scheduler;
  }

  /**
   * Configures Spring's scheduling infrastructure to use our custom task scheduler.
   *
   * <p>This method is called by Spring during initialization to set up the scheduler for all
   * {@code @Scheduled} methods.
   *
   * @param taskRegistrar The registrar for scheduled tasks
   */
  @Override
  public void configureTasks(@NonNull ScheduledTaskRegistrar taskRegistrar) {
    taskRegistrar.setTaskScheduler(taskScheduler());
  }
}
