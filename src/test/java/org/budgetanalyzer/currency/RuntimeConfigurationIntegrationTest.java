package org.budgetanalyzer.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.service.config.HttpLoggingProperties;

/**
 * Verifies the deployed runtime configuration: safe logging defaults and health probe groups.
 *
 * <p>Loads the main {@code application.yml} with the {@code kubernetes} profile, as the cluster
 * deployment does. One sequential test checks logging, then probe membership, then stops Redis and
 * PostgreSQL in turn. The test owns its containers, rather than using the shared reusable ones,
 * because it stops them.
 */
@SpringBootTest(
    properties = {
      // src/test/resources/application.yml shadows the main one, so load the shipped file.
      "spring.config.additional-location=file:./src/main/resources/",
      "currency-service.exchange-rate-import.fred.api-key=" + TestConstants.FRED_TEST_API_KEY,
      "currency-service.exchange-rate-import.import-on-startup=false",
      "management.endpoint.health.show-components=always"
    })
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("kubernetes")
@DirtiesContext
class RuntimeConfigurationIntegrationTest {

  @Container @ServiceConnection
  private static final PostgreSQLContainer POSTGRESQL_CONTAINER =
      new PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"))
          .withDatabaseName("currency_runtime_test")
          .withUsername("test")
          .withPassword("test");

  @Container
  @ServiceConnection(name = "redis")
  private static final GenericContainer<?> REDIS_CONTAINER =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @Container @ServiceConnection
  private static final RabbitMQContainer RABBIT_MQ_CONTAINER =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management-alpine"));

  @Autowired private Environment environment;
  @Autowired private HttpLoggingProperties httpLoggingProperties;
  @Autowired private MockMvc mockMvc;

  @Test
  void kubernetesRuntimeKeepsLoggingSafeAndReadinessDependentOnDataStores() throws Exception {
    assertThat(environment.getProperty("logging.level.org.budgetanalyzer")).isEqualTo("INFO");
    assertThat(environment.getProperty("logging.level.budgetanalyzer.audit")).isEqualTo("INFO");
    assertThat(httpLoggingProperties.isIncludeRequestBody()).isFalse();
    assertThat(httpLoggingProperties.isIncludeResponseBody()).isFalse();

    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.readinessState.status").value("UP"))
        .andExpect(jsonPath("$.components.db.status").value("UP"))
        .andExpect(jsonPath("$.components.redis.status").value("UP"))
        .andExpect(jsonPath("$.components.rabbit").doesNotExist());
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.db").doesNotExist())
        .andExpect(jsonPath("$.components.redis").doesNotExist());

    REDIS_CONTAINER.stop();

    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.components.redis.status").value("DOWN"))
        .andExpect(jsonPath("$.components.db.status").value("UP"));
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));

    // Redis stays stopped, so only the named db component is asserted.
    POSTGRESQL_CONTAINER.stop();

    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.components.db.status").value("DOWN"));
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
