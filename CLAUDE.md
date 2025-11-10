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

## Spring Boot Patterns

**This service follows standard Budget Analyzer Spring Boot conventions.**

See [@service-common/CLAUDE.md](../service-common/CLAUDE.md) and [@service-common/docs/](../service-common/docs/) for:
- Architecture layers (Controller → Service → Repository)
- Naming conventions (`*Controller`, `*Service`, `*ServiceImpl`, `*Repository`)
- Testing patterns (JUnit 5, TestContainers)
- Error handling (exception hierarchy, `BusinessException` vs `InvalidRequestException`)
- Logging conventions (SLF4J structured logging)
- Dependency management (inherit from service-common parent POM)
- Code quality standards (Spotless, Checkstyle, var usage, Javadoc)
- Validation strategy (Bean Validation vs business validation)

## Advanced Patterns Used

**This service implements ALL advanced patterns documented in service-common.**

See [@service-common/docs/advanced-patterns.md](../service-common/docs/advanced-patterns.md) for detailed documentation on:
- **Provider Abstraction Pattern**: FRED API integration via `ExchangeRateProvider` interface
- **Event-Driven Messaging**: Spring Modulith transactional outbox for guaranteed message delivery
- **Redis Distributed Caching**: High-performance caching for exchange rate queries
- **ShedLock Distributed Locking**: Coordinated scheduled imports across multiple pods
- **Flyway Migrations**: Version-controlled database schema evolution

These patterns are production-proven and reusable across services. Currency service serves as the reference implementation.

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

**Important:** Provider-specific logic is encapsulated in `FredExchangeRateProvider`. Service layer only depends on `ExchangeRateProvider` interface, allowing future providers (ECB, Bloomberg) without service changes.

