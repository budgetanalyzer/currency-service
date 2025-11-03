# Currency Service - Spring Boot Microservice

## Project Overview

The Currency Service is a production-grade Spring Boot microservice responsible for managing currencies and exchange rates within the Budget Analyzer application ecosystem. It provides RESTful APIs for currency operations and exchange rate queries, with automated import capabilities from external data sources.

## Architecture

### Technology Stack

- **Framework**: Spring Boot 3.5.6
- **Language**: Java 24
- **Database**: PostgreSQL (production), H2 (testing)
- **Cache**: Redis (distributed caching)
- **API Documentation**: SpringDoc OpenAPI 3
- **Build Tool**: Gradle (Kotlin DSL)
- **Code Quality**: Spotless (Google Java Format), Checkstyle

### Package Structure

The service follows a clean, layered architecture with clear separation of concerns:

```
com.bleurubin.budgetanalyzer.currency/
├── api/                          # REST controllers and response DTOs
│   ├── CurrencyController.java
│   ├── ExchangeRateController.java
│   └── response/                 # API response DTOs only
├── domain/                       # Domain entities
│   └── ExchangeRate.java
├── service/                      # Business logic layer
│   ├── ExchangeRateService.java
│   ├── ExchangeRateImportService.java
│   └── provider/                 # Exchange rate data providers
├── repository/                   # Data access layer
│   ├── ExchangeRateRepository.java
│   └── spec/                     # JPA Specifications
├── client/                       # External API clients
│   └── fred/                     # FRED API integration
├── scheduler/                    # Scheduled tasks
│   └── ExchangeRateImportScheduler.java
├── config/                       # Configuration classes
│   ├── OpenApiConfig.java
│   ├── WebClientConfig.java
│   ├── CacheConfig.java
│   └── CurrencyServiceProperties.java
└── dto/                          # Internal DTOs (not API contracts)
```

## Architectural Principles

### 1. Production-Quality Code

**RULE**: All code must be production-ready. No shortcuts, prototypes, or workarounds.

- Follow established design patterns
- Implement proper error handling and validation
- Write comprehensive tests
- Ensure thread safety where applicable
- Use appropriate logging levels
- Handle edge cases explicitly

### 2. Service Layer Architecture

**RULE**: Services accept and return domain entities, NOT API request/response objects.

This enables service reusability across multiple contexts:
- REST API endpoints
- Scheduled jobs
- Message queue consumers (future)
- Internal service-to-service calls

**Controller Responsibilities:**
- Handle HTTP concerns (status codes, headers)
- Retrieve entities by ID from repositories
- Map API DTOs to/from domain entities
- Delegate business logic to services

**Service Responsibilities:**
- Execute business logic
- Perform business validation
- Manage transactions
- Coordinate between repositories
- Publish domain events

**Example Pattern:**
```java
@RestController
@RequestMapping("/exchange-rates")
public class ExchangeRateController {

  @Autowired private ExchangeRateRepository exchangeRateRepository;
  @Autowired private ExchangeRateMapper mapper;
  @Autowired private ExchangeRateService exchangeRateService;

  @PutMapping("/{id}")
  public ExchangeRateResponse update(@PathVariable Long id,
                                      @RequestBody ExchangeRateRequest request) {
    // 1. Retrieve entity
    var exchangeRate = exchangeRateRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Exchange rate not found"));

    // 2. Map request to entity
    mapper.updateFromRequest(request, exchangeRate);

    // 3. Delegate to service
    var updated = exchangeRateService.update(exchangeRate);

    // 4. Map entity to response
    return mapper.toResponse(updated);
  }
}
```

**Controllers ONLY:**
- Use `repository.findById(id)` for entity resolution
- Throw `ResourceNotFoundException` when entity not found
- Throw `InvalidRequestException` for malformed requests

**Controllers NEVER:**
- Perform complex repository queries
- Execute business logic or validation
- Import service-level exceptions beyond ResourceNotFoundException

### 3. Persistence Layer: Pure JPA

**RULE**: Use pure JPA (Jakarta Persistence API) exclusively. NO Hibernate-specific features.

**Why?**
- **Portability**: Allows switching JPA providers without code changes
- **Standard compliance**: JPA is a specification with multiple implementations
- **Architectural discipline**: Maintains flexibility at minimal cost

**Forbidden:**
```java
❌ import org.hibernate.*;
❌ import org.hibernate.annotations.*;
❌ import org.hibernate.criterion.*;
```

**Allowed:**
```java
✅ import jakarta.persistence.*;
```

**Note**: While we acknowledge Hibernate is unlikely to be replaced, adhering to JPA standards is a best practice that prevents vendor lock-in and maintains architectural integrity.

