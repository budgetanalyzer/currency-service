# Currency Service - Exchange Rate Management

## Service Purpose

Manages currencies and exchange rates for the Budget Analyzer application with automated import from external data providers.

**Domain**: Currency and exchange rate management
**Responsibilities**:
- CRUD operations for currency series
- Exchange rate queries with date range filtering
- Automated import from FRED (Federal Reserve Economic Data)
- Scheduled background imports with distributed coordination
- High-performance distributed caching

## Repository Scope

**Boundary**: This repository only.

**Allowed**:
- Read `../service-common/` and `../orchestration/docs/`
- All operations within this repository

**Forbidden**:
- Writing outside this repository

Cross-service changes: coordinate through orchestration or service-common.

## Spring Boot Patterns

**This service follows standard Budget Analyzer Spring Boot conventions.**

**Pattern**: Clean layered architecture (Controller → Service → Repository) with standardized naming, pure JPA persistence, and base entity classes.

**When to consult documentation**:
- Setting up architecture layers → Read [service-common/CLAUDE.md](../service-common/CLAUDE.md) Architecture Layers section
- Creating entities → Review Base Entity Classes in [service-common/CLAUDE.md](../service-common/CLAUDE.md) (AuditableEntity, SoftDeletableEntity)
- Writing controllers → See HTTP Response Patterns in [service-common/CLAUDE.md](../service-common/CLAUDE.md) (201 Created with Location header)
- Dependency injection patterns → Read Dependency Injection section in [service-common/CLAUDE.md](../service-common/CLAUDE.md)

**Quick reference**:
- Controllers: `*Controller` + thin HTTP layer only
- Services: `*Service` interface + `*ServiceImpl` + `@Transactional`
- Repositories: `*Repository` extends `JpaRepository<Entity, ID>`
- Pure JPA only: **Forbidden** `org.hibernate.*` → **Use** `jakarta.persistence.*`
- Base entities: Extend `AuditableEntity` or `SoftDeletableEntity`

**For comprehensive patterns:** Read [service-common/CLAUDE.md](../service-common/CLAUDE.md)

## Advanced Patterns Used

This service implements ALL advanced patterns from service-common for external integrations, messaging, caching, and distributed systems.

**Pattern**: Provider abstraction (external APIs), Transactional Outbox (guaranteed messaging), Redis caching (performance), ShedLock (distributed locking).

