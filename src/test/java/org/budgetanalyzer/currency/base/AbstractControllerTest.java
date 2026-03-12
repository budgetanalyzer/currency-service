package org.budgetanalyzer.currency.base;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.WireMockServer;

import org.budgetanalyzer.currency.config.WireMockConfig;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;

/**
 * Base class for controller integration tests with MockMvc and WireMock.
 *
 * <p>Extends {@link AbstractIntegrationTest} and adds:
 *
 * <ul>
 *   <li>MockMvc for HTTP request/response testing
 *   <li>WireMock server for mocking external FRED API
 *   <li>Helper methods for JSON request/response handling with claims-header authentication
 * </ul>
 *
 * <p>WireMock server is configured via {@link WireMockConfig} which is imported by this class. The
 * server instance is shared across all tests that import the configuration and is reset between
 * tests to ensure isolation.
 *
 * <p><b>Claims-Header Authentication:</b>
 *
 * <p>All helper methods ({@code performGet}, {@code performPost}, etc.) automatically add
 * claims-header authentication using the configured {@code ClaimsHeaderTestBuilder}. By default,
 * the builder authenticates as a default test user. Tests can customize authentication per-test or
 * per-request:
 *
 * <pre>{@code
 * // Per-test authentication (affects all requests via default helpers)
 * @BeforeEach
 * void setupAdmin() {
 *     setTestClaims(ClaimsHeaderTestBuilder.admin());
 * }
 *
 * @Test
 * void adminCanDeleteCurrency() throws Exception {
 *     performDelete("/v1/currencies/{id}", 1)
 *         .andExpect(status().isNoContent());
 * }
 *
 * // Per-request authentication (one-time override)
 * @Test
 * void testDifferentUsers() throws Exception {
 *     // Regular user (uses default testClaims)
 *     performGet("/v1/rates")
 *         .andExpect(status().isOk());
 *
 *     // Admin user for one request
 *     performGetWithClaims("/v1/admin/rates", ClaimsHeaderTestBuilder.admin())
 *         .andExpect(status().isOk());
 * }
 * }</pre>
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * class CurrencyControllerTest extends AbstractControllerTest {
 *
 *     @Test
 *     void shouldReturnCurrencies() throws Exception {
 *         // Automatically authenticated as default test user
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
 * @see WireMockConfig
 * @see ClaimsHeaderTestBuilder
 */
@AutoConfigureMockMvc
public abstract class AbstractControllerTest extends AbstractWireMockTest {

  @Autowired protected MockMvc mockMvc;

  private ClaimsHeaderTestBuilder testClaims = ClaimsHeaderTestBuilder.defaultUser();

  // ==================== Test Claims Configuration ====================

  /**
   * Sets the claims builder used by default helper methods.
   *
   * <p>Call this in {@code @BeforeEach} to set authentication for all requests in the test.
   *
   * @param claims the claims builder to use
   */
  protected void setTestClaims(ClaimsHeaderTestBuilder claims) {
    this.testClaims = claims;
  }

  /**
   * Resets the claims builder to the default test user.
   *
   * <p>Called automatically if needed. Can also be called manually within a test.
   */
  protected void resetTestClaims() {
    this.testClaims = ClaimsHeaderTestBuilder.defaultUser();
  }

  // ==================== Authenticated Request Methods ====================

