package org.budgetanalyzer.currency.config;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import org.budgetanalyzer.currency.fixture.TestConstants;

/**
 * Replaces the application's UTC system clock with a running UTC clock that starts at the beginning
 * of {@link TestConstants#FIXED_TODAY}.
 *
 * <p>Makes "today", which bounds exchange-rate query end dates, deterministic in integration tests
 * that run for less than 24 hours. The clock keeps ticking, so beans that take the application
 * clock, such as Spring Modulith's event publication registry, still record advancing publication
 * and completion timestamps.
 */
@TestConfiguration
public class FixedClockTestConfig {

  /**
   * Provides the running test clock in preference to the application clock.
   *
   * <p>The offset from the system clock is computed once, when the bean is created.
   *
   * @return UTC clock that started at the beginning of {@link TestConstants#FIXED_TODAY}
   */
  @Bean
  @Primary
  public Clock fixedTodayClock() {
    var systemClock = Clock.systemUTC();
    var fixedTodayStart = TestConstants.FIXED_TODAY.atStartOfDay(ZoneOffset.UTC).toInstant();
    var offset = Duration.between(systemClock.instant(), fixedTodayStart);
    return Clock.offset(systemClock, offset);
  }
}
