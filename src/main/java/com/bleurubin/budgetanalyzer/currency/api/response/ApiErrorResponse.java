package com.bleurubin.budgetanalyzer.currency.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/*
 * RFC-7807 error response.  https://datatracker.ietf.org/doc/html/rfc7807
 * The type field isn't a URI contrary to the specification as that doesn't
 * seem practical.
 */
@Schema(description = "Standard API error response format (RFC 7807-inspired)")
public class ApiErrorResponse {

  @Schema(
      description = "Machine-readable error type (slug or code)",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "transaction_not_found")
  private String type;

  @Schema(
      description = "Short summary of the problem",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "Transaction not found")
  private String title;

  @Schema(
      description = "HTTP status code",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "404")
  private int status;

  @Schema(
      description = "Detailed explanation of the problem",
      requiredMode = Schema.RequiredMode.NOT_REQUIRED,
      example = "Transaction with Id 123 could not be located.")
  private String detail;

  @Schema(
      description = "Request path or trace Id",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "/transactions/123")
  private String instance;

  @Schema(
      description = "Timestamp of the error in ISO-8601 format",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "2023-10-15T14:30:00.500Z")
  private Instant timestamp;

  @Schema(
      description = "List of field-level validation errors (optional)",
      requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  private List<FieldError> errors;

  public String getType() {
    return type;
  }

  public String getTitle() {
    return title;
  }

  public int getStatus() {
    return status;
  }

  public String getDetail() {
    return detail;
  }

  public String getInstance() {
    return instance;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public List<FieldError> getErrors() {
    return errors;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private final ApiErrorResponse target = new ApiErrorResponse();

    public Builder type(String type) {
      target.type = type;
      return this;
    }

    public Builder title(String title) {
      target.title = title;
      return this;
    }

    public Builder status(int status) {
      target.status = status;
      return this;
    }

    public Builder detail(String detail) {
      target.detail = detail;
      return this;
    }

    public Builder instance(String instance) {
      target.instance = instance;
      return this;
    }

    public Builder timestamp(Instant timestamp) {
      target.timestamp = timestamp;
      return this;
    }

    public Builder errors(List<FieldError> errors) {
      target.errors = errors;
      return this;
    }

    public Builder addError(FieldError error) {
      if (target.errors == null) target.errors = new ArrayList<>();
      target.errors.add(error);
      return this;
    }

    public ApiErrorResponse build() {
      return target;
    }
  }

  @Schema(description = "Field-level validation error details")
  public static class FieldError {
    @Schema(
        description = "Field that triggered the error",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "email")
    private String field;

    @Schema(
        description = "Error message",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "must be a valid email address")
    private String message;

    @Schema(
        description = "Value that caused the error",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "invalid@email")
    private Object rejectedValue;

    public FieldError() {}

    public FieldError(String field, String message, Object rejectedValue) {
      this.field = field;
      this.message = message;
      this.rejectedValue = rejectedValue;
    }

    public String getField() {
      return field;
    }

    public String getMessage() {
      return message;
    }

    public Object getRejectedValue() {
      return rejectedValue;
    }

    public static FieldError of(String field, String message, Object rejectedValue) {
      return new FieldError(field, message, rejectedValue);
    }
  }
}
