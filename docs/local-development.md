# Local Development

## Prerequisites

- JDK 25
- Docker, for the Testcontainers integration tests and the orchestration Kind cluster
- The orchestration Tilt environment, for shared PostgreSQL, Redis, and RabbitMQ when running the
  service locally

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

Redis and RabbitMQ TLS are off by default; the exports above turn TLS on and select the `infra-ca`
bundle. See [configuration.md](configuration.md#environment-variables) for every variable and its
default.

### TLS Notes

- The CA path must point at the host-side file created by `../orchestration/scripts/bootstrap/setup-infra-tls.sh`.
- RabbitMQ hostname verification is enabled. `SPRING_RABBITMQ_HOST` must use a name covered by the broker certificate SANs.
- The default host-side certificate includes `localhost`, `rabbitmq.infrastructure`, `rabbitmq.infrastructure.svc`, and `rabbitmq.infrastructure.svc.cluster.local`. Using a different hostname will fail the TLS handshake until the certificate is regenerated with that SAN.

## API Access

The service listens on port **8084** with the context path `/currency-service`.

### Through the gateway

| Endpoint | URL |
|---|---|
| Currencies API | `https://app.budgetanalyzer.localhost/api/v1/currencies` |
| Exchange Rates API | `https://app.budgetanalyzer.localhost/api/v1/exchange-rates` |
| Unified API docs | `https://app.budgetanalyzer.localhost/api-docs` |

The gateway maps `/api/v1/...` to `/currency-service/v1/...` on this service and adds the claims
headers after validating the session. See the orchestration
[resource-based routing pattern](https://github.com/budgetanalyzer/orchestration/blob/main/docs/architecture/resource-routing-pattern.md)
for routing and the
[getting-started guide](https://github.com/budgetanalyzer/orchestration/blob/main/docs/development/getting-started.md#operator-entry-points)
for the entry points and the unified API docs surface.

### Direct to the service

| Endpoint | URL |
|---|---|
| Swagger UI | `http://localhost:8084/currency-service/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8084/currency-service/v3/api-docs` |
| Health check | `http://localhost:8084/currency-service/actuator/health` |

These three paths are public in service-common's claims-header security. A direct call to a
`/currency-service/v1/...` route without claims headers returns `401`; see
[api/README.md](api/README.md#authentication-and-authorization). In Swagger UI, select the
`http://localhost:8084/currency-service` server before using "Try it out": the first server listed
in `OpenApiConfig`, `http://localhost:8080/api`, is not a current entry point.

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
coverage. The recorded baseline is 96.41% line / 88.71% branch; ratchet toward
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
