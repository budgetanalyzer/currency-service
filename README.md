# Currency Service

> **⚠️ Work in Progress**: This project is under active development. Features and documentation are subject to change.

Spring Boot microservice for managing currencies and exchange rates in Budget Analyzer - a personal finance management application.

## Overview

The Currency Service is responsible for:

- Managing supported currencies
- Tracking exchange rates between currencies
- Providing currency conversion capabilities
- Supporting multi-currency financial operations
- Historical exchange rate data

## Features

- RESTful API for currency management
- PostgreSQL persistence with Flyway migrations
- OpenAPI/Swagger documentation
- Exchange rate management
- Currency conversion endpoints
- Spring Boot Actuator for health checks
- Input validation and error handling

## Technology Stack

- **Java 24**
- **Spring Boot 3.x**
  - Spring Web (REST APIs)
  - Spring Data JPA (database access)
  - Spring Boot Actuator (monitoring)
  - Spring Validation
- **PostgreSQL** (database)
- **Flyway** (database migrations)
- **SpringDoc OpenAPI** (API documentation)
- **JUnit 5** (testing)

## Quick Start

### Prerequisites

- JDK 24
- Docker and Docker Compose (for infrastructure)

**Local development setup**: See [getting-started.md](https://github.com/budget-analyzer/orchestration/blob/main/docs/development/getting-started.md)

**Database configuration**: See [database-setup.md](https://github.com/budget-analyzer/orchestration/blob/main/docs/development/database-setup.md)

Note: This service uses the `currency` database (not `budget_analyzer`).

### Running Locally

```bash
# Build the service
./gradlew build

# Run the service
./gradlew bootRun
```

The service runs on port 8084 for development/debugging.

### API Access

**Production/User access** (through gateway):
- Currencies API: `http://localhost:8080/api/v1/currencies`
- Exchange Rates API: `http://localhost:8080/api/v1/exchange-rates`
- Unified API Documentation: `http://localhost:8080/api/docs`
- OpenAPI JSON: `http://localhost:8080/api/docs/openapi.json`
- OpenAPI YAML: `http://localhost:8080/api/docs/openapi.yaml`

**Development access** (direct to service):
- Swagger UI: `http://localhost:8084/swagger-ui.html`
- OpenAPI Spec: `http://localhost:8084/v3/api-docs`
- Health Check: `http://localhost:8084/actuator/health`

## Development

### Building

```bash
# Clean and build
./gradlew clean build

# Run tests
./gradlew test

# Check code style
./gradlew spotlessCheck

# Apply code formatting
./gradlew spotlessApply
```

### Code Quality

This project enforces:
- **Google Java Format** for code style
- **Checkstyle** for standards
- **Spotless** for automated formatting

## Project Structure

```
currency-service/
├── src/
│   ├── main/
│   │   ├── java/              # Java source code
│   │   └── resources/
│   │       ├── application.properties
│   │       └── db/migration/  # Flyway migrations
│   └── test/java/             # Unit and integration tests
└── build.gradle.kts           # Build configuration
```

## Integration

This service integrates with:
- **API Gateway** (NGINX) for routing
- **PostgreSQL** for data persistence
- **Service Common** for shared utilities
- **Transaction Service** for multi-currency transaction support

See the [orchestration repository](https://github.com/budget-analyzer/orchestration) for full system setup.

## Related Repositories

- **Orchestration**: https://github.com/budget-analyzer/orchestration
- **Service Common**: https://github.com/budget-analyzer/service-common
- **Transaction Service**: https://github.com/budget-analyzer/transaction-service
- **Web Frontend**: https://github.com/budget-analyzer/budget-analyzer-web

## License

MIT

## Contributing

This project is currently in early development. Contributions, issues, and feature requests are welcome as we build toward a stable release.
