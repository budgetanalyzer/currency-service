package org.budgetanalyzer.currency.base;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.WireMockServer;

import org.budgetanalyzer.currency.config.WireMockConfiguration;

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
 * <p>WireMock server is configured via {@link WireMockConfiguration} which is imported by this
 * class. The server instance is shared across all tests that import the configuration and is reset
 * between tests to ensure isolation.
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
 *         wireMockServer.stubFor(get(urlPathEqualTo("/fred/series/observations"))
 *             .willReturn(okJson("{...}")));
 *         // Test code that calls FRED API
 *     }
 * }
 * }</pre>
 *
 * @see MockMvc
 * @see WireMockServer
 * @see WireMockConfiguration
 */
@AutoConfigureMockMvc
@Import(WireMockConfiguration.class)
public abstract class AbstractControllerTest extends AbstractIntegrationTest {

  /**
   * WireMock server for mocking FRED API responses.
   *
   * <p>Server instance is provided by {@link WireMockConfiguration} and shared across all tests
   * that import the configuration.
   */
  @Autowired protected WireMockServer wireMockServer;

  @Autowired protected MockMvc mockMvc;

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
    var wireMock = WireMockConfiguration.getWireMockServer();
    registry.add(
        "currency-service.exchange-rate-import.fred.base-url",
        () -> "http://localhost:" + wireMock.port());
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
