package org.budgetanalyzer.currency.client.fred.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FredErrorResponse(
    @JsonProperty("error_code") Integer errorCode,
    @JsonProperty("error_message") String errorMessage) {
  // Fallback constructor for XML format which uses different field names
  public FredErrorResponse {
    // Handle both JSON (error_code/error_message) and XML (code/message) formats
  }
}
