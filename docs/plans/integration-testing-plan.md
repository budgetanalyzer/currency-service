# Comprehensive Integration Testing Plan for Currency Service

## Overview
Transform currency-service from minimal test coverage (1 smoke test) to comprehensive integration testing using Spring Boot 3.5.6 + TestContainers with 2025 best practices.

## Phase 1: Test Infrastructure Setup
**Goal**: Establish foundational testing framework with TestContainers

### Step 1.1: Add Testing Dependencies
- Add TestContainers (PostgreSQL, Redis, RabbitMQ) v1.20.4+
- Add WireMock v3.9.2+ for FRED API mocking
- Add Spring Modulith Test support
- Add Awaitility v4.2.2+ for async testing
- Remove H2 dependency (replace with real PostgreSQL)

### Step 1.2: Create Base Test Classes
- `AbstractIntegrationTest`: Base class with TestContainers (PostgreSQL, Redis, RabbitMQ)
- `AbstractControllerTest`: Extends AbstractIntegrationTest + MockMvc + WireMock
- `AbstractRepositoryTest`: DataJpaTest with PostgreSQL container
- Configure `@ServiceConnection` for automatic Spring Boot 3.1+ container discovery

### Step 1.3: Create Test Fixtures & Builders
- `CurrencySeriesTestBuilder`: Fluent builder for test data
- `ExchangeRateTestBuilder`: Fluent builder for test data
- `FredApiStubs`: WireMock response templates for common scenarios
- Test data constants (valid/invalid ISO codes, dates, rates)

## Phase 2: Repository Layer Tests (15-20 tests)
**Goal**: Validate JPA queries, constraints, and database operations

### Step 2.1: CurrencySeriesRepository Tests
- Test `findByEnabledTrue()` with mixed enabled/disabled records
- Test `findByCurrencyCode()` query
- Test unique constraint on `currencyCode`
- Test cascade delete behavior with exchange rates
- Test audit timestamp population

### Step 2.2: ExchangeRateRepository Tests
- Test `findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc()`
- Test `findEarliestDateByTargetCurrency()` with multiple series
- Test `countByCurrencySeries()` aggregation
- Test JpaSpecificationExecutor with complex filters
- Test unique constraint on `(currencySeries, date)`

## Phase 3: Service Layer Tests (40-50 tests)
**Goal**: Test business logic, validation, transactions, and event publishing

### Step 3.1: CurrencySeriesService Tests
- **Happy Paths**: Create/update/query currency series
- **Validation**: ISO 4217 validation, duplicate detection
- **FRED Integration**: Mock provider to validate series existence
- **Events**: Verify CurrencyCreatedEvent and CurrencyUpdatedEvent published
- **Error Cases**: Invalid currency codes, non-existent provider series

### Step 3.2: ExchangeRateService Tests
- **Query Operations**: Date range filtering, enabled series only
- **Gap-Filling Algorithm**: Forward-fill missing dates, weekend handling
- **Caching**: Verify cache hits/misses with Redis TestContainer
- **Validation**: Start > end date, no data available scenarios
- **Complex Queries**: Multiple currencies, overlapping date ranges

### Step 3.3: ExchangeRateImportService Tests
- **Import Modes**: Missing data, latest data, specific series
- **Deduplication**: Skip existing dates, update modified rates
- **Cache Eviction**: Verify `@CacheEvict(allEntries=true)` clears Redis
- **FRED Integration**: Mock successful/failed API responses
- **Transaction Rollback**: Verify partial imports rollback on error
- **Retry Logic**: Test retry mechanism with transient failures

## Phase 4: Controller Layer Tests (30-40 tests)
**Goal**: Validate REST API contracts, JSON serialization, HTTP status codes

### Step 4.1: CurrencySeriesController Tests (MockMvc)
- `GET /v1/currencies?enabledOnly=true` - Returns only enabled series
- `GET /v1/currencies?enabledOnly=false` - Returns all series
- Test JSON response structure matches DTOs
- Test pagination (if implemented)

