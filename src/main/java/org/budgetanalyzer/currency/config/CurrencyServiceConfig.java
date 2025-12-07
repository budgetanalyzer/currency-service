package org.budgetanalyzer.currency.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Main configuration class for the Currency Service.
 *
 * <p>Configures application-wide beans.
 *
 * <p>Note: ObjectMapper is auto-configured by Spring Boot using spring.jackson.* properties in
 * application.yml. JavaTimeModule is automatically registered when jackson-datatype-jsr310 is on
 * the classpath.
 *
 * <p>Note: service-common beans (including CSV parsing) are auto-configured via Spring Boot
 * autoconfiguration mechanism. Explicit @ComponentScan is NOT required.
 */
@Configuration
@EnableConfigurationProperties(CurrencyServiceProperties.class)
public class CurrencyServiceConfig {}
