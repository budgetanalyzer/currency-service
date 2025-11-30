# Currency Service

> "Archetype: service. Role: Manages currencies and exchange rates with external provider integration."
>
> — [CLAUDE.md](CLAUDE.md#tree-position)

[![Build](https://github.com/budgetanalyzer/currency-service/actions/workflows/build.yml/badge.svg)](https://github.com/budgetanalyzer/currency-service/actions/workflows/build.yml)

Spring Boot microservice that automates exchange rate imports from the Federal Reserve Economic Data (FRED) API. This service provides production-quality integration with the St. Louis Fed's economic data infrastructure, delivering reliable currency and exchange rate data for Budget Analyzer.

## Overview

The Currency Service integrates with [FRED](https://fred.stlouisfed.org/) (Federal Reserve Economic Data) to automatically import and maintain exchange rate time series. FRED provides access to 800,000+ economic data series from the Federal Reserve Bank of St. Louis.

**Core Capabilities:**

- **Automated FRED imports** - Daily scheduled imports from the Fed's Exchange Rates category
- **Currency series management** - CRUD operations for tracking exchange rate time series
- **Historical rate queries** - Date range filtering with high-performance caching
- **Distributed coordination** - Multi-pod safe scheduling with ShedLock
- **Event-driven architecture** - Domain events with guaranteed delivery via RabbitMQ

The existing FRED client and API key infrastructure can support importing any FRED data category in future services (e.g., interest rates, inflation indices, employment data).

## Features

### FRED Integration
- Automated daily imports from Federal Reserve Economic Data
- Provider abstraction pattern (extensible to ECB, Bloomberg, etc.)
- Configurable import schedules with cron expressions

### Data Management
- Currency series CRUD with soft delete support
- Exchange rate queries with date range filtering
- Flyway database migrations

### Production Infrastructure
- **Redis caching** - 1-3ms response times, 80-95% hit rates
- **ShedLock** - Distributed lock coordination for multi-pod deployments
- **RabbitMQ** - Event-driven messaging with transactional outbox pattern
- **Health checks** - Kubernetes-ready liveness/readiness probes

### API & Documentation
- RESTful API with OpenAPI/Swagger documentation
- Input validation and standardized error handling
- Spring Boot Actuator monitoring

## Technology Stack

- **Java 24**
- **Spring Boot 3.x**
  - Spring Web (REST APIs)
  - Spring Data JPA (database access)
  - Spring Boot Actuator (monitoring)
  - Spring Validation
  - Spring Modulith (event-driven architecture)
- **PostgreSQL** (database + ShedLock storage)
- **Redis** (distributed caching)
- **RabbitMQ** (message broker)
- **ShedLock** (distributed scheduling)
- **Flyway** (database migrations)
- **SpringDoc OpenAPI** (API documentation)
- **TestContainers + JUnit 5** (testing)

## FRED Integration

This service integrates with the [Federal Reserve Economic Data (FRED)](https://fred.stlouisfed.org/) API, maintained by the Federal Reserve Bank of St. Louis. FRED is a comprehensive database of 800,000+ economic time series from dozens of national, international, public, and private sources.

### Data Source

Exchange rate data is imported from the [Daily Rates category](https://fred.stlouisfed.org/categories/94):

- **Category path**: Money, Banking, & Finance → Exchange Rates → Daily Rates
- **Available series**: USD/EUR, USD/GBP, USD/JPY, USD/CNY, and 20+ other currency pairs
- **Dollar indices**: Nominal Broad, Advanced Foreign Economies, Emerging Markets
- **Historical depth**: Data back to 1971 for major currencies

### Expandability

The FRED API provides access to economic data far beyond exchange rates. With the existing client infrastructure and API key, future Budget Analyzer services could import:

- **Interest rates** - Treasury yields, Fed funds rate, LIBOR
- **Inflation** - CPI, PCE, producer prices
- **Employment** - Unemployment rate, payrolls, labor force
- **GDP & output** - Real GDP, industrial production
- **Regional data** - State and metro area statistics

See the full [FRED categories](https://fred.stlouisfed.org/categories) for available data.

### API Key

FRED requires a free API key. Register at https://fred.stlouisfed.org/docs/api/api_key.html

## Quick Start

### Prerequisites

- JDK 24
- Docker and Docker Compose (for infrastructure)

**Local development setup**: See [getting-started.md](https://github.com/budgetanalyzer/orchestration/blob/main/docs/development/getting-started.md)

**Database configuration**: See [database-setup.md](https://github.com/budgetanalyzer/orchestration/blob/main/docs/development/database-setup.md)

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
- Unified API Documentation: `https://api.budgetanalyzer.localhost/api/docs`
- OpenAPI JSON: `https://api.budgetanalyzer.localhost/api/docs/openapi.json`
- OpenAPI YAML: `https://api.budgetanalyzer.localhost/api/docs/openapi.yaml`

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
./gradlew clean spotlessApply
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

See the [orchestration repository](https://github.com/budgetanalyzer/orchestration) for full system setup.

## Related Repositories

- **Orchestration**: https://github.com/budgetanalyzer/orchestration
- **Service Common**: https://github.com/budgetanalyzer/service-common
- **Transaction Service**: https://github.com/budgetanalyzer/transaction-service
- **Web Frontend**: https://github.com/budgetanalyzer/budget-analyzer-web

## License

MIT

## Contributing

Contributions, issues, and feature requests are welcome. Please see the related repositories for the full Budget Analyzer ecosystem.
