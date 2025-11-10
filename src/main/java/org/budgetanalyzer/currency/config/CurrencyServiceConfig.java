package org.budgetanalyzer.currency.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Main configuration class for the Currency Service.
 *
 * <p>Configures application-wide beans and enables component scanning for shared libraries.
 *
 * <p>Note: ObjectMapper is auto-configured by Spring Boot using spring.jackson.* properties in
 * application.yml. JavaTimeModule is automatically registered when jackson-datatype-jsr310 is on
 * the classpath.
 */
@Configuration
@EnableConfigurationProperties(CurrencyServiceProperties.class)
@ComponentScan({"org.budgetanalyzer.core.csv"})
public class CurrencyServiceConfig {}