See [@service-common/docs/advanced-patterns.md#provider-abstraction-pattern](../service-common/docs/advanced-patterns.md#provider-abstraction-pattern)

### Scheduled Exchange Rate Import

**Schedule:** Daily at 11 PM UTC

**Coordination:** ShedLock ensures only one pod executes import in multi-instance deployment

**Discovery:**
```bash
# Find scheduler
cat src/main/java/org/budgetanalyzer/currency/scheduler/ExchangeRateImportScheduler.java

# Check lock configuration
cat src/main/resources/application.yml | grep -A 5 "shedlock"
```

**Lock Configuration:**
- `lockAtMostFor: 15m` - Safety timeout (import takes ~30 seconds)
- `lockAtLeastFor: 1m` - Prevents rapid re-execution

See [@service-common/docs/advanced-patterns.md#distributed-locking-with-shedlock](../service-common/docs/advanced-patterns.md#distributed-locking-with-shedlock)

### Exchange Rate Caching

**Cache:** Redis distributed cache with 1-hour TTL

**Performance:**
- Cache hit: 1-3ms
- Cache miss: 50-200ms (database query)
- Expected hit rate: 80-95%

**Discovery:**
```bash
# Find cache configuration
cat src/main/java/org/budgetanalyzer/currency/config/CacheConfig.java

# View cached service methods
grep -r "@Cacheable\|@CacheEvict" src/main/java/*/service/
```

**Cache Strategy:**
- `@Cacheable` on `getExchangeRates()` - Caches query results
- `@CacheEvict(allEntries = true)` on import - Clears all cache after new data imported
- Key format: `{targetCurrency}:{startDate}:{endDate}`

See [@service-common/docs/advanced-patterns.md#redis-distributed-caching](../service-common/docs/advanced-patterns.md#redis-distributed-caching)

### Event-Driven Architecture

**Pattern:** Transactional outbox ensures guaranteed message delivery

**Event Flow:**
1. Service creates currency series
2. Domain event persisted in database (same transaction)
3. Event listener publishes to RabbitMQ asynchronously
4. Consumer triggers exchange rate import

**Discovery:**
```bash
# Find domain events
cat src/main/java/org/budgetanalyzer/currency/domain/event/CurrencyCreatedEvent.java

# Find event listeners
cat src/main/java/org/budgetanalyzer/currency/messaging/listener/MessagingEventListener.java

# Find message consumers
cat src/main/java/org/budgetanalyzer/currency/messaging/consumer/ExchangeRateImportConsumer.java
```

**Benefits:**
- 100% guaranteed delivery (event survives crashes, network failures)
- Async HTTP responses (fast API response times)
- Automatic retries with Spring Modulith

See [@service-common/docs/advanced-patterns.md#event-driven-messaging-with-transactional-outbox](../service-common/docs/advanced-patterns.md#event-driven-messaging-with-transactional-outbox)

### Domain Model

**CurrencySeries Entity:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | Long | Yes | Primary key |
| currencyCode | String | Yes | ISO 4217 currency code |
| providerSeriesId | String | Yes | External provider series ID (e.g., FRED series) |
| createdAt | Instant | Inherited | Audit timestamp |
| updatedAt | Instant | Inherited | Audit timestamp |

**ExchangeRate Entity:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | Long | Yes | Primary key |
| targetCurrency | CurrencySeries | Yes | Foreign key to currency series |
| date | LocalDate | Yes | Exchange rate date |
| rate | BigDecimal | Yes | Exchange rate value |
| createdAt | Instant | Inherited | Audit timestamp |
| updatedAt | Instant | Inherited | Audit timestamp |

See [@src/main/java/org/budgetanalyzer/currency/domain/](src/main/java/org/budgetanalyzer/currency/domain/)

### Package Structure

```
org.budgetanalyzer.currency/
├── api/                  # REST controllers and API DTOs
├── domain/              # JPA entities and domain events
│   └── event/          # Domain events (CurrencyCreatedEvent)
├── service/             # Business logic
│   ├── dto/            # Internal service DTOs
│   └── provider/       # Provider abstraction
├── repository/          # Data access layer
├── client/             # External API clients
│   └── fred/          # FRED API client
├── config/             # Spring configuration
├── scheduler/          # Scheduled background tasks
└── messaging/          # Event-driven messaging
    ├── message/       # External message payloads
    ├── publisher/     # Message publishers (StreamBridge wrappers)
    ├── listener/      # Event listeners (domain → external)
    └── consumer/      # Message consumers (functional beans)
```

**Package Dependency Rules:**
```
api → service (NEVER repository)
service → repository, domain, provider
service → ApplicationEventPublisher (publishes domain events)
messaging/listener → domain/event, messaging/publisher
messaging/consumer → service (delegates to services)
service/provider → client (provider implementations use clients)
```

**Critical Boundaries:**
- Controllers NEVER import repositories
- Consumers NEVER import repositories (use services)
- Service layer NEVER imports message publishers (uses domain events)
- Service layer NEVER references FRED (uses provider interface)

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

## Testing Strategy

**Current State:**
- Limited test coverage - opportunity for improvement
- Basic smoke test exists

**Test Framework:**
- JUnit 5 (Jupiter)
- Spring Boot Test (`@SpringBootTest`, `@DataJpaTest`, `@WebMvcTest`)

**Priority Testing Needs:**
1. Controller layer tests with MockMvc
2. Service layer tests with mocked repositories
3. Provider abstraction tests with mock FRED client
4. Event-driven messaging tests (domain events → external messages)
5. Caching behavior verification
6. Scheduled task execution tests

**Future:** Migrate to TestContainers for PostgreSQL/Redis integration tests

See [@service-common/docs/testing-patterns.md](../service-common/docs/testing-patterns.md) for testing conventions.

## Deployment

### Environment Variables

**Application:**
- `SPRING_PROFILES_ACTIVE`: Active Spring profile

**Database:**
- `POSTGRES_HOST`: Database host
- `POSTGRES_PORT`: Database port
- `POSTGRES_DB`: Database name
- `POSTGRES_USER`: Database username
- `POSTGRES_PASSWORD`: Database password

**Cache:**
- `REDIS_HOST`: Redis host (default: localhost)
- `REDIS_PORT`: Redis port (default: 6379)
- `REDIS_PASSWORD`: Redis password (optional)

**External APIs:**
- `FRED_API_KEY`: FRED API authentication key (required)

### Health Checks

- Readiness: `/actuator/health/readiness`
- Liveness: `/actuator/health/liveness`

## Future Enhancements

### High Priority
- [ ] **Comprehensive integration tests** - TestContainers for PostgreSQL/Redis
- [ ] **WireMock for FRED API tests** - Mock external API responses
- [ ] **PostgreSQL partial indexes** - Optimize event_publication table queries
- [ ] **Additional exchange rate providers** - ECB, Bloomberg (via provider abstraction)

### Medium Priority
- [ ] **Prometheus metrics setup** - Complete endpoint exposure and custom metrics
- [ ] **Distributed tracing** - Zipkin/Jaeger integration
- [ ] **MapStruct for DTO mapping** - Replace manual mapping
- [ ] **Audit logging** - Track data changes with JPA entity listeners

### Low Priority
- [ ] **GraphQL endpoint** - Alternative to REST API
- [ ] **API rate limiting** - Request throttling (may be at gateway level)

See [@service-common/docs/advanced-patterns.md](../service-common/docs/advanced-patterns.md) for implementation guidance.

## AI Assistant Guidelines

When working on this service:

### Critical Rules

1. **NEVER implement changes without explicit permission** - Always present a plan and wait for approval
2. **Distinguish between statements and requests** - "I did X" is informational, not a request
3. **Questions deserve answers first** - Provide information before implementing
4. **Wait for explicit action language** - Only implement when user says "do it", "implement", etc.
5. **Limit file access** - Stay within currency-service directory

### Code Quality

- **Production-quality only** - No shortcuts or workarounds
- **Follow service layer architecture** - Services accept/return entities, not API DTOs
- **Pure JPA only** - No Hibernate-specific imports
- **Controllers NEVER import repositories** - All database access via services
- **Consumers NEVER import repositories** - All database access via services
- **Always run before committing:**
  1. `./gradlew clean spotlessApply`
  2. `./gradlew clean build`

### Checkstyle Warnings

- **Read build output carefully** - Check for warnings even if build succeeds
- **Fix all Checkstyle warnings** - Treat as errors
- **Common issues**: Missing Javadoc periods, wildcard imports, line length
- **If unable to fix**: Document warning details and notify user

### Architecture

- **Controllers**: Thin, HTTP-focused, delegate to services
- **Services**: Business logic, validation, transactions
- **Repositories**: Data access only, used by services only
- **Domain events**: Published by services, handled by listeners
- **Message consumers**: Functional beans that delegate to services
- **Provider abstraction**: Service layer uses interface, never concrete implementation

### Advanced Patterns

- **Provider abstraction**: NEVER reference FRED in service layer, use `ExchangeRateProvider` interface
- **Event-driven**: Services publish domain events, listeners bridge to external messages
- **Consumer error handling**: NEVER swallow exceptions, let them propagate for retry
- **Caching**: Use `@Cacheable` for queries, `@CacheEvict` for updates
- **Distributed locking**: Use `@SchedulerLock` for scheduled tasks in multi-pod deployment

### Documentation

- **Update CLAUDE.md** when architecture changes
- **Add JavaDoc** with proper punctuation (period at end of first sentence)
- **Document provider integrations** in comments
- **Keep OpenAPI annotations current**
- **Update advanced-patterns.md** if patterns evolve (affects all services)
