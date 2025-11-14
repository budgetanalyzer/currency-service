package org.budgetanalyzer.currency.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Centralized WireMock configuration for tests that need to mock external HTTP APIs.
 *
 * <p>Provides a WireMock server instance for mocking external HTTP calls, primarily used for FRED
 * API integration testing.
 *
 * <p>Server lifecycle:
 *
 * <ul>
 *   <li>Started in static initializer to ensure availability before Spring context loads
 *   <li>Runs on dynamic port to avoid conflicts
 *   <li>Stopped automatically via bean {@code destroyMethod}
 *   <li>Shared across all tests that import this configuration
 * </ul>
 *
 * <p>Tests should use {@code @DynamicPropertySource} to override service URLs to point to WireMock,
 * and reset stubs in {@code @BeforeEach} to ensure test isolation.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * @SpringBootTest
 * @Import(WireMockConfiguration.class)
 * class MyIntegrationTest {
 *
 *     @Autowired
 *     private WireMockServer wireMockServer;
 *
 *     @DynamicPropertySource
 *     static void configureWireMock(DynamicPropertyRegistry registry) {
 *         registry.add("my-service.base-url",
 *             () -> "http://localhost:" + getWireMockServer().port());
 *     }
 *
 *     @BeforeEach
 *     void setUp() {
 *         wireMockServer.resetAll();
 *     }
 *
 *     @Test
 *     void shouldMockExternalApi() {
 *         wireMockServer.stubFor(get("/api/data")
 *             .willReturn(okJson("{\"result\": \"success\"}")));
 *         // Test code that calls external API
 *     }
 * }
 * }</pre>
 *
 * <p><b>Design Pattern:</b> Follows same pattern as {@link TestContainersConfig} - separate
 * configuration class for test infrastructure, opt-in via {@code @Import}, managed lifecycle via
 * Spring beans.
 *
 * @see com.github.tomakehurst.wiremock.WireMockServer
 * @see TestContainersConfig
 */
@TestConfiguration(proxyBeanMethods = false)
public class WireMockConfig {

  /**
   * WireMock server for mocking external HTTP APIs.
   *
   * <p>Initialized statically to ensure it's available before Spring context loads and before
   * {@code @DynamicPropertySource} is evaluated in test classes.
   *
   * <p>Server is started immediately and runs on a random available port.
   */
  private static WireMockServer wireMockServer;

  static {
    // Start WireMock server in static initializer to ensure it's available
    // before @DynamicPropertySource is evaluated in test classes
    wireMockServer =
        new WireMockServer(
            com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
    wireMockServer.start();
  }

  /**
   * Returns the WireMock server instance as a Spring bean.
   *
   * <p>Server is started in static initializer and stopped on application context shutdown via
   * {@code destroyMethod}.
   *
   * @return WireMock server instance
   */
  @Bean(destroyMethod = "stop")
  WireMockServer wireMockServer() {
    return wireMockServer;
  }

  /**
   * Returns the static WireMock server instance for use in {@code @DynamicPropertySource}.
   *
   * <p>This method provides access to the WireMock server before the Spring context is fully
   * initialized, which is required for {@code @DynamicPropertySource} to configure properties that
   * depend on the WireMock port.
   *
   * @return WireMock server instance
   */
  public static WireMockServer getWireMockServer() {
    return wireMockServer;
  }
}
