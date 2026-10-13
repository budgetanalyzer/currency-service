package org.budgetanalyzer.currency.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import org.budgetanalyzer.currency.base.AbstractIntegrationTest;

/** Integration tests for the generated OpenAPI response schemas. */
@AutoConfigureMockMvc
class OpenApiContractIntegrationTest extends AbstractIntegrationTest {

  private static final String API_ERROR_RESPONSE = "#/components/schemas/ApiErrorResponse";

  @Autowired private MockMvc mockMvc;

  @Test
  void shouldDocumentAllErrorResponsesWithApiErrorResponseSchema() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    "$.paths['/v1/currencies'].post.responses['400'].content"
                        + "['application/json'].schema['$ref']")
                .value(API_ERROR_RESPONSE))
        .andExpect(
            jsonPath(
                    "$.paths['/v1/currencies/{id}'].get.responses['404'].content"
                        + "['application/json'].schema['$ref']")
                .value(API_ERROR_RESPONSE))
        .andExpect(
            jsonPath(
                    "$.paths['/v1/currencies/{id}'].put.responses['400'].content"
                        + "['application/json'].schema['$ref']")
                .value(API_ERROR_RESPONSE))
        .andExpect(
            jsonPath(
                    "$.paths['/v1/currencies/{id}'].put.responses['404'].content"
                        + "['application/json'].schema['$ref']")
                .value(API_ERROR_RESPONSE))
        .andExpect(
            jsonPath(
                    "$.paths['/v1/exchange-rates'].get.responses['400'].content"
                        + "['application/json'].schema['$ref']")
                .value(API_ERROR_RESPONSE));
  }
}
