package org.budgetanalyzer.currency.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfiguration {

  static PostgreSQLContainer<?> postgresContainer =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
          .withDatabaseName("currency_test")
          .withUsername("test")
          .withPassword("test")
          .withReuse(true);

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return postgresContainer;
  }
}
