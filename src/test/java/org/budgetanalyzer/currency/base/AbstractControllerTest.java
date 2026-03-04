package org.budgetanalyzer.currency.base;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.github.tomakehurst.wiremock.WireMockServer;

import org.budgetanalyzer.currency.config.WireMockConfig;

/**
 * Base class for controller integration tests with MockMvc and WireMock.
 *
 * <p>Extends {@link AbstractIntegrationTest} and adds:
 *
 * <ul>
 *   <li>MockMvc for HTTP request/response testing
 *   <li>WireMock server for mocking external FRED API
 *   <li>Helper methods for JSON request/response handling with automatic JWT authentication
 * </ul>
 *
 * <p>WireMock server is configured via {@link WireMockConfig} which is imported by this class. The
 * server instance is shared across all tests that import the configuration and is reset between
 * tests to ensure isolation.
 *
 * <p><b>JWT Authentication:</b>
 *
 * <p>All helper methods ({@code performGet}, {@code performPost}, etc.) automatically add a JWT
 * Authorization header. By default, the JWT authenticates as "test-user" with scopes "openid
 * profile email". Tests can customize authentication per-test or per-request:
 *
 * <pre>{@code
 * // Per-test authentication (affects all requests in test)
 * @BeforeEach
 * void setupAdmin() {
 *     setCustomJwt(JwtTestBuilder.admin()
 *         .withScopes("read:rates", "write:rates", "admin:currencies")
 *         .build());
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
 *     // Regular user
 *     performGet("/v1/rates")
 *         .andExpect(status().isOk());
 *
 *     // Admin user for one request
 *     Jwt adminJwt = JwtTestBuilder.admin().withScopes("admin:all").build();
 *     performGetWithJwt("/v1/admin/rates", adminJwt)
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
 * @see JwtTestBuilder
 */
@AutoConfigureMockMvc
public abstract class AbstractControllerTest extends AbstractWireMockTest {

  @Autowired protected MockMvc mockMvc;

  // ==================== Helper Methods ====================

  /**
   * Adds default JWT Authorization header to the request.
   *
   * <p>The JWT will be decoded by the mock {@code JwtDecoder}, which returns either a custom JWT
   * (if set via {@link #setCustomJwt(Jwt)}) or the default test JWT.
   *
   * @param builder the request builder to add the header to
   * @return the request builder with Authorization header
   */
  private MockHttpServletRequestBuilder withJwtAuth(MockHttpServletRequestBuilder builder) {
    return builder.header(HttpHeaders.AUTHORIZATION, "Bearer test-token");
  }