### 4. Clear Package Separation

Package boundaries should be self-evident from inspection:

- **api**: REST controllers and API-specific response DTOs only
- **domain**: JPA entities representing business domain
- **service**: Business logic, orchestration, validation
- **repository**: Data access layer (Spring Data JPA)
- **client**: External API integrations
- **config**: Spring configuration classes
- **dto**: Internal data transfer objects (NOT API contracts)
- **scheduler**: Scheduled background tasks

**Package Dependency Rules:**
```
api → service → repository
api → domain
service → domain
service → repository
service → client
repository → domain
```

API classes should NEVER be imported by service layer.

### 5. Exception Handling Strategy

**Controller-Level Exceptions:**
- `ResourceNotFoundException` - Entity not found by ID (404)
- `InvalidRequestException` - Malformed request data (400)

**Service-Level Exceptions:**
- `BusinessException` - Business rule violations (422)
- `DuplicateResourceException` - Uniqueness constraint violations (409)
- `ExternalServiceException` - External API failures (502/503)

**Global Exception Handler:**
All exceptions are handled centrally via `@RestControllerAdvice` in service-common library.

### 6. Validation Strategy

**Bean Validation (Controller Layer):**
```java
@PostMapping
public ExchangeRateResponse create(@Valid @RequestBody ExchangeRateRequest request) {
    // Bean validation automatically applied
}
```

**Business Validation (Service Layer):**
```java
@Service
public class ExchangeRateService {
    public ExchangeRate create(ExchangeRate rate) {
        // Business rules
        validateDateRange(rate);
        validateRateValue(rate);
        checkDuplicates(rate);

        return exchangeRateRepository.save(rate);
    }
}
```

### 7. Code Quality Standards

**Spotless Configuration:**
- Google Java Format (1.17.0)
- Automatic import ordering: java → javax → jakarta → org → com → com.bleurubin
- Trailing whitespace removal
- File ends with newline
- Unused import removal

**Checkstyle Enforcement:**
- Version 12.0.1
- Custom rules in `config/checkstyle/checkstyle.xml`
- Enforces Hibernate import ban
- Enforces naming conventions

**Build Commands:**
```bash
# Format code
./gradlew spotlessApply

# Check formatting
./gradlew spotlessCheck

# Run checkstyle
./gradlew checkstyleMain

# Run all checks
./gradlew check
```

## Service Features

### Exchange Rate Management

- Query exchange rates by currency pair and date range
- Automated import from FRED (Federal Reserve Economic Data)
- Scheduled background imports
- Historical rate storage and retrieval
- JPA Specification-based dynamic queries
- Redis-based distributed caching for high performance

### Caching Strategy

**Implementation: Redis Distributed Cache**

The service uses Redis for distributed caching to provide:
- **High performance**: 50-200x faster than database queries (1-3ms vs 50-200ms)
- **Immediate consistency**: All application instances share the same cache
- **Automatic invalidation**: Cache cleared when new rates are imported

**Cache Configuration:**
```yaml
Cache Name: exchangeRates
TTL: 1 hour (configurable)
Key Format: {targetCurrency}:{startDate}:{endDate}
Serialization: JSON (human-readable for debugging)
Key Prefix: currency-service: (namespace isolation)
```

**Caching Points:**
1. **Query Results** (`ExchangeRateService.getExchangeRates()`):
   - Caches entire result list for specific currency/date range queries
   - Cache key includes all query parameters for uniqueness
   - Automatically populated on cache miss

2. **Cache Invalidation** (`ExchangeRateImportService.importLatestExchangeRates()`):
   - All cache entries evicted after successful import
   - Ensures immediate consistency across all pods
   - Next query repopulates cache with fresh data

**Why Redis (not Caffeine/local cache)?**
- **Consistency requirement**: Financial data requires immediate consistency after import
- **Multi-instance deployment**: Shared cache eliminates stale data across pods
- **Proven reliability**: Industry-standard solution for distributed caching
- **Operational simplicity**: No pub/sub or cache synchronization needed

**Performance Characteristics:**
- Cache hit rate: Expected 80-95% for common currency pairs
- Response time (cache hit): 1-3ms
- Response time (cache miss): 50-200ms
- Memory usage: ~500MB for 10,000 cached queries

**Configuration Files:**
- Cache logic: [CacheConfig.java](src/main/java/com/bleurubin/budgetanalyzer/currency/config/CacheConfig.java)
- Redis connection: [application.yml](src/main/resources/application.yml)
- Service annotations: `@Cacheable`, `@CacheEvict` in service classes