### Step 4.2: AdminCurrencySeriesController Tests
- `POST /v1/admin/currencies` - Success (201 Created)
- `POST /v1/admin/currencies` - Validation errors (400 Bad Request)
- `POST /v1/admin/currencies` - Business errors (422 Unprocessable Entity)
- `GET /v1/admin/currencies/{id}` - Success (200) and Not Found (404)
- `PUT /v1/admin/currencies/{id}` - Enable/disable currency series
- Test ISO 4217 validation in request body
- Test provider series ID validation via mocked FRED

### Step 4.3: ExchangeRateController Tests
- `GET /v1/exchange-rates?targetCurrency=EUR&startDate=2024-01-01&endDate=2024-12-31` - Success
- Test missing required parameters (400)
- Test invalid date formats (400)
- Test start > end date validation (400)
- Test gap-filled response structure
- Test caching headers (if implemented)

### Step 4.4: AdminExchangeRateController Tests
- `POST /v1/admin/exchange-rates/import` - Manual trigger
- Test import status response
- Test concurrent import prevention
- Test error responses (500 on FRED failure)

## Phase 5: External Integration Tests (15-20 tests)
**Goal**: Test FRED API client with realistic mock scenarios

### Step 5.1: FredClient Tests (WireMock)
- **Success Scenarios**: Valid series with observations, empty observations
- **Error Scenarios**: 400 Bad Request, 404 Not Found, 500 Server Error
- **Network Issues**: Timeouts, connection refused
- **Malformed Responses**: Invalid JSON, missing required fields
- **Rate Limiting**: 429 Too Many Requests handling
- **Large Datasets**: Paginated responses (if applicable)

### Step 5.2: FredExchangeRateProvider Tests
- Test `getExchangeRates()` transformation from FRED format to domain
- Test `validateSeriesExists()` with valid/invalid series IDs
- Test error translation from FredClient exceptions to business exceptions
- Test date parsing and timezone handling

## Phase 6: Advanced Patterns Tests (30-40 tests)
**Goal**: Test distributed systems patterns (caching, messaging, locking)

### Step 6.1: Redis Caching Tests
- **Cache Population**: First query populates cache
- **Cache Hits**: Subsequent queries return cached data (verify no DB query)
- **Cache Keys**: Verify composite key format `{currency}:{start}:{end}`
- **Cache Eviction**: Import operation clears all cache entries
- **Multi-Currency**: Verify cache isolation between currencies
- **Cache Serialization**: Verify DTOs serialize/deserialize correctly

### Step 6.2: Event-Driven Messaging Tests (Spring Modulith)
- **Event Publishing**: Verify events persisted to `event_publication` table
- **Event Processing**: Verify MessagingEventListener publishes to RabbitMQ
- **Consumer Execution**: Verify ExchangeRateImportConsumer triggered on CurrencyCreatedEvent
- **Async Processing**: Use Awaitility to verify eventual consistency
- **Transaction Boundaries**: Verify events commit with entity saves
- **Retry Logic**: Verify failed event processing retries
- **Dead Letter Queue**: Verify poison messages route to DLQ

### Step 6.3: Scheduled Jobs Tests (ShedLock)
- **Scheduled Execution**: Verify ExchangeRateImportScheduler runs on schedule
- **Lock Acquisition**: Verify ShedLock prevents concurrent execution
- **Lock Duration**: Verify `lockAtMostFor` and `lockAtLeastFor` settings
- **Retry Mechanism**: Verify configurable retry attempts and delays
- **Failure Recovery**: Verify lock released on exception
- **Multi-Instance**: Simulate multiple pods and verify single execution

## Phase 7: End-to-End Integration Tests (10-15 tests)
**Goal**: Test complete user workflows across all layers

### Step 7.1: Complete Workflows
- **Create Currency → Auto Import Flow**:
  1. POST currency series
  2. Verify CurrencyCreatedEvent published
  3. Verify import triggered via messaging
  4. Verify exchange rates imported from FRED
  5. Verify cache populated
  6. Query exchange rates and verify gap-filling

- **Manual Import Flow**:
  1. POST import request
  2. Verify FRED API called with WireMock
  3. Verify exchange rates persisted
  4. Verify cache evicted
  5. Query and verify fresh data

