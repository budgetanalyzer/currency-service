package org.budgetanalyzer.currency.base;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

import org.budgetanalyzer.currency.config.WireMockConfig;

@Import(WireMockConfig.class)
public abstract class AbstractWireMockTest extends AbstractIntegrationTest {

  /**
   * WireMock server for mocking FRED API responses.
   *
   * <p>Server instance is provided by {@link WireMockConfig} and shared across all tests that
   * import the configuration.
   */
  @Autowired protected WireMockServer wireMockServer;

  /**
   * Configures Spring Boot to use WireMock server for FRED API calls.
   *
   * <p>Overrides {@code currency-service.fred.base-url} property to point to local WireMock server
   * instead of real FRED API. Must be at the class level (not in nested @TestConfiguration) for
   * Spring to properly process it.
   *
   * @param registry Spring dynamic property registry
   */
  @DynamicPropertySource
  static void configureWireMockProperties(DynamicPropertyRegistry registry) {
    var wireMock = WireMockConfig.getWireMockServer();
    registry.add(
        "currency-service.exchange-rate-import.fred.base-url",
        () -> "http://localhost:" + wireMock.port());
  }

  /** Cleanup database and reset WireMock stubs before each test. */
  @BeforeEach
  protected void resetDatabaseAndWireMock() {
    // Clear database tables
    testDatabaseHelper.cleanupAllTables();

    // Reset all WireMock stubs
    wireMockServer.resetAll();
  }
}