  /**
   * Performs GET request with default claims-header authentication.
   *
   * @param urlTemplate URL template with optional path variables
   * @param uriVars path variable values
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performGet(String urlTemplate, Object... uriVars) throws Exception {
    return mockMvc.perform(
        get(urlTemplate, uriVars).contentType(MediaType.APPLICATION_JSON).with(testClaims));
  }

  /**
   * Performs GET request with custom claims-header authentication (one-time override).
   *
   * <p>Use this when you need different user/permissions for a single request. For per-test
   * authentication, use {@link #setTestClaims(ClaimsHeaderTestBuilder)} in {@code @BeforeEach}.
   *
   * @param urlTemplate URL template with optional path variables
   * @param claims custom claims for this request
   * @param uriVars path variable values
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performGetWithClaims(
      String urlTemplate, ClaimsHeaderTestBuilder claims, Object... uriVars) throws Exception {
    return mockMvc.perform(
        get(urlTemplate, uriVars).contentType(MediaType.APPLICATION_JSON).with(claims));
  }

  /**
   * Performs POST request with JSON body and default claims-header authentication.
   *
   * @param urlTemplate URL template with optional path variables
   * @param jsonBody JSON request body
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performPost(String urlTemplate, String jsonBody) throws Exception {
    return mockMvc.perform(
        post(urlTemplate)
            .contentType(MediaType.APPLICATION_JSON)
            .content(jsonBody)
            .with(testClaims));
  }

  /**
   * Performs POST request with custom claims-header authentication (one-time override).
   *
   * @param urlTemplate URL template with optional path variables
   * @param jsonBody JSON request body
   * @param claims custom claims for this request
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performPostWithClaims(
      String urlTemplate, String jsonBody, ClaimsHeaderTestBuilder claims) throws Exception {
    return mockMvc.perform(
        post(urlTemplate).contentType(MediaType.APPLICATION_JSON).content(jsonBody).with(claims));
  }

  /**
   * Performs PUT request with JSON body and default claims-header authentication.
   *
   * @param urlTemplate URL template with optional path variables
   * @param jsonBody JSON request body
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performPut(String urlTemplate, String jsonBody) throws Exception {
    return mockMvc.perform(
        put(urlTemplate)
            .contentType(MediaType.APPLICATION_JSON)
            .content(jsonBody)
            .with(testClaims));
  }

  /**
   * Performs PUT request with custom claims-header authentication (one-time override).
   *
   * @param urlTemplate URL template with optional path variables
   * @param jsonBody JSON request body
   * @param claims custom claims for this request
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performPutWithClaims(
      String urlTemplate, String jsonBody, ClaimsHeaderTestBuilder claims) throws Exception {
    return mockMvc.perform(
        put(urlTemplate).contentType(MediaType.APPLICATION_JSON).content(jsonBody).with(claims));
  }

  /**
   * Performs DELETE request with default claims-header authentication.
   *
   * @param urlTemplate URL template with optional path variables
   * @param uriVars path variable values
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performDelete(String urlTemplate, Object... uriVars) throws Exception {
    return mockMvc.perform(
        delete(urlTemplate, uriVars).contentType(MediaType.APPLICATION_JSON).with(testClaims));
  }

  /**
   * Performs DELETE request with custom claims-header authentication (one-time override).
   *
   * @param urlTemplate URL template with optional path variables
   * @param claims custom claims for this request
   * @param uriVars path variable values
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performDeleteWithClaims(
      String urlTemplate, ClaimsHeaderTestBuilder claims, Object... uriVars) throws Exception {
    return mockMvc.perform(
        delete(urlTemplate, uriVars).contentType(MediaType.APPLICATION_JSON).with(claims));
  }

  // ==================== Unauthenticated Request Methods ====================

  /**
   * Performs GET request without authentication (unauthenticated).
   *
   * <p>Use this to verify that unauthenticated requests are rejected with 401 Unauthorized.
   *
   * @param urlTemplate URL template with optional path variables
   * @param uriVars path variable values
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performGetUnauthenticated(String urlTemplate, Object... uriVars)
      throws Exception {
    return mockMvc.perform(get(urlTemplate, uriVars).contentType(MediaType.APPLICATION_JSON));
  }

  /**
   * Performs POST request without authentication (unauthenticated).
   *
   * <p>Use this to verify that unauthenticated requests are rejected with 401 Unauthorized.
   *
   * @param urlTemplate URL template with optional path variables
   * @param jsonBody JSON request body
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performPostUnauthenticated(String urlTemplate, String jsonBody)
      throws Exception {
    return mockMvc.perform(
        post(urlTemplate).contentType(MediaType.APPLICATION_JSON).content(jsonBody));
  }

  /**
   * Performs PUT request without authentication (unauthenticated).
   *
   * <p>Use this to verify that unauthenticated requests are rejected with 401 Unauthorized.
   *
   * @param urlTemplate URL template with optional path variables
   * @param jsonBody JSON request body
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performPutUnauthenticated(String urlTemplate, String jsonBody)
      throws Exception {
    return mockMvc.perform(
        put(urlTemplate).contentType(MediaType.APPLICATION_JSON).content(jsonBody));
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
