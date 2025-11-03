package com.bleurubin.budgetanalyzer.currency.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Main configuration class for the Currency Service.
 *
 * <p>Configures application-wide beans and enables component scanning for shared libraries.
 */
@Configuration
@EnableConfigurationProperties(CurrencyServiceProperties.class)
@ComponentScan({"com.bleurubin.core.csv", "com.bleurubin.service.api"})
public class CurrencyServiceConfig {

  /**
   * Configures the application-wide ObjectMapper with support for Java 8 date/time types.
   *
   * <p>This ObjectMapper is used by: - FredClient for deserializing FRED API responses - Redis
   * cache for serializing/deserializing cached objects - Any other Jackson-based
   * serialization/deserialization
   *
   * <p>Configuration: - Registers JavaTimeModule for LocalDate, LocalDateTime, etc. - Disables
   * timestamp serialization (uses ISO-8601 format instead)
   *
   * @return configured ObjectMapper
   */
  @Bean
  public ObjectMapper objectMapper() {
    var mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    return mapper;
  }
}