### External Integrations

**FRED API Integration:**
- Non-blocking WebClient implementation
- Configurable retry logic
- Comprehensive error handling
- Rate limiting awareness

**Future Integrations:**
- Additional exchange rate providers
- Real-time currency data streams
- Historical data backfill capabilities

## Configuration

### Application Properties

Configuration is externalized via `application.yml` and bound to type-safe `@ConfigurationProperties` classes:

```java
@ConfigurationProperties(prefix = "currency-service")
public class CurrencyServiceProperties {
    private FredConfig fred;
    private SchedulerConfig scheduler;
    // ... getters/setters
}
```

### Environment-Specific Configuration

- Development: `application.yml`
- Testing: `application-test.yml`
- Production: Environment variables or external config server

## Database Schema

### Design Principles

- Pure JPA entity definitions
- Database-agnostic SQL where possible
- Flyway migrations for schema versioning (future)
- Proper indexing for query performance
- Foreign key constraints for data integrity

### Transaction Management

- `@Transactional` at service layer only
- Read-only transactions for queries
- Proper isolation levels for critical operations
- Rollback on runtime exceptions

## Testing Strategy

### Unit Tests

- Service layer: Mock repositories, test business logic
- Repository layer: Use `@DataJpaTest` with H2
- Controller layer: Use `@WebMvcTest`, mock services

### Integration Tests

- End-to-end API tests with TestRestTemplate
- Database integration with test containers (future)
- External API mocking with WireMock (future)

### Test Coverage Goals

- Minimum 80% code coverage
- 100% coverage for critical business logic
- All edge cases explicitly tested

## API Documentation

### OpenAPI/Swagger

- Accessible at `/swagger-ui.html` (development)
- Automatic generation from annotations
- Comprehensive endpoint documentation
- Example request/response payloads

### API Versioning

- Current version: v1 (implicit)
- Future versions: URL-based (`/api/v2/...`)
- Backward compatibility maintained for 2 major versions

## Deployment

### Docker

