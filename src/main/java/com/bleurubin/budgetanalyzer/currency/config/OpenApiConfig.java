package com.bleurubin.budgetanalyzer.currency.config;

import com.bleurubin.service.api.ApiErrorResponse;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.time.Instant;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

@Configuration
@OpenAPIDefinition(
    info =
        @Info(
            title = "Currency Service",
            version = "1.0",
            description = "API documentation for Currency Service resources",
            contact = @Contact(name = "Bleu Rubin", email = "support@bleurubin.com"),
            license = @License(name = "MIT", url = "https://opensource.org/licenses/MIT")),
    servers = {
      @Server(url = "http://localhost:8080/api", description = "Local environment (via gateway)"),
      @Server(
          url = "http://localhost:8082/currency-service",
          description = "Local environment (direct)"),
      @Server(url = "https://api.bleurubin.com", description = "Production environment")
    },
    externalDocs =
        @ExternalDocumentation(
            description = "Find more info here",
            url = "https://github.com/bleurubin/currency-service"))
public class OpenApiConfig {

  @Bean
  public OpenApiCustomizer globalResponseCustomizer() {
    return openApi -> {
      if (openApi.getPaths() != null) {
        openApi
            .getPaths()
            .values()
            .forEach(
                pathItem -> pathItem.readOperationsMap().forEach(this::addStandardErrorResponses));
      }
    };
  }

  private void addStandardErrorResponses(PathItem.HttpMethod httpMethod, Operation operation) {
    addInternalServerErrorResponse(operation);
    addServiceUnavailableResponse(operation);

    switch (httpMethod) {
      case POST:
        addBadRequestResponse(operation);
        break;

      case PUT:
      case PATCH:
        addBadRequestResponse(operation);
        addNotFoundResponse(operation);
        break;

      case GET:
      case DELETE:
        addNotFoundResponse(operation);
        break;

      default:
        // No standard error responses for other methods
        break;
    }
  }

  private void addBadRequestResponse(Operation operation) {
    operation.getResponses().addApiResponse("400", buildApiErrorResponse(HttpStatus.BAD_REQUEST));
  }

  private void addNotFoundResponse(Operation operation) {
    operation.getResponses().addApiResponse("404", buildApiErrorResponse(HttpStatus.NOT_FOUND));
  }

  private void addInternalServerErrorResponse(Operation operation) {
    operation
        .getResponses()
        .addApiResponse("500", buildApiErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR));
  }

  private void addServiceUnavailableResponse(Operation operation) {
    operation
        .getResponses()
        .addApiResponse("503", buildApiErrorResponse(HttpStatus.SERVICE_UNAVAILABLE));
  }

  private ApiResponse buildApiErrorResponse(HttpStatus httpStatus) {
    ApiErrorResponse exampleResponse =
        ApiErrorResponse.builder()
            .type(httpStatus.name().toLowerCase())
            .title(httpStatus.getReasonPhrase())
            .status(httpStatus.value())
            .instance("/currency-service/rates")
            .timestamp(Instant.now())
            .build();

    return new ApiResponse()
        .description(httpStatus.getReasonPhrase())
        .content(
            new Content()
                .addMediaType(
                    "application/json",
                    new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ApiErrorResponse"))
                        .example(exampleResponse)));
  }
}
