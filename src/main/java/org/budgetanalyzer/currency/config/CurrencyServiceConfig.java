package org.budgetanalyzer.currency.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Main configuration class for the Currency Service.
 *
 * <p>Configures application-wide beans.
 *
 * <p>Note: Spring Boot auto-configures the Jackson 3 {@code JsonMapper} from spring.jackson.*
 * properties in application.yml. Jackson 3 supports {@code java.time} types natively, so no
 * separate datatype module is required.
 *
 * <p>Note: service-common beans (including CSV parsing) are auto-configured via Spring Boot
 * autoconfiguration mechanism. Explicit @ComponentScan is NOT required.
 */
@Configuration
@EnableConfigurationProperties(CurrencyServiceProperties.class)
public class CurrencyServiceConfig {

  /**
   * Provides the UTC clock used to determine the current date.
   *
   * @return system clock in UTC
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