- **Update Currency Series Flow**:
  1. PUT to disable series
  2. Verify CurrencyUpdatedEvent published
  3. Query exchange rates and verify series excluded

## Phase 8: Performance & Edge Cases (10-15 tests)
**Goal**: Test non-functional requirements and edge cases

### Step 8.1: Performance Tests
- Large dataset queries (10,000+ exchange rates)
- Cache performance (verify <5ms response time on cache hit)
- Concurrent request handling (100+ parallel requests)
- Bulk import performance (1 year of daily data)

### Step 8.2: Edge Cases
- Leap year date handling (Feb 29)
- Timezone boundaries (UTC vs local time)
- Currency code case sensitivity (EUR vs eur)
- Extreme dates (year 1900, year 2100)
- Special characters in provider series IDs
- Concurrent updates to same currency series
- Race conditions in cache eviction

## Implementation Guidelines

### Modern Spring Boot 2025 Best Practices
1. **Use `@ServiceConnection`** for TestContainers (Spring Boot 3.1+)
2. **Use `@TestConfiguration`** for test-specific beans
3. **Use `@DynamicPropertySource`** for container URLs (if needed)
4. **Use `@Sql`** for test data setup when appropriate
5. **Use RestAssured or MockMvc** (MockMvc recommended for controller tests)
6. **Use `@Transactional(propagation = NOT_SUPPORTED)`** for read-only integration tests
7. **Use AssertJ** for fluent assertions
8. **Use `@ParameterizedTest`** for multiple scenarios
9. **Use TestContainers reuse mode** for faster test execution
10. **Use Spring Modulith `@ApplicationModuleTest`** for module testing

### Test Organization
```
src/test/java/org/budgetanalyzer/currency/
├── base/
│   ├── AbstractIntegrationTest.java
│   ├── AbstractControllerTest.java
│   └── AbstractRepositoryTest.java
├── fixture/
│   ├── CurrencySeriesTestBuilder.java
│   ├── ExchangeRateTestBuilder.java
│   └── FredApiStubs.java
├── repository/
│   ├── CurrencySeriesRepositoryTest.java
│   └── ExchangeRateRepositoryTest.java
├── service/
│   ├── CurrencySeriesServiceTest.java
│   ├── ExchangeRateServiceTest.java
│   └── ExchangeRateImportServiceTest.java
├── api/
│   ├── CurrencySeriesControllerTest.java
│   ├── AdminCurrencySeriesControllerTest.java
│   ├── ExchangeRateControllerTest.java
│   └── AdminExchangeRateControllerTest.java
├── client/
│   ├── FredClientTest.java
│   └── FredExchangeRateProviderTest.java
├── integration/
│   ├── CachingIntegrationTest.java
│   ├── MessagingIntegrationTest.java
│   ├── SchedulingIntegrationTest.java
│   └── EndToEndIntegrationTest.java
└── CurrencyServiceApplicationTests.java (existing smoke test)
```

### Estimated Effort
- **Total Test Cases**: 150-200
- **Estimated Development Time**: 3-5 days (per phase)
- **Priority**: Phases 1-4 are critical, Phases 5-8 are nice-to-have

### Success Criteria
- **Code Coverage**: 80%+ line coverage, 70%+ branch coverage
- **Test Execution Time**: <5 minutes for full suite
- **Zero Flaky Tests**: All tests deterministic and repeatable
- **Documentation**: Each test class has JavaDoc explaining scope

---

## Execution Strategy

Implement phases sequentially, as each builds on previous infrastructure. Tackle one phase per context window as planned:

1. **Phase 1**: Foundation (dependencies, base classes, fixtures)
2. **Phase 2**: Repository tests (database layer)
3. **Phase 3**: Service tests (business logic)
4. **Phase 4**: Controller tests (API contracts)
5. **Phase 5**: External integration (FRED API)
6. **Phase 6**: Advanced patterns (caching, messaging, scheduling)
7. **Phase 7**: End-to-end workflows
8. **Phase 8**: Performance and edge cases

Each phase can be implemented independently in a fresh context window, referring back to this master plan.