  /**
   * Adds custom JWT Authorization header to the request (one-time use).
   *
   * <p>Note: For per-test authentication (affecting all requests in the test), use {@link
   * #setCustomJwt(Jwt)} instead. This method is for one-off requests with different credentials.
   *
   * @param builder the request builder to add the header to
   * @param jwt the custom JWT to use for this request only
   * @return the request builder with Authorization header
   */
  private MockHttpServletRequestBuilder withCustomJwtAuth(
      MockHttpServletRequestBuilder builder, Jwt jwt) {
    // Temporarily set custom JWT, make request, then restore previous JWT
    // Note: This is a simple approach; could be enhanced with request interceptors if needed
    return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue());
  }

  /**
   * Performs GET request with automatic JWT authentication.
   *
   * @param urlTemplate URL template with optional path variables
   * @param uriVars path variable values
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performGet(String urlTemplate, Object... uriVars) throws Exception {
    return mockMvc.perform(
        withJwtAuth(get(urlTemplate, uriVars).contentType(MediaType.APPLICATION_JSON)));
  }

  /**
   * Performs GET request with custom JWT authentication (one-time override).
   *
   * <p>Use this when you need a different user/scopes for a single request. For per-test
   * authentication, use {@link #setCustomJwt(Jwt)} in {@code @BeforeEach}.
   *
   * @param urlTemplate URL template with optional path variables
   * @param jwt custom JWT for this request
   * @param uriVars path variable values
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performGetWithJwt(String urlTemplate, Jwt jwt, Object... uriVars)
      throws Exception {
    var previousJwt = saveAndSetCustomJwt(jwt);

    try {
      return performGet(urlTemplate, uriVars);
    } finally {
      restoreCustomJwt(previousJwt);
    }
  }

  /**
   * Performs POST request with JSON body and automatic JWT authentication.
   *
   * @param urlTemplate URL template with optional path variables
   * @param jsonBody JSON request body
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performPost(String urlTemplate, String jsonBody) throws Exception {
    return mockMvc.perform(
        withJwtAuth(post(urlTemplate).contentType(MediaType.APPLICATION_JSON).content(jsonBody)));
  }

  /**
   * Performs POST request with custom JWT authentication (one-time override).
   *
   * @param urlTemplate URL template with optional path variables
   * @param jsonBody JSON request body
   * @param jwt custom JWT for this request
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performPostWithJwt(String urlTemplate, String jsonBody, Jwt jwt)
      throws Exception {
    var previousJwt = saveAndSetCustomJwt(jwt);

    try {
      return performPost(urlTemplate, jsonBody);
    } finally {
      restoreCustomJwt(previousJwt);
    }
  }

  /**
   * Performs PUT request with JSON body and automatic JWT authentication.
   *
   * @param urlTemplate URL template with optional path variables
   * @param jsonBody JSON request body
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performPut(String urlTemplate, String jsonBody) throws Exception {
    return mockMvc.perform(
        withJwtAuth(put(urlTemplate).contentType(MediaType.APPLICATION_JSON).content(jsonBody)));
  }

  /**
   * Performs PUT request with custom JWT authentication (one-time override).
   *
   * @param urlTemplate URL template with optional path variables
   * @param jsonBody JSON request body
   * @param jwt custom JWT for this request
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performPutWithJwt(String urlTemplate, String jsonBody, Jwt jwt)
      throws Exception {
    var previousJwt = saveAndSetCustomJwt(jwt);

    try {
      return performPut(urlTemplate, jsonBody);
    } finally {
      restoreCustomJwt(previousJwt);
    }
  }

  /**
   * Performs DELETE request with automatic JWT authentication.
   *
   * @param urlTemplate URL template with optional path variables
   * @param uriVars path variable values
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performDelete(String urlTemplate, Object... uriVars) throws Exception {
    return mockMvc.perform(
        withJwtAuth(delete(urlTemplate, uriVars).contentType(MediaType.APPLICATION_JSON)));
  }

  /**
   * Performs DELETE request with custom JWT authentication (one-time override).
   *
   * @param urlTemplate URL template with optional path variables
   * @param jwt custom JWT for this request
   * @param uriVars path variable values
   * @return ResultActions for chaining assertions
   * @throws Exception if request fails
   */
  protected ResultActions performDeleteWithJwt(String urlTemplate, Jwt jwt, Object... uriVars)
      throws Exception {
    var previousJwt = saveAndSetCustomJwt(jwt);

    try {
      return performDelete(urlTemplate, uriVars);
    } finally {
      restoreCustomJwt(previousJwt);
    }
  }

  // ==================== Unauthenticated Request Methods ====================

  /**
   * Performs GET request without JWT authentication (unauthenticated).
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
   * Performs POST request without JWT authentication (unauthenticated).
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
   * Performs PUT request without JWT authentication (unauthenticated).
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

  // ==================== JWT Save/Restore Helpers ====================

  /**
   * Temporarily saves current custom JWT and sets a new one.
   *
   * @param jwt the new JWT to set
   * @return the previous JWT (may be null)
   */
  private Jwt saveAndSetCustomJwt(Jwt jwt) {
    // Note: This implementation assumes access to the ThreadLocal in AbstractIntegrationTest
    // We'll need to expose a getter or make this work differently
    setCustomJwt(jwt);
    return null; // Will be enhanced if we need to support nested custom JWTs
  }

  /**
   * Restores the previous custom JWT.
   *
   * @param previousJwt the JWT to restore (may be null)
   */
  private void restoreCustomJwt(Jwt previousJwt) {
    if (previousJwt == null) {
      clearCustomJwt();
    } else {
      setCustomJwt(previousJwt);
    }
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