**When to consult detailed documentation**:
- **Adding new providers (ECB, Bloomberg)** → Read [Advanced Patterns Usage Guide](docs/advanced-patterns-usage.md#provider-abstraction-pattern)
- **Adjusting lock durations or schedules** → See [ShedLock section](docs/advanced-patterns-usage.md#shedlock-distributed-locking)
- **Cache tuning or monitoring** → Review [Redis Caching section](docs/advanced-patterns-usage.md#redis-distributed-caching)
- **Adding domain events** → Check [Event-Driven Messaging](docs/advanced-patterns-usage.md#event-driven-messaging)
- **Understanding pattern theory** → Read [service-common/docs/advanced-patterns.md](../service-common/docs/advanced-patterns.md)

**Quick reference**:
- **Provider Pattern**: Service → `ExchangeRateProvider` interface → `FredExchangeRateProvider` → `FredClient`
- **ShedLock**: `@SchedulerLock` on scheduled tasks, 15m max lock, 1m min lock, PostgreSQL-backed
- **Redis Cache**: `@Cacheable` on queries (1-3ms), `@CacheEvict` on imports (50-200ms miss), 80-95% hit rate
- **Messaging**: Domain events → `event_publication` table → `@ApplicationModuleListener` → RabbitMQ

**For implementation examples and testing patterns:** Read [docs/advanced-patterns-usage.md](docs/advanced-patterns-usage.md)

## Service-Specific Patterns

### FRED API Integration

**External provider:** Federal Reserve Economic Data (FRED)

**Discovery:**
```bash
# Find FRED client
cat src/main/java/org/budgetanalyzer/currency/client/fred/FredClient.java

# View provider interface
cat src/main/java/org/budgetanalyzer/currency/service/provider/ExchangeRateProvider.java

# Check configuration
cat src/main/resources/application.yml | grep -A 5 "fred"
```

**Key Configuration:**
```yaml
currency-service:
  fred:
    base-url: https://api.stlouisfed.org/fred
    api-key: ${FRED_API_KEY}
```

### Scheduled Exchange Rate Import

**Schedule:** Daily at 11 PM UTC
**Coordination:** ShedLock ensures only one pod executes

**Discovery:**
```bash
# Find scheduler
cat src/main/java/org/budgetanalyzer/currency/scheduler/ExchangeRateImportScheduler.java

# Check lock configuration
cat src/main/resources/application.yml | grep -A 5 "shedlock"
```

### Domain Model

See [docs/domain-model.md](docs/domain-model.md) for detailed entity relationships and business rules.

**Key Concepts:**
- **CurrencySeries**: Exchange rate time series from external providers (ISO 4217 codes)
- **ExchangeRate**: Individual rate observations (date + rate value)

**Discovery:**
```bash
# Find all entities
find src/main/java -type f -path "*/domain/*.java" | grep -v event

# View entity structure
cat src/main/java/org/budgetanalyzer/currency/domain/CurrencySeries.java
cat src/main/java/org/budgetanalyzer/currency/domain/ExchangeRate.java
```

### Package Structure

**Standard Spring Boot layers** - Read [service-common/CLAUDE.md](../service-common/CLAUDE.md) for architecture layer details

**Service-specific packages:**
- `client/fred/` - FRED API integration
- `scheduler/` - Background import jobs
- `messaging/` - Event-driven messaging (listener, consumer, publisher)
- `service/provider/` - Provider abstraction interface

**Discovery:**
```bash
# View full package structure
tree src/main/java/org/budgetanalyzer/currency -L 2

# Or without tree command
find src/main/java/org/budgetanalyzer/currency -type d | sort
```

**Critical Architecture Rules:**
- Controllers NEVER import repositories (use services)
- Consumers NEVER import repositories (delegate to services)
- Service layer NEVER imports message publishers (publishes domain events instead)
- Service layer NEVER references FRED directly (uses `ExchangeRateProvider` interface)

## API Documentation

**OpenAPI Specification:** Run service and access Swagger UI:
```bash
./gradlew bootRun
# Visit: http://localhost:8084/swagger-ui.html
```

**Key Endpoints:**
- Currency series CRUD: `/v1/currencies/**`
- Exchange rates: `/v1/exchange-rates/**`

**Gateway Access:**
- Internal: `http://localhost:8084/v1/currencies`
- External (via NGINX): `http://localhost:8080/api/v1/currencies`

## Running Locally

**Prerequisites:**
- JDK 24
- PostgreSQL 15+
- Redis 7+
- Gradle 8.11+
- FRED API key (sign up at https://fred.stlouisfed.org/docs/api/api_key.html)

**Start Infrastructure:**
```bash
cd ../orchestration
docker compose up
```

**Set Environment Variable:**
```bash
export FRED_API_KEY=your_api_key_here
```

**Run Service:**
```bash
./gradlew bootRun
```

**Access:**
- Service: http://localhost:8084
- Swagger UI: http://localhost:8084/swagger-ui.html
- Health Check: http://localhost:8084/actuator/health

## Discovery Commands

```bash
# Find all REST endpoints
grep -r "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping" src/main/java/*/api/

# Find provider implementations
find src/main/java -type f -name "*Provider.java"

# Find domain events
find src/main/java -type f -path "*/domain/event/*.java"

# Check scheduled tasks
grep -r "@Scheduled" src/main/java/

# View application configuration
cat src/main/resources/application.yml
```

## Build and Test

**Format code:**
```bash
./gradlew clean spotlessApply
```

**Build and test:**
```bash
./gradlew clean build
```

The build includes:
- Spotless code formatting checks
- Checkstyle rule enforcement
- All unit and integration tests
- JAR file creation

**Troubleshooting:**

If encountering "cannot resolve" errors for service-common classes:
```bash
cd ../service-common
./gradlew clean build publishToMavenLocal
cd ../currency-service
./gradlew clean build
```

## Testing

**Pattern**: Unit tests (*Test.java), integration tests (*IntegrationTest.java) with TestContainers. Minimum 80% coverage. Always test correct behavior (fix bugs, don't test around them).

**When to consult documentation**:
- Writing unit tests → Read Unit Testing Patterns section in [testing-patterns.md](../service-common/docs/testing-patterns.md)
- Setting up integration tests → Review TestContainers setup in [testing-patterns.md](../service-common/docs/testing-patterns.md)
- Understanding test philosophy → See Testing Philosophy section in [testing-patterns.md](../service-common/docs/testing-patterns.md)

**Quick reference**:
- Unit tests: No Spring context, fast, mock dependencies
- Integration tests: `@SpringBootTest` + TestContainers (PostgreSQL/Redis/RabbitMQ)
- Minimum coverage: 80% line coverage
- Use TestConstants for test data (no magic strings/numbers)

**Current state**: Limited coverage, opportunity for improvement (provider abstraction, caching, messaging, scheduling)

**For comprehensive testing patterns:** Read [testing-patterns.md](../service-common/docs/testing-patterns.md)

## Deployment

**Environment variables**: Standard Spring Boot + PostgreSQL + Redis + `FRED_API_KEY` (required)

**Health checks**: `/actuator/health/readiness`, `/actuator/health/liveness`

**Discovery:**
```bash
# View all env vars
cat src/main/resources/application.yml | grep '\${' | sort -u
```

## Notes for Claude Code

**CRITICAL - Prerequisites First**: Before implementing any plan or feature:
1. Check for prerequisites in documentation (e.g., "Prerequisites: service-common Enhancement")
2. If prerequisites are NOT satisfied, STOP immediately and inform the user
3. Do NOT attempt to hack around missing prerequisites - this leads to broken implementations that must be deleted
4. Complete prerequisites first, then return to the original task

**General guidance**: Read [service-common/CLAUDE.md](../service-common/CLAUDE.md) for code quality standards and build commands.

**Service-specific reminders**:
- Service layer uses `ExchangeRateProvider` interface, NEVER references FRED directly
- Consumers delegate to services, NEVER import repositories
- Services publish domain events, listeners bridge to external messages
- Use `@Cacheable` for queries, `@CacheEvict(allEntries=true)` after imports
- Use `@SchedulerLock` for scheduled tasks (multi-pod coordination)

---

## External Links (GitHub Web Viewing)

*The relative paths in this document are optimized for Claude Code. When viewing on GitHub, use these links to access other repositories:*

- [Service-Common Repository](https://github.com/budgetanalyzer/service-common)
- [Service-Common CLAUDE.md](https://github.com/budgetanalyzer/service-common/blob/main/CLAUDE.md)
- [Advanced Patterns Documentation](https://github.com/budgetanalyzer/service-common/blob/main/docs/advanced-patterns.md)
- [Testing Patterns Documentation](https://github.com/budgetanalyzer/service-common/blob/main/docs/testing-patterns.md)

### Web Search Year Awareness

Claude's training data may default to an outdated year. When using WebSearch for best practices or current information:

1. Check `<env>Today's date</env>` for the actual current year
2. Include that year in searches (e.g., "Spring Boot best practices 2025" not 2024)
3. This ensures results reflect current standards, not outdated patterns
