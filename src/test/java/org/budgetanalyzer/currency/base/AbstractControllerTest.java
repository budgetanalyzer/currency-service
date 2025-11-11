package org.budgetanalyzer.currency.base;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Base class for controller integration tests with MockMvc and WireMock.
 *
 * <p>Extends {@link AbstractIntegrationTest} and adds:
 *
 * <ul>
 *   <li>MockMvc for HTTP request/response testing
 *   <li>WireMock server for mocking external FRED API
 *   <li>Helper methods for JSON request/response handling
 * </ul>
 *
 * <p>WireMock server lifecycle is managed at class level (singleton pattern) for performance.
 * Server is reset between tests to ensure isolation.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * class CurrencyControllerTest extends AbstractControllerTest {
 *
 *     @Test
 *     void shouldReturnCurrencies() throws Exception {
 *         performGet("/v1/currencies")
 *             .andExpect(status().isOk())
 *             .andExpect(jsonPath("$").isArray());
 *     }
 *
 *     @Test
 *     void shouldMockFredApi() {
 *         stubFredApiSuccess("DEXUSEU", "2024-01-01", "1.10");
 *         // Test code that calls FRED API
 *     }
 * }
 * }</pre>
 *
 * @see MockMvc
 * @see WireMockServer
 */
@AutoConfigureMockMvc
public abstract class AbstractControllerTest extends AbstractIntegrationTest {

  /**
   * WireMock server for mocking FRED API responses.
   *
   * <p>Runs on random port to avoid conflicts. Shared across all test methods in the class for
   * performance, but reset between tests.
   */
  protected static WireMockServer wireMockServer;

  @Autowired protected MockMvc mockMvc;

  /**
   * Starts WireMock server before all tests in the class.
   *
   * <p>Uses dynamic port allocation to avoid port conflicts.
   */
  @BeforeAll
  static void startWireMock() {
    if (wireMockServer == null) {
      wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
      wireMockServer.start();
    }
  }

  /** Stops WireMock server after all tests in the class. */
  @AfterAll
  static void stopWireMock() {
    if (wireMockServer != null && wireMockServer.isRunning()) {
      wireMockServer.stop();
    }
  }

  /**
   * Resets WireMock stubs before each test to ensure isolation.
   *
   * <p>Removes all previously configured stubs and request history.
   */
  @BeforeEach
  void resetWireMock() {
    if (wireMockServer != null) {
      wireMockServer.resetAll();
    }
  }

  /**
   * Configures Spring Boot to use WireMock server for FRED API calls.
   *
   * <p>Overrides {@code currency-service.fred.base-url} property to point to local WireMock server
   * instead of real FRED API.
   *
   * @param registry Spring dynamic property registry
   */
  @DynamicPropertySource
  static void configureWireMock(DynamicPropertyRegistry registry) {
    if (wireMockServer != null) {
      registry.add(
          "currency-service.fred.base-url", () -> "http://localhost:" + wireMockServer.port());
    }
  }

  // ==================== Helper Methods ====================

  /**
   * Performs GET request with MockMvc.
   *
   * @param urlTemplate URL template with optional path variables
   * @param uriVars path variable values
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performGet(String urlTemplate, Object... uriVars) throws Exception {
    return mockMvc.perform(get(urlTemplate, uriVars).contentType(MediaType.APPLICATION_JSON));
  }

  /**
   * Performs POST request with JSON body.
   *
   * @param urlTemplate URL template with optional path variables
   * @param jsonBody JSON request body
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performPost(String urlTemplate, String jsonBody) throws Exception {
    return mockMvc.perform(
        post(urlTemplate).contentType(MediaType.APPLICATION_JSON).content(jsonBody));
  }

  /**
   * Performs PUT request with JSON body.
   *
   * @param urlTemplate URL template with optional path variables
   * @param jsonBody JSON request body
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performPut(String urlTemplate, String jsonBody) throws Exception {
    return mockMvc.perform(
        put(urlTemplate).contentType(MediaType.APPLICATION_JSON).content(jsonBody));
  }

  /**
   * Performs DELETE request.
   *
   * @param urlTemplate URL template with optional path variables
   * @param uriVars path variable values
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performDelete(String urlTemplate, Object... uriVars) throws Exception {
    return mockMvc.perform(delete(urlTemplate, uriVars).contentType(MediaType.APPLICATION_JSON));
  }

  /**
   * Returns the base URL for WireMock server.
   *
   * <p>Useful for constructing full URLs in test assertions.
   *
   * @return WireMock base URL (e.g., "http://localhost:8080")
   */
  protected String getWireMockBaseUrl() {
    return "http://localhost:" + wireMockServer.port();
  }
}