```dockerfile
# Multi-stage build
FROM eclipse-temurin:24-jdk as builder
WORKDIR /app
COPY . .
RUN ./gradlew bootJar

FROM eclipse-temurin:24-jre
COPY --from=builder /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### Kubernetes

- Deployment manifests in orchestration repository
- Health checks: `/actuator/health`
- Readiness probes: `/actuator/health/readiness`
- Liveness probes: `/actuator/health/liveness`

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
- `REDIS_PASSWORD`: Redis password (optional, leave empty for no auth)

**External APIs:**
- `FRED_API_KEY`: FRED API authentication key

## Service Dependencies

### Required Services

- **PostgreSQL** database (production)
- **Redis** cache (production)

### Optional Integrations

- NGINX API Gateway (for routing)
- Prometheus (metrics)
- Zipkin/Jaeger (distributed tracing)

## Development Workflow

### Local Development

1. **Prerequisites:**
   - JDK 24
   - PostgreSQL 15+
   - Gradle 8.11+

2. **Start Services:**
   ```bash
   cd ../budget-analyzer
   docker compose up
   ```
   This starts PostgreSQL and Redis shared across all microservices. Each service has its own predefined database.

3. **Run Application:**
   ```bash
   ./gradlew bootRun
   ```

4. **Access Swagger UI:**
   http://localhost:8084/swagger-ui.html

### Code Formatting

**Before committing:**
```bash
./gradlew spotlessApply
./gradlew check
```

### Git Workflow

- Create feature branches from `main`
- Follow conventional commits
- Run all checks before pushing
- Request code review for all changes

## Best Practices

### General

1. **Follow SOLID principles**: Single Responsibility, Open/Closed, Liskov Substitution, Interface Segregation, Dependency Inversion
2. **Favor composition over inheritance**
3. **Program to interfaces, not implementations**
4. **Use dependency injection** for all dependencies
5. **Avoid static methods** except for pure utility functions
6. **Immutability**: Use final fields where possible
7. **Null safety**: Use Optional for potentially null returns

### Spring Boot Specific

1. **Use constructor injection** over field injection
2. **Avoid @Autowired on fields** - use constructor injection
3. **Keep controllers thin** - delegate to services
4. **Use @Transactional only at service layer**
5. **Leverage Spring Boot starters** for consistent configuration
6. **Use @ConfigurationProperties** for type-safe configuration
7. **Implement proper health checks** via Actuator

### Database

1. **Always use JPA specifications** for dynamic queries
2. **Avoid N+1 queries** - use JOIN FETCH appropriately
3. **Index foreign keys** and frequently queried columns
4. **Use optimistic locking** (`@Version`) where appropriate
5. **Never expose entities directly** in API responses
6. **Use projections** for read-heavy operations

### Security

1. **Validate all inputs** at controller layer
2. **Sanitize user-provided data**
3. **Never log sensitive information**
4. **Use HTTPS** in production
5. **Implement rate limiting** for public endpoints
6. **Audit sensitive operations**

### Performance

1. **Use pagination** for list endpoints
2. **Implement caching** where appropriate
3. **Use async processing** for long-running operations
4. **Monitor query performance**
5. **Use connection pooling** (HikariCP default)
6. **Implement circuit breakers** for external calls

## Common Tasks

### Adding a New Entity

1. Create entity class in `domain/` package
2. Create repository interface in `repository/`
3. Create service class in `service/`
4. Create controller in `api/`
5. Create request/response DTOs in `api/response/`
6. Create mapper interface (MapStruct - future)
7. Write unit tests for all layers
8. Update OpenAPI documentation

### Adding a New External API Client

1. Create client package under `client/`
2. Create response DTOs in client package
3. Configure WebClient in `config/`
4. Implement error handling and retries
5. Add circuit breaker (future)
6. Write integration tests
7. Document configuration properties

### Adding a Scheduled Job

1. Create scheduler class in `scheduler/`
2. Use `@Scheduled` annotation
3. Configure scheduling properties
4. Implement idempotent processing
5. Add monitoring/logging
6. Handle distributed locking (future)

## Troubleshooting

### Common Issues

**Application won't start:**
- Check database connectivity
- Verify all required environment variables set
- Review application logs for stack traces

**Database connection errors:**
- Verify PostgreSQL is running
- Check credentials and host/port
- Ensure database exists

**Build failures:**
- Run `./gradlew clean build`
- Check for Spotless formatting violations
- Review Checkstyle errors

**Tests failing:**
- Clear test database: `./gradlew cleanTest test`
- Check for port conflicts
- Review test logs

## Notes for Claude Code

When working on this project:

### Critical Rules

1. **NEVER implement changes without explicit permission** - Always present a plan and wait for approval
2. **Distinguish between informational statements and action requests** - If the user says "I did X", they're informing you, not asking you to do it
3. **Questions deserve answers, not implementations** - Respond to questions with information, not code changes
4. **Wait for explicit implementation requests** - Only implement when the user says "implement", "do it", "make this change", or similar action-oriented language

### Code Quality

- **All code must be production-quality** - No shortcuts, prototypes, or workarounds
- **Follow service layer architecture** - Services accept/return entities, not API DTOs
- **Use pure JPA only** - No Hibernate-specific imports or annotations
- **Maintain package separation** - Clear boundaries between api, service, repository, domain
- **Run Spotless before committing** - `./gradlew spotlessApply`
- **Ensure all checks pass** - `./gradlew check`

### Architecture Conventions

- Controllers: Thin, HTTP-focused, delegate to services
- Services: Business logic, validation, transactions
- Repositories: Data access only
- Domain entities: Pure JPA, no business logic
- API DTOs: In `api/response` package only, never used by services

### Testing Requirements

- Write tests for all new features
- Maintain minimum 80% coverage
- Test edge cases explicitly
- Use proper test doubles (mocks, stubs, fakes)

### Documentation

- Update this file when architecture changes
- Add JavaDoc for public APIs
- Document complex business logic
- Keep OpenAPI annotations current

## Future Enhancements

- [ ] Implement MapStruct for DTO mapping
- [ ] Add Flyway for database migrations
- [ ] Implement Redis caching layer
- [ ] Add circuit breakers (Resilience4j)
- [ ] Implement distributed tracing
- [ ] Add Prometheus metrics
- [ ] Implement API rate limiting
- [ ] Add GraphQL endpoint (optional)
- [ ] Implement event publishing (Kafka/RabbitMQ)
- [ ] Add comprehensive integration tests
- [ ] Implement test containers for integration tests
- [ ] Add WireMock for external API testing
- [ ] Implement distributed locking (Redis/Hazelcast)
- [ ] Add API versioning strategy
- [ ] Implement request/response logging filter
- [ ] Add audit logging for data changes

## Related Documentation

- [Persistence Layer Architecture](../budget-analyzer/docs/persistence-layer-architecture.md)
- [Service Layer Architecture](../budget-analyzer/docs/service-layer-architecture.md)
- [Budget Analyzer Orchestration](../budget-analyzer/CLAUDE.md)

## Support and Contact

For questions or issues:
- Review this documentation first
- Check existing GitHub issues
- Create new issue with detailed description and reproduction steps
