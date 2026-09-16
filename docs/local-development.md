# Local Development

## Prerequisites

- JDK 25
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

Dependency versions for shared Spring libraries are managed by the service-common
`spring-cloud-platform` artifact. Local builds resolve that platform, `service-web`, and
`service-core` from `mavenLocal()` first; after changing service-common platform metadata, publish it
locally before rebuilding this service. The authenticated `service-common` GitHub Packages repository
is the remote source for `org.budgetanalyzer` artifacts. Maven Central is used only for external
dependencies and explicitly excludes the `org.budgetanalyzer` group.

```bash
./gradlew clean build       # Clean and build
./gradlew test              # Run tests
./gradlew test jacocoTestReport  # Generate JaCoCo coverage reports
./gradlew spotlessCheck     # Check code style
./gradlew clean spotlessApply  # Apply code formatting
```

JaCoCo writes the HTML report to `build/reports/jacoco/test/html/index.html`
and the XML report to `build/reports/jacoco/test/jacocoTestReport.xml`.
`check` enforces the Phase 2 coverage gates: 90% line coverage and 85% branch
coverage. The recorded baseline is 96.43% line / 88.71% branch; ratchet toward
critical utility and provider path coverage.

## Testing

Before writing or changing tests, consult the
[canonical service-common testing patterns](https://github.com/budgetanalyzer/service-common/blob/main/docs/testing-patterns.md).
Keep application-owned Spring beans real. Integration tests use Testcontainers for PostgreSQL,
Redis, and RabbitMQ, and use WireMock only for the external FRED HTTP boundary.

Docker must be running, but the integration suite does not require shared local infrastructure or a
real FRED API key. `AbstractIntegrationTest` supplies the containers, and tests that call FRED extend
`AbstractWireMockTest` and register controlled FRED responses through `FredApiStubs`.

```bash
# Run one integration suite
./gradlew test --tests '*EventListenerIntegrationTest'

# Run all messaging integration suites
./gradlew test --tests '*EventListenerIntegrationTest' \
  --tests '*MessageConsumerIntegrationTest' \
  --tests '*EndToEndMessagingFlowIntegrationTest'
```

## Code Quality

This project enforces:
- **Google Java Format** for code style
- **Checkstyle** for standards
- **Spotless** for automated formatting
