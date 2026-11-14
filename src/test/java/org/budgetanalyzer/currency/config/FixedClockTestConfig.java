package org.budgetanalyzer.currency.config;

import java.time.Clock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import org.budgetanalyzer.currency.fixture.TestConstants;

/**
 * Replaces the application's UTC system clock with {@link TestConstants#FIXED_CLOCK}.
 *
 * <p>Makes "today", which bounds exchange-rate query end dates, deterministic in integration tests.
 */
@TestConfiguration
public class FixedClockTestConfig {

  /**
   * Provides the fixed test clock in preference to the application clock.
   *
   * @return clock fixed at the start of {@link TestConstants#FIXED_TODAY} in UTC
   */
  @Bean
  @Primary
  public Clock fixedClock() {
    return TestConstants.FIXED_CLOCK;
  }
}
