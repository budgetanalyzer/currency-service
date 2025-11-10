package org.budgetanalyzer.currency.config;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;

import org.budgetanalyzer.service.config.BaseOpenApiConfig;

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
          url = "http://localhost:8084/currency-service",
          description = "Local environment (direct)"),
      @Server(url = "https://api.bleurubin.com", description = "Production environment")
    },
    externalDocs =
        @ExternalDocumentation(
            description = "Find more info here",
            url = "https://github.com/budget-analyzer/currency-service"))
public class OpenApiConfig extends BaseOpenApiConfig {}
