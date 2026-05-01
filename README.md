# Currency Service

> "Archetype: service. Role: Manages currencies and exchange rates with external provider integration."
>
> — [AGENTS.md](AGENTS.md#tree-position)

[![Build](https://github.com/budgetanalyzer/currency-service/actions/workflows/build.yml/badge.svg)](https://github.com/budgetanalyzer/currency-service/actions/workflows/build.yml)

Spring Boot microservice that automates exchange rate imports from the [Federal Reserve Economic Data (FRED)](https://fred.stlouisfed.org/) API, delivering reliable currency and exchange rate data for Budget Analyzer.

## Core Capabilities

- **Automated FRED imports** - Daily scheduled imports with provider abstraction ([details](docs/fred-integration.md))
- **Currency series management** - CRUD operations for exchange rate time series ([API docs](docs/api/README.md))
- **Historical rate queries** - Date range filtering with Redis caching (80-95% hit rate)
- **Distributed coordination** - Multi-pod safe scheduling with ShedLock
- **Event-driven architecture** - Guaranteed delivery via transactional outbox + RabbitMQ

## Quick Start

**Prerequisites:** JDK 24, Docker/Docker Compose

```bash
# 1. Start infrastructure
cd ../orchestration && tilt up

# 2. Export credentials and start the service
cd ../currency-service
export FRED_API_KEY=your_api_key_here
export SPRING_DATASOURCE_PASSWORD=your_db_password
export SPRING_RABBITMQ_PASSWORD=your_rabbitmq_password
export SPRING_DATA_REDIS_PASSWORD=your_redis_password
# See docs/local-development.md for full env var list (TLS, ports, etc.)
./gradlew bootRun
```

**Dev server:** `http://localhost:8084` | **Swagger UI:** `http://localhost:8084/swagger-ui.html`

## Technology Stack

Java 24, Spring Boot 3.x (Web, Data JPA, Modulith, Actuator), PostgreSQL, Redis, RabbitMQ, ShedLock, Flyway, SpringDoc OpenAPI, TestContainers + JUnit 5

## Project Structure

```
currency-service/
├── src/main/java/              # Java source code
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/           # Flyway migrations
├── src/test/java/              # Unit and integration tests
└── docs/                       # Detailed documentation
```

## Documentation

| Document | Description |
|---|---|
| [Local Development](docs/local-development.md) | Running locally, environment variables, building, API access |
| [Configuration](docs/configuration.md) | Infrastructure dependencies, config properties, security |
| [FRED Integration](docs/fred-integration.md) | Data source, API key, provider architecture, import schedule |
| [API Reference](docs/api/README.md) | Endpoints, request/response examples, validation rules |
| [Domain Model](docs/domain-model.md) | Entities, aggregates, domain events, business rules |
| [Advanced Patterns](docs/advanced-patterns-usage.md) | Provider abstraction, ShedLock, Redis caching, messaging |

## Related Repositories

- [Orchestration](https://github.com/budgetanalyzer/orchestration) - Full system setup and infrastructure
- [Service Common](https://github.com/budgetanalyzer/service-common) - Shared libraries and patterns
- [Session Gateway](https://github.com/budgetanalyzer/session-gateway) - OAuth2 session management
- [Transaction Service](https://github.com/budgetanalyzer/transaction-service) - Multi-currency transactions
- [Permission Service](https://github.com/budgetanalyzer/permission-service) - Authorization (roles and permissions)
- [Web Frontend](https://github.com/budgetanalyzer/budget-analyzer-web) - Browser client

## License

MIT
