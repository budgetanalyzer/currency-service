# Local Development

## Prerequisites

- JDK 24
- Docker and Docker Compose (for infrastructure)

**Ecosystem setup guides:**
- [getting-started.md](https://github.com/budgetanalyzer/orchestration/blob/main/docs/development/getting-started.md)
- [database-setup.md](https://github.com/budgetanalyzer/orchestration/blob/main/docs/development/database-setup.md)
- [service-common artifact resolution](https://github.com/budgetanalyzer/orchestration/blob/main/docs/development/service-common-artifact-resolution.md)

> **Note:** This service uses the `currency` database (not `budget_analyzer`). Local builds resolve `service-common` from `mavenLocal()` — no GitHub credentials required.

## Running Locally

Start shared infrastructure from the orchestration repo:

```bash
cd ../orchestration
tilt up
```

In another terminal, export the required environment variables and start the service:

```bash
cd ../currency-service

export FRED_API_KEY=your_api_key_here
export SPRING_DATASOURCE_PASSWORD=your_currency_database_password
export SPRING_RABBITMQ_PASSWORD=your_currency_service_rabbitmq_password
export SPRING_RABBITMQ_HOST=localhost
export SPRING_RABBITMQ_PORT=5671
export SPRING_RABBITMQ_SSL_ENABLED=true
export SPRING_RABBITMQ_SSL_BUNDLE=infra-ca
export SPRING_DATA_REDIS_PASSWORD=your_currency_service_redis_password
export SPRING_DATA_REDIS_HOST="${SPRING_DATA_REDIS_HOST:-localhost}"
export SPRING_DATA_REDIS_PORT="${SPRING_DATA_REDIS_PORT:-6379}"
export SPRING_DATA_REDIS_SSL_ENABLED=true
export SPRING_DATA_REDIS_SSL_BUNDLE=infra-ca
export INFRA_CA_CERT_PATH="file:$(cd ../orchestration && pwd)/nginx/certs/infra/infra-ca.pem"

./gradlew bootRun
```

If you are reusing values from `../orchestration/.env`, map
`POSTGRES_CURRENCY_SERVICE_PASSWORD`,
`RABBITMQ_CURRENCY_SERVICE_PASSWORD`, and
`REDIS_CURRENCY_SERVICE_PASSWORD` into the Spring environment variables above.

### TLS Notes

- The CA path must point at the host-side file created by `../orchestration/scripts/bootstrap/setup-infra-tls.sh`.
- RabbitMQ hostname verification is enabled. `SPRING_RABBITMQ_HOST` must use a name covered by the broker certificate SANs.
- The default host-side certificate includes `localhost`, `rabbitmq.infrastructure`, `rabbitmq.infrastructure.svc`, and `rabbitmq.infrastructure.svc.cluster.local`. Using a different hostname will fail the TLS handshake until the certificate is regenerated with that SAN.

## API Access

The service runs on port **8084** for development/debugging.

### Production (through gateway)

| Endpoint | URL |
|---|---|
| Currencies API | `http://localhost:8080/api/v1/currencies` |
| Exchange Rates API | `http://localhost:8080/api/v1/exchange-rates` |
| Unified API Docs | `https://api.budgetanalyzer.localhost/api/docs` |
| OpenAPI JSON | `https://api.budgetanalyzer.localhost/api/docs/openapi.json` |
| OpenAPI YAML | `https://api.budgetanalyzer.localhost/api/docs/openapi.yaml` |

### Development (direct to service)

| Endpoint | URL |
|---|---|
| Swagger UI | `http://localhost:8084/swagger-ui.html` |
| OpenAPI Spec | `http://localhost:8084/v3/api-docs` |
| Health Check | `http://localhost:8084/actuator/health` |

## Building

```bash
./gradlew clean build       # Clean and build
./gradlew test              # Run tests
./gradlew spotlessCheck     # Check code style
./gradlew clean spotlessApply  # Apply code formatting
```

## Code Quality

This project enforces:
- **Google Java Format** for code style
- **Checkstyle** for standards
- **Spotless** for automated formatting
