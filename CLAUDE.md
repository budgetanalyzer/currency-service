# Currency Service - Spring Boot Microservice

## Project Overview

The Currency Service is a production-grade Spring Boot microservice responsible for managing currencies and exchange rates within the Budget Analyzer application ecosystem. It provides RESTful APIs for currency operations and exchange rate queries, with automated import capabilities from external data sources.

## Architecture

### Technology Stack

- **Framework**: Spring Boot 3.5.6
- **Language**: Java 24
- **Database**: PostgreSQL (production), H2 (testing)
- **Cache**: Redis (distributed caching)
- **Messaging**: Spring Cloud Stream with RabbitMQ (event-driven architecture)
- **API Documentation**: SpringDoc OpenAPI 3
- **Build Tool**: Gradle (Kotlin DSL)
- **Code Quality**: Spotless (Google Java Format), Checkstyle

### Package Structure

The service follows a clean, layered architecture with clear separation of concerns:

- **api/**: REST controllers and API-specific request/response DTOs only
- **domain/**: JPA entities and domain events
  - **domain/event/**: Domain events (internal business facts)
- **service/**: Business logic, orchestration, validation
  - **service/dto/**: Internal data transfer objects (NOT API contracts)
  - **service/provider/**: Provider abstraction interfaces and implementations
- **repository/**: Data access layer (Spring Data JPA)
- **client/**: External API integrations
- **config/**: Spring configuration classes
- **scheduler/**: Scheduled background tasks
- **messaging/**: Event-driven messaging components
  - **messaging/message/**: Message payload classes (external contracts)
  - **messaging/publisher/**: Message publishers (encapsulate StreamBridge)
  - **messaging/listener/**: Event listeners (bridge domain events to messages)
  - **messaging/consumer/**: Message consumers (functional bean pattern)

**Package Dependency Rules:**
```
api → service (NEVER repository)
service → repository
service → domain (entities AND events)
service → client
service → ApplicationEventPublisher (can publish domain events)
service/provider → client
messaging/listener → domain/event (listens to domain events)
messaging/listener → messaging/publisher (publishes external messages)
messaging/publisher → messaging/message (publishes messages)
messaging/consumer → service (delegates to services)
repository → domain
```

**Critical Boundaries:**
- API classes should NEVER be imported by service layer
- **Controllers should NEVER import repositories** - all repository access must go through services
- **Messaging consumers should NEVER import repositories** - all repository access via services
- **Service layer publishes messages** via publisher abstraction (not StreamBridge directly)

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

**CRITICAL RULE**: Controllers NEVER import repositories. All repository access is encapsulated in the service layer.

**Controller Responsibilities:**
- Handle HTTP concerns (status codes, headers)
- Map API DTOs to/from domain entities
- Delegate ALL business operations to services (including entity retrieval by ID)
- Handle controller-level exceptions (ResourceNotFoundException, InvalidRequestException)

**Service Responsibilities:**
- Execute business logic
- Perform business validation
- Manage transactions
- Coordinate between repositories
- Retrieve entities by ID from repositories
- Publish domain events

**Example Pattern:**
```java
@RestController
@RequestMapping("/v1/exchange-rates")
public class ExchangeRateController {

  @Autowired private ExchangeRateMapper mapper;
  @Autowired private ExchangeRateService exchangeRateService;

  @PutMapping("/{id}")
  public ExchangeRateResponse update(@PathVariable Long id,
                                      @RequestBody ExchangeRateRequest request) {
    // 1. Map request to entity (for updates)
    var updates = mapper.fromRequest(request);

    // 2. Delegate to service (service retrieves entity by ID internally)
    var updated = exchangeRateService.update(id, updates);

    // 3. Map entity to response
    return mapper.toResponse(updated);
  }

  @GetMapping("/{id}")
  public ExchangeRateResponse getById(@PathVariable Long id) {
    // Service handles entity retrieval and throws ResourceNotFoundException if not found
    var exchangeRate = exchangeRateService.getById(id);
    return mapper.toResponse(exchangeRate);
  }
}
```

**Service Pattern:**
```java
@Service
public class ExchangeRateService {

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  public ExchangeRate getById(Long id) {
    return exchangeRateRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Exchange rate not found: " + id));
  }

  @Transactional
  public ExchangeRate update(Long id, ExchangeRate updates) {
    var entity = getById(id);  // Reuse getById for entity retrieval
    // Apply updates
    entity.setRate(updates.getRate());
    // Business validation
    validateExchangeRate(entity);
    return exchangeRateRepository.save(entity);
  }
}
```

**Controllers ONLY:**
- Map API DTOs to/from domain entities
- Delegate to service methods
- Throw `InvalidRequestException` for request format validation (e.g., startDate > endDate)
- Bean Validation annotations (`@Valid`, `@NotBlank`, etc.) automatically return 400 Bad Request

**Controllers NEVER:**
- Import or use repositories directly
- Perform complex business logic or validation
- Access the database
- Throw `BusinessException` (service-layer exception)

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

### 4. Provider Abstraction Pattern

**RULE**: External data source integrations MUST use the provider abstraction pattern. Provider-specific implementations (e.g., FRED) should NEVER be referenced outside their implementation classes.

**Architecture:**
```
service/ → service/provider/ExchangeRateProvider (interface)
         → service/dto/ (internal DTOs)
service/provider/FredExchangeRateProvider (impl) → client/fred/FredClient
```

**Provider Interface:** [ExchangeRateProvider.java](src/main/java/com/bleurubin/budgetanalyzer/currency/service/provider/ExchangeRateProvider.java)
```java
public interface ExchangeRateProvider {
  Map<LocalDate, BigDecimal> getExchangeRates(CurrencySeries currencySeries, LocalDate startDate);
  boolean validateSeriesExists(String providerSeriesId);
}
```

**Dependency Rules:**
- ✅ `CurrencyService` → `ExchangeRateProvider` (interface)
- ✅ `ExchangeRateImportService` → `ExchangeRateProvider` (interface)
- ✅ `FredExchangeRateProvider` → `FredClient`
- ❌ `CurrencyService` → `FredClient` (direct dependency - FORBIDDEN)
- ❌ `CurrencyService` → `FredExchangeRateProvider` (concrete implementation - FORBIDDEN)

**Why?**
- **Extensibility**: Adding new providers (ECB, Bloomberg, etc.) requires no changes to service layer
- **Testability**: Services can be tested with mock providers without FRED API dependency
- **Encapsulation**: Provider-specific logic (API keys, error codes, rate limits) contained in implementation
- **Substitutability**: Can switch providers at runtime via configuration/feature flags

**Example - CORRECT:**
```java
@Service
public class CurrencyService {
  private final ExchangeRateProvider exchangeRateProvider;  // Interface dependency

  public CurrencySeries create(CurrencySeries currencySeries) {
    validateProviderSeriesId(currencySeries.getProviderSeriesId());
    // ...
  }

  private void validateProviderSeriesId(String providerSeriesId) {
    boolean exists = exchangeRateProvider.validateSeriesExists(providerSeriesId);  // Provider-agnostic
    // ...
  }
}
```

**Example - WRONG:**
```java
@Service
public class CurrencyService {
  private final FredClient fredClient;  // ❌ Direct dependency on FRED

  private void validateProviderSeriesId(String providerSeriesId) {
    boolean exists = fredClient.seriesExists(providerSeriesId);  // ❌ FRED-specific
    // ...
  }
}
```

**Key Points:**
- The word "FRED" should NEVER appear in service layer code
- Service layer uses generic terminology: "provider", "provider series ID", "external provider"
- Only `FredExchangeRateProvider` and `FredClient` contain FRED-specific logic
- Error messages use "external provider" not "FRED"

### 5. Event-Driven Messaging with Transactional Outbox Pattern

**RULE**: Use Spring Modulith's transactional outbox pattern for guaranteed message delivery. Services publish domain events that are automatically persisted in the database, then asynchronously processed and published to external messaging systems.

**Architecture:**
```
service/ → domain/event/CurrencyCreatedEvent (domain event)
         → messaging/listener/MessagingEventListener (bridges events to messages)
         → messaging/publisher/CurrencyMessagePublisher (abstraction)
         → messaging/message/CurrencyCreatedMessage (external payload)
messaging/consumer/ExchangeRateImportConsumer → service/ExchangeRateImportService
```

**The Problem We're Solving:**

Traditional message publishing has the "dual-write problem":
```java
@Transactional
public void create(Currency currency) {
    repository.save(currency);           // Write #1: Database
    messagePublisher.publish(message);   // Write #2: RabbitMQ
    // What if app crashes between these two writes?
    // What if RabbitMQ is down but database succeeded?
    // Result: Data saved but no message sent = lost event!
}
```

**The Solution: Transactional Outbox Pattern**

Spring Modulith automatically stores events in the database **within the same transaction** as your business data:
```java
@Transactional
public void create(Currency currency) {
    var saved = repository.save(currency);           // Write #1: Database
    eventPublisher.publishEvent(new Event(...));     // Write #2: Database (same transaction!)
    // Spring Modulith stores event in event_publication table
    // Both writes succeed or both fail - atomic guarantee!
}
// After transaction commits, Spring Modulith asynchronously publishes to RabbitMQ
```

**Components:**

1. **Domain Events** (`domain/event/`): Internal events representing business facts
   ```java
   /**
    * Domain event published when a new currency series is created.
    * Spring Modulith automatically persists this to event_publication table.
    */
   public record CurrencyCreatedEvent(
       Long currencySeriesId,
       String currencyCode,
       String correlationId
   ) {}
   ```

2. **Service Layer** (`service/`): Publishes domain events within transactions
   ```java
   @Service
   public class CurrencyService {
       private final ApplicationEventPublisher eventPublisher;

       @Transactional
       public CurrencySeries create(CurrencySeries currencySeries) {
           // Save entity to database
           var saved = repository.save(currencySeries);

           // Publish domain event - Spring Modulith persists to event_publication table
           // in the SAME transaction as the entity save
           var correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
           eventPublisher.publishEvent(
               new CurrencyCreatedEvent(saved.getId(), saved.getCurrencyCode(), correlationId)
           );

           return saved;  // Transaction commits - both entity and event saved atomically!
       }
   }
   ```

3. **Event Listeners** (`messaging/listener/`): Bridge domain events to external messages
   ```java
   @Component
   public class MessagingEventListener {
       private final CurrencyMessagePublisher messagePublisher;

       /**
        * @ApplicationModuleListener enables transactional outbox pattern:
        * - Event already persisted to database before this method is called
        * - Runs asynchronously on background thread after transaction commits
        * - Automatically retries on failure until successful
        * - Event marked completed in database after successful processing
        */
       @ApplicationModuleListener
       void onCurrencyCreated(CurrencyCreatedEvent event) {
           MDC.put("correlationId", event.correlationId());
           MDC.put("eventType", "currency_created");
           try {
               // Publish to RabbitMQ - if this fails, Spring Modulith will retry
               messagePublisher.publishCurrencyCreated(
                   new CurrencyCreatedMessage(
                       event.currencySeriesId(),
                       event.currencyCode(),
                       event.correlationId()
                   )
               );
           } finally {
               MDC.clear();
           }
       }
   }
   ```

4. **Message Publishers** (`messaging/publisher/`): Thin wrappers around StreamBridge
   ```java
   @Component
   public class CurrencyMessagePublisher {
       private final StreamBridge streamBridge;

       public void publishCurrencyCreated(CurrencyCreatedMessage message) {
           streamBridge.send("currencyCreated-out-0", message);
       }
   }
   ```

5. **Message Classes** (`messaging/message/`): External message contracts
   ```java
   public record CurrencyCreatedMessage(
       Long currencySeriesId,
       String currencyCode,
       String correlationId
   ) {}
   ```

6. **Consumers** (`messaging/consumer/`): Functional beans that delegate to services
   ```java
   @Configuration
   public class ExchangeRateImportConsumer {
       private final ExchangeRateImportService service;

       @Bean
       public Consumer<CurrencyCreatedMessage> importExchangeRates() {
           return message -> {
               MDC.put("correlationId", message.correlationId());
               MDC.put("eventType", "currency_created");
               try {
                   service.importExchangeRatesForSeries(message.currencySeriesId());
               } finally {
                   MDC.clear();  // Always clean up MDC
               }
               // Exceptions propagate to Spring Cloud Stream retry mechanism
           };
       }
   }
   ```

**CRITICAL: Message Consumer Error Handling**

Message consumers **MUST NOT** catch and swallow exceptions. Spring Cloud Stream's retry and DLQ mechanisms depend on exceptions propagating from the consumer.

**WRONG - Breaks retry mechanism:**
```java
@Bean
public Consumer<CurrencyCreatedMessage> importExchangeRates() {
    return message -> {
        try {
            service.importExchangeRatesForSeries(message.currencySeriesId());
        } catch (Exception e) {
            log.error("Failed to import", e);
            // ❌ Exception swallowed - Spring Cloud Stream thinks it succeeded!
            // Result: No retry, message acknowledged and removed from queue
        }
    };
}
```

**CORRECT - Allows retry mechanism:**
```java
@Bean
public Consumer<CurrencyCreatedMessage> importExchangeRates() {
    return message -> {
        MDC.put("correlationId", message.correlationId());
        try {
            service.importExchangeRatesForSeries(message.currencySeriesId());
        } finally {
            MDC.clear();  // ✅ Always clean up MDC, even on exception
        }
        // ✅ Exception propagates naturally to Spring Cloud Stream
    };
}
```

**How Spring Cloud Stream Retry Works:**

1. **Success**: Consumer returns normally → Message acknowledged, removed from queue
2. **Failure**: Consumer throws exception → Spring Cloud Stream retry logic triggers
3. **After max retries**: Message published to Dead Letter Queue (DLQ) for manual investigation

**Retry Configuration** (application.yml):
```yaml
spring.cloud.stream.rabbit.bindings.importExchangeRates-in-0.consumer:
  max-attempts: 3              # 1 initial + 2 retries
  initial-interval: 1000       # 1 second initial delay
  multiplier: 2.0              # Exponential backoff (1s, 2s, 4s)
  auto-bind-dlq: true          # Create DLQ automatically
  republish-to-dlq: true       # Preserve original message in DLQ
```

**Why the `finally` block?**

The `finally` block ensures MDC is cleaned up even when exceptions are thrown. Without it, MDC values would leak across messages in the thread pool (Spring Cloud Stream reuses threads).

**Key Points:**
- Use `finally` for cleanup (MDC, resources) - it always executes
- Never use `catch` unless rethrowing the exception
- Let exceptions propagate naturally to Spring Cloud Stream
- Spring Cloud Stream handles retry, backoff, and DLQ automatically

**Event Flow with Transactional Outbox:**

```
HTTP Request (Thread 1: nio-8084-exec-1)
│
├─> CurrencySeriesController.create()
│   └─> CurrencyService.create()
│       ├─> repository.save(currency)              [Transaction START]
│       ├─> eventPublisher.publishEvent(...)
│       │   └─> Spring Modulith intercepts
│       │       └─> INSERT INTO event_publication  [Same transaction!]
│       └─> return                                 [Transaction COMMIT - atomic!]
│
└─> HTTP 201 Created (request completes immediately)

Background (Thread 2: modulith-event-processor)
│
└─> Spring Modulith polls event_publication table
    ├─> SELECT * FROM event_publication WHERE completion_date IS NULL
    ├─> Found unpublished event
    ├─> Invokes @ApplicationModuleListener method
    │   └─> MessagingEventListener.onCurrencyCreated()
    │       └─> messagePublisher.publishCurrencyCreated()
    │           └─> StreamBridge.send() → RabbitMQ
    └─> UPDATE event_publication SET completion_date = NOW()
```

**Dependency Rules:**
- ✅ `CurrencyService` → `ApplicationEventPublisher` (Spring event publishing)
- ✅ `MessagingEventListener` → `CurrencyMessagePublisher` (bridges events to messages)
- ✅ `CurrencyMessagePublisher` → `StreamBridge` (Spring Cloud Stream)
- ✅ `ExchangeRateImportConsumer` → `ExchangeRateImportService` (delegates to service)
- ❌ `CurrencyService` → `CurrencyMessagePublisher` (direct message publishing - FORBIDDEN)
- ❌ `CurrencyService` → `StreamBridge` (bypass domain events - FORBIDDEN)
- ❌ `ExchangeRateImportConsumer` → `ExchangeRateRepository` (direct DB access - FORBIDDEN)

**Why This Pattern?**

1. **100% Guaranteed Delivery**
   - Events persisted in database within same transaction as business data
   - Survives application crashes, network failures, RabbitMQ downtime
   - No lost events - if entity exists in database, event will be published

2. **Exactly-Once Semantics**
   - Eliminates dual-write problem (database + message queue)
   - Atomic commit: both entity and event saved together or both fail
   - No partial states where entity exists but event wasn't published

3. **Async HTTP Responses**
   - HTTP request returns immediately after database commit
   - Client doesn't wait for RabbitMQ publishing (200ms vs 2ms response time)
   - Better user experience and higher throughput

4. **Automatic Retries**
   - Spring Modulith retries failed event processing until successful
   - No manual retry logic needed in application code
   - Failed events remain in database for troubleshooting

5. **Event Replay & Audit Trail**
   - All events stored in database with timestamps
   - Can manually replay events from database if needed
   - Full audit trail of all domain events for debugging

6. **Decoupled Architecture**
   - Service layer publishes domain events (internal concern)
   - Event listeners translate to external messages (infrastructure concern)
   - Easy to add new listeners without changing service layer

**Database Schema:**

Spring Modulith requires an `event_publication` table (created via Flyway migration):
```sql
CREATE TABLE event_publication (
    id UUID PRIMARY KEY,
    listener_id VARCHAR(512) NOT NULL,          -- Event listener method
    event_type VARCHAR(512) NOT NULL,           -- Domain event class
    serialized_event TEXT NOT NULL,             -- JSON event payload
    publication_date TIMESTAMP NOT NULL,        -- When event was created
    completion_date TIMESTAMP                   -- When successfully processed (NULL = pending)
);

-- Index for polling unpublished events
CREATE INDEX idx_event_publication_unpublished
    ON event_publication(completion_date)
    WHERE completion_date IS NULL;
```

**Configuration:**

Spring Modulith configuration in `application.yml`:
```yaml
spring:
  modulith:
    events:
      # Republish outstanding events on application restart
      republish-outstanding-events-on-restart: true
      # Retain completed events for audit trail (30 days)
      delete-completion-after: 30d

  cloud:
    function:
      definition: importExchangeRates  # Consumer bean name
    stream:
      bindings:
        currencyCreated-out-0:
          destination: currency.created
        importExchangeRates-in-0:
          destination: currency.created
          group: exchange-rate-import-service
      rabbit:
        bindings:
          importExchangeRates-in-0:
            consumer:
              auto-bind-dlq: true
              republish-to-dlq: true
              max-attempts: 3
              initial-interval: 1000
              multiplier: 2.0
```

**Error Handling:**

- **Event Listener Failures**: Spring Modulith catches exceptions and retries event processing. Event remains in database with NULL completion_date until successful.
- **Consumer Failures**: Spring Cloud Stream retry mechanism handles failures automatically. Failed messages sent to Dead Letter Queue after max attempts.
- **Database Failures**: Transaction rolls back - neither entity nor event is saved (atomic guarantee).

**Distributed Tracing with MDC:**

Correlation ID flows through entire pipeline:
```
HTTP Request
  └─> CorrelationIdFilter sets MDC["correlationId"] = UUID
      └─> CurrencyService reads MDC, includes in domain event
          └─> MessagingEventListener sets MDC from event.correlationId()
              └─> External message includes correlationId
                  └─> Consumer sets MDC from message.correlationId()
```

All logs from HTTP request → service → event listener → external consumer include the same correlation ID for end-to-end tracing.

**Key Points:**
- Domain events ≠ External messages (conceptually different, structurally similar)
- Events are internal facts ("currency was created"), messages are external contracts ("notify other services")
- Domain events belong in `domain/event/` package (part of domain model, not service logic)
- Use `@ApplicationModuleListener` (Spring Modulith), not `@EventListener` (no outbox guarantee)
- Event listeners must be in separate package (`messaging/listener/`) from domain events (`domain/event/`)
- Always propagate correlation ID through events for distributed tracing
- Completed events retained in database for audit trail (configurable TTL)

### 6. Clear Package Separation

Package boundaries should be self-evident from inspection. See the **Package Structure** section above for the complete package organization and dependency rules.

### 7. Exception Handling Strategy

**CRITICAL**: Understand the difference between validation failures and business rule violations.

**Controller-Level Exceptions:**
- `ResourceNotFoundException` - Entity not found by ID (404)
- `InvalidRequestException` - Malformed request data (400)

**Service-Level Exceptions:**
- `BusinessException` - Business rule violations (422)
- `ExternalServiceException` - External API failures (502/503)
- `IllegalArgumentException` / `IllegalStateException` - Defensive programming checks (programming errors)

**Exception Throwing Rules:**
- **Controllers ONLY throw**: `InvalidRequestException` for request format validation
- **Services ONLY throw**: `BusinessException` for business rule violations, `IllegalArgumentException`/`IllegalStateException` for defensive checks
- **Services NEVER throw**: `InvalidRequestException` (controller-level exception)

**Global Exception Handler:**
All exceptions are handled centrally via `@RestControllerAdvice` in service-common library.

**BusinessException Pattern:**

`BusinessException` requires TWO parameters:
1. Human-readable message (for users/logs)
2. Machine-readable error code (for client handling)

**Best Practice: Use Error Code Enums**

Create a service-specific enum for error codes:

```java
public enum CurrencyServiceError {
  /** Currency code already exists in the system. */
  DUPLICATE_CURRENCY_CODE,

  /** Currency code is not a valid ISO 4217 code. */
  INVALID_ISO_4217_CODE
}

// Usage
throw new BusinessException(
    "Currency code 'EUR' already exists",
    CurrencyServiceError.DUPLICATE_CURRENCY_CODE.name());
```

**What IS a BusinessException?**

✅ **Use BusinessException for domain rule violations:**
- Duplicate currency code (user tries to create EUR when EUR exists)
- Invalid ISO 4217 code (user submits "XXX" - valid format, but not in ISO 4217 standard)
- Insufficient funds (user tries to withdraw more than account balance)
- Invalid state transition (user tries to approve an already-approved transaction)

These are **valid requests** that violate **business logic**.

**What is NOT a BusinessException?**

❌ **DO NOT use BusinessException for:**
- Missing required fields (null/blank values) - Bean Validation handles this → 400 Bad Request
- Invalid format (wrong data type, regex mismatch) - Bean Validation handles this → 400 Bad Request
- Request format issues (startDate > endDate) - Controller throws `InvalidRequestException` → 400 Bad Request
- Missing entity ID in update - This is a programming error, not a user error
- Programming errors (illegal state) - Use `IllegalArgumentException` or `IllegalStateException`

**Rule of Thumb:**
- If validation can be done with `@NotBlank`, `@NotNull`, `@Pattern`, `@Size`, etc. → Bean Validation in request DTO
- If validation is request format (comparing request parameters) → Controller throws `InvalidRequestException` (400)
- If validation is business logic (ISO 4217, data availability, duplicates) → Service throws `BusinessException` (422)
- If validation is defensive programming (service layer constraint check) → Service throws `IllegalArgumentException`

**Controller vs Service Exception Example:**
```java
// CONTROLLER - Request format validation
@GetMapping
public List<ExchangeRateResponse> getExchangeRates(
    @RequestParam Optional<LocalDate> startDate,
    @RequestParam Optional<LocalDate> endDate) {

    // Request format validation: startDate > endDate → InvalidRequestException (400)
    if (startDate.isPresent() && endDate.isPresent() && startDate.get().isAfter(endDate.get())) {
        throw new InvalidRequestException("Start date must be before or equal to end date");
    }

    return service.getExchangeRates(...);
}

// SERVICE - Business validation + defensive programming
@Service
public class ExchangeRateService {
    public List<ExchangeRateData> getExchangeRates(
        Currency targetCurrency, LocalDate startDate, LocalDate endDate) {

        // Defensive programming: validate constraint (programming error protection)
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Start date must be before or equal to end date");
        }

        // Business validation: check data availability → BusinessException (422)
        var earliestDate = repository.findEarliestDateByTargetCurrency(targetCurrency);
        if (earliestDate.isEmpty()) {
            throw new BusinessException(
                "No exchange rate data available for currency: " + targetCurrency.getCurrencyCode(),
                CurrencyServiceError.NO_EXCHANGE_RATE_DATA_AVAILABLE.name());
        }

        // Business validation: start date out of range → BusinessException (422)
        if (startDate != null && startDate.isBefore(earliestDate.get())) {
            throw new BusinessException(
                "Exchange rates for " + targetCurrency.getCurrencyCode()
                    + " not available before " + earliestDate.get(),
                CurrencyServiceError.START_DATE_OUT_OF_RANGE.name());
        }
    }
}
```

### 8. Validation Strategy

**CRITICAL PRINCIPLE: Clear Separation of Concerns**

- **Bean Validation (Controller)**: Format, required fields, null checks, length, regex patterns
- **Business Validation (Service)**: Domain rules, business logic, cross-entity constraints
- **Database Constraints**: Data integrity as ultimate authority (UNIQUE, NOT NULL, foreign keys)

**Three Layers of Validation:**

| Layer | Type | When to Use | Example |
|-------|------|-------------|---------|
| **Controller (Bean Validation)** | Format, required, type | Always for API contracts | `@NotBlank`, `@Pattern("^[A-Z]{3}$")`, `@Size(max=50)` |
| **Service (Business Logic)** | Domain rules | Only for business constraints | ISO 4217 validity, business state rules |
| **Database (Constraints)** | Data integrity | Single source of truth | `UNIQUE` constraint, `NOT NULL`, foreign keys |

**Bean Validation (Controller Layer):**
```java
@PostMapping
public CurrencySeriesResponse create(@Valid @RequestBody CurrencySeriesRequest request) {
    // Bean validation automatically applied BEFORE this method executes
    // @NotBlank, @Pattern, @Size violations → 400 Bad Request
}

public record CurrencySeriesRequest(
    @NotBlank(message = "Currency code is required")
    @Size(min = 3, max = 3)
    @Pattern(regexp = "^[A-Z]{3}$")
    String currencyCode,

    @NotBlank(message = "Provider series ID is required")
    @Size(max = 50)
    String providerSeriesId) { }
```

**Business Validation (Service Layer):**
```java
@Service
public class CurrencyService {
    public CurrencySeries create(CurrencySeries currencySeries) {
        // ONLY business rules - NOT format/null checks
        validateCurrencyCode(currencySeries.getCurrencyCode());  // ISO 4217 check

        // Let database constraint handle uniqueness
        try {
            return currencySeriesRepository.save(currencySeries);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(
                "Currency code '" + currencySeries.getCurrencyCode() + "' already exists",
                CurrencyServiceError.DUPLICATE_CURRENCY_CODE.name());
        }
    }

    private void validateCurrencyCode(String currencyCode) {
        // Bean Validation already checked: not blank, 3 chars, uppercase
        // Service ONLY checks: ISO 4217 validity (business rule)
        try {
            Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                "Invalid ISO 4217 currency code: " + currencyCode,
                CurrencyServiceError.INVALID_ISO_4217_CODE.name());
        }
    }
}
```

**Key Principles:**

1. **Bean Validation happens in the Controller layer** (via `@Valid` on request DTOs)
   - Validates: null/blank, format, length, regex patterns
   - Fails fast before service layer executes
   - Results in `InvalidRequestException` (400 Bad Request)

2. **Business Validation happens in the Service layer**
   - Validates: domain rules, business logic, cross-entity constraints
   - Assumes format/null validation already passed
   - Results in `BusinessException` (422 Unprocessable Entity)

3. **Database Constraints are the single source of truth**
   - Enforces: uniqueness, referential integrity, data integrity
   - Service catches constraint violations and converts to `BusinessException`
   - Prevents race conditions and ensures consistency

**Anti-Pattern: Redundant Validation**

```java
// ❌ WRONG - Redundant validation in service layer
private void validateCurrencyCode(String currencyCode) {
    if (currencyCode == null || currencyCode.isBlank()) {
        throw new BusinessException(...);  // Bean Validation already checked this!
    }

    if (!currencyCode.matches("^[A-Z]{3}$")) {
        throw new BusinessException(...);  // @Pattern already checked this!
    }

    // ✅ ONLY THIS CHECK BELONGS HERE
    Currency.getInstance(currencyCode);  // ISO 4217 business rule
}

// ❌ WRONG - Duplicate check when database has UNIQUE constraint
private void checkDuplicateCurrencyCode(String currencyCode) {
    if (repository.findByCurrencyCode(currencyCode).isPresent()) {
        throw new BusinessException(...);  // Database constraint will catch this!
    }
}

// ✅ CORRECT - Let database constraint be the authority
try {
    return repository.save(entity);
} catch (DataIntegrityViolationException e) {
    throw new BusinessException(...);
}
```

**Service Layer Trust:**

The service layer should **trust** that the controller layer has validated request format. If null/blank values reach the service from the API, that's a **programming error** (forgot `@Valid` annotation), not a user error.

### 9. Code Quality Standards

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

**Variable Declarations:**
   **Use `var` whenever possible** for local variables to reduce verbosity and improve readability.
      - Prefer `var` whenever possible
      - Use explicit types only when the only other option is to cast a return type, e.g. 
      ```java
         Map<String, Object> details = Map.of("method", "POST", "uri", "/api/users", "status", 201);
         var body = "{\"name\":\"John Doe\"}";
      ```
**Imports:**
  **No wildcard imports, always expand explicit imports**

**Method Formatting:**
  - Add a blank line before `return` statements when there's **3 or more lines** of logic before it
  - For **1-2 line combos** (variable + return), **DO NOT** add a blank line before return
  - Don't create unnecessary variables just to return them - return the expression directly
  - Exception: Single-line methods or guard clauses don't need the blank line

  **Examples:**

  ```java
  // ✅ GOOD - Return expression directly (no intermediate variable needed)
  public String toJson(Object object) {
    return objectMapper.writeValueAsString(object);
  }

  // ✅ GOOD - 2-line combo: NO blank line before return
  public CurrencySeriesResponse getById(Long id) {
    var currencySeries = currencyService.getById(id);
    return CurrencySeriesResponse.from(currencySeries);
  }

  // ✅ GOOD - Multi-step logic (3+ lines) with blank line before return
  public CurrencySeries create(CurrencySeriesRequest request) {
    var entity = request.toEntity();
    validate(entity);

    return currencySeriesRepository.save(entity);
  }

  // ✅ GOOD - Simple getter
  public String getCurrencyCode() {
    return currencyCode;
  }

  // ✅ GOOD - Early return guard clause (no blank line)
  public void validate(CurrencySeries series) {
    if (series.getCurrencyCode() == null) {
      throw new IllegalArgumentException("Currency code required");
    }

    // ... more logic
  }

  // ❌ BAD - Unnecessary variable assignment
  public String toJson(Object object) {
    var json = objectMapper.writeValueAsString(object);
    return json;
  }

  // ❌ BAD - Blank line before return in 2-line combo
  public CurrencySeriesResponse getById(Long id) {
    var currencySeries = currencyService.getById(id);

    return CurrencySeriesResponse.from(currencySeries);
  }

  // ❌ BAD - Missing blank line before return (3+ lines of logic)
  public CurrencySeries toEntity() {
    var entity = new CurrencySeries();
    entity.setCurrencyCode(currencyCode);
    entity.setProviderSeriesId(providerSeriesId);
    return entity;
  }
  ```

**Javadoc Comments:**
  **CRITICAL**: All Javadoc comments must follow these formatting rules to pass Checkstyle:

  - **First sentence MUST end with a period (`.`)** - This is enforced by the `SummaryJavadoc` Checkstyle rule
  - The first sentence should be a concise summary (appears in method/class listings)
  - Use proper punctuation throughout

  **Examples:**

  ```java
  // ✅ CORRECT - First sentence ends with period
  /** Converts object to JSON string with sensitive fields masked. */
  public static String toJson(Object object) { }

  /** Header name for correlation ID. */
  public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

  /**
   * Masks a sensitive string value.
   *
   * @param value The value to mask
   * @param showLast Number of characters to show at the end
   * @return Masked value
   */
  public static String mask(String value, int showLast) { }

  // ❌ INCORRECT - Missing period at end of first sentence
  /** Converts object to JSON string with sensitive fields masked */
  public static String toJson(Object object) { }

  /** Header name for correlation ID */
  public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
  ```

  **Key Points:**
  - Single-line Javadoc: `/** Summary sentence here. */`
  - Multi-line Javadoc: First line after `/**` must end with period
  - Field documentation: Even short descriptions need periods
  - Always end the summary sentence with a period, even if it's obvious

**Build Commands:**

**IMPORTANT**: Always use these two commands in sequence. Never use other gradle commands like `check`, `bootJar`, `checkstyleMain`, etc.

```bash
# 1. Format code (always run first)
./gradlew clean spotlessApply

# 2. Build and test (always run second)
./gradlew clean build
```

The `clean build` command will:
- Clean previous build artifacts
- Compile all source code
- Run Spotless checks
- Run Checkstyle
- Run all unit and integration tests
- Build the JAR file

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

### Distributed Locking for Scheduled Tasks

**Implementation: ShedLock with JDBC/PostgreSQL**

The service uses ShedLock to ensure scheduled tasks run only once across multiple application instances in production deployments.

**Problem:** Without distributed locking, every pod would execute the exchange rate import job simultaneously, causing:
- Duplicate FRED API calls
- Unnecessary database load
- Race conditions when writing exchange rates
- Cache thrashing from multiple invalidations

**Solution:** ShedLock provides distributed coordination using the PostgreSQL database as the lock provider.

**How It Works:**
1. When the scheduled task triggers (daily at 11 PM UTC), each pod attempts to acquire a lock by inserting/updating a row in the `shedlock` table
2. Only one pod successfully acquires the lock (successful database write) and executes the import
3. Other pods skip execution (database constraint violation - lock already held)
4. Lock automatically expires after `lockAtMostFor` duration (prevents deadlocks if pod crashes)
5. Lock must be held for at least `lockAtLeastFor` duration (prevents too-frequent executions)

**Lock Configuration:**
```java
@SchedulerLock(
    name = "exchangeRateImport",     // Unique lock name
    lockAtMostFor = "15m",             // Max lock duration (safety timeout)
    lockAtLeastFor = "1m"              // Min lock duration
)
```

**Lock Duration Guidelines:**
- `lockAtMostFor` (15 minutes): Longer than expected import time (~30 seconds) to prevent premature release, but short enough to allow retry if pod crashes
- `lockAtLeastFor` (1 minute): Prevents rapid re-execution if job completes quickly

**Why ShedLock (not custom database locks)?**
- **Industry standard**: De facto solution for Spring Boot scheduled task coordination
- **Battle-tested**: Proven in production across thousands of applications
- **Simple integration**: Just add annotation to scheduled method
- **Automatic cleanup**: Handles lock release on pod crash via timeout
- **Clock drift handling**: Built-in safeguards for time synchronization issues

**Why JDBC/PostgreSQL (not Redis)?**
- **Service independence**: Each microservice uses its own database for locking (no shared Redis dependency)
- **Better failure isolation**: Database outage only affects one service, not all microservices
- **Simpler operations**: One less system to monitor (Redis only used for caching, not locking)
- **Architectural consistency**: Follows microservice principle of independent data stores
- **Sufficient performance**: Lock operations once per day - database latency (10-50ms) is acceptable

**Database Schema:**
```sql
CREATE TABLE shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
```

**Configuration Files:**
- Lock configuration: [ShedLockConfig.java](src/main/java/com/bleurubin/budgetanalyzer/currency/config/ShedLockConfig.java)
- Database migration: [V2__add_shedlock_table.sql](src/main/resources/db/migration/V2__add_shedlock_table.sql)
- Scheduler with lock: [ExchangeRateImportScheduler.java:42](src/main/java/com/bleurubin/budgetanalyzer/currency/scheduler/ExchangeRateImportScheduler.java#L42)
- Configuration: [application.yml:68-94](src/main/resources/application.yml#L68-L94)
- Dependencies: [build.gradle.kts:33-34](build.gradle.kts#L33-L34)

**Monitoring:**
- ShedLock doesn't provide built-in metrics, but you can monitor:
  - Application logs: "Starting scheduled exchange rate import" indicates lock acquired
  - Database query: `SELECT * FROM shedlock WHERE name = 'exchangeRateImport'` shows current lock holder
  - Import metrics: `exchange.rate.import.executions` (existing Micrometer counter)

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
- Flyway migrations for schema versioning
- Proper indexing for query performance
- Foreign key constraints for data integrity

### Schema Migration with Flyway

**Migration Management:**
- All schema changes are version-controlled via Flyway migrations
- Migration files located in [src/main/resources/db/migration/](src/main/resources/db/migration/)
- Naming convention: `V{version}__{description}.sql` (e.g., `V1__initial_schema.sql`)
- Undo migrations: `U{version}__rollback_{description}.sql` (documentation only, not auto-executed)

**Migration Workflow:**
1. **Create Migration**: Create new SQL file in `db/migration/` with next version number
2. **Write SQL**: Use database-agnostic SQL where possible (PostgreSQL/H2 compatible)
3. **Test Locally**: Run application or tests - Flyway auto-applies pending migrations
4. **Version Control**: Commit migration file with code changes
5. **Deploy**: Flyway automatically runs pending migrations on application startup

**Configuration:**
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate  # JPA validates schema matches entities
  flyway:
    enabled: true
    locations: classpath:db/migration
    validate-on-migrate: true
```

**Important Notes:**
- **Never modify committed migrations** - Create new migrations instead
- **Test migrations with clean database** - Use `./gradlew cleanTest test`
- **JPA validates schema** - Entities must match migrated schema (ddl-auto: validate)
- **Rollback** - Free tier doesn't auto-execute undo migrations; use as manual guide

**Example Migration:**
```sql
-- V2__add_exchange_rate_source.sql
ALTER TABLE exchange_rate ADD COLUMN source VARCHAR(50);
COMMENT ON COLUMN exchange_rate.source IS 'Data source (e.g., FRED, ECB)';
```

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

- Current version: v1 (explicit URL-based: `/v1/...`)
- Gateway prefix: `/api` handled by NGINX API Gateway
- External URLs: `/api/v1/currencies`, `/api/v1/exchange-rates`
- Internal service URLs: `/v1/currencies`, `/v1/exchange-rates`
- Future versions: URL-based (`/v2/...`, routed as `/api/v2/...` externally)
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
./gradlew clean spotlessApply
./gradlew clean build
```

### Git Workflow

- Create feature branches from `main`
- Follow conventional commits
- Run all checks before pushing
- Request code review for all changes

## Testing Strategy

### Unit Tests

**Current Coverage:**
- Limited test coverage (opportunity for improvement)

**Test Framework:**
- JUnit 5 (Jupiter)
- Spring Boot Test (for integration testing if needed)

**Running Tests:**
```bash
./gradlew test
```

### Testing Guidelines

**Test Coverage Goals:**
- Minimum 80% code coverage
- 100% coverage for critical utilities
- All edge cases explicitly tested


### Testing Philosophy: Test Correct Behavior, Not Defects

**CRITICAL PRINCIPLE**: Tests must always be written for how components **should** behave according to their specification and real-world requirements, NOT around defective implementations.

**When Tests Fail Due to Implementation Issues:**

1. **STOP immediately** - Do not attempt to work around the implementation deficiency
2. **Analyze the failure** - Determine if the test is correct or if the implementation is deficient
3. **Explain the situation** to the user:
    - What behavior the test expects (correct behavior)
    - What the current implementation does (deficient behavior)
    - Why the implementation is inadequate
4. **Ask the user** how to proceed:
    - Option A: Fix the implementation to match correct behavior
    - Option B: Remove/modify tests if requirements have changed

**Example Scenario:**

```
User: Add tests for CSV parsing with mismatched column counts

Assistant discovers:
- Tests written for correct behavior (handle rows with fewer/more columns than headers)
- Implementation fails because it assumes all rows have same column count as headers
- This is a common real-world edge case that MUST be handled

Assistant response:
"I've written tests for handling mismatched column counts (a common real-world scenario),
but they're failing because the current OpenCsvParser implementation doesn't handle this
edge case. The implementation assumes all rows have exactly the same number of columns
as headers, which causes ArrayIndexOutOfBoundsException.

Would you like me to:
1. Fix the implementation to handle mismatched columns properly, or
2. Remove these tests (not recommended - this is a real-world edge case)"
```

**Why This Matters:**

- **Test integrity**: Tests document correct behavior and serve as specifications
- **Code quality**: Working around bugs creates technical debt
- **Maintainability**: Future developers need accurate tests, not workarounds
- **Real-world robustness**: Edge cases are often discovered in production if not tested

**Anti-Pattern to Avoid:**

```java
// ❌ WRONG - Writing tests around broken implementation
@Test
void shouldThrowExceptionWhenRowHasFewerColumns() {
    // Documenting current buggy behavior instead of fixing it
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> {
        parser.parse("Name,Age\nJohn");
    });
}
```

**Correct Pattern:**

```java
// ✅ CORRECT - Writing test for correct behavior
@Test
void shouldHandleRowsWithFewerColumnsThanHeaders() {
    var result = parser.parse("Name,Age\nJohn");
    // Correct behavior: missing columns should be empty strings
    assertEquals("", result.get(0).get("Age"));
}
// If this fails, FIX THE IMPLEMENTATION, don't change the test
```

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
6. **Consider circuit breakers** for high-frequency external calls (not applicable for low-frequency scheduled jobs)

## Common Tasks

### Adding a New Entity

1. Create entity class in `domain/` package
2. Create Flyway migration script for new table/columns
3. Create repository interface in `repository/`
4. Create service class in `service/`
5. Create controller in `api/`
6. Create request/response DTOs in `api/response/`
7. Create mapper interface (MapStruct - future)
8. Write unit tests for all layers
9. Update OpenAPI documentation

### Adding a Database Migration

1. **Create Migration File**: `src/main/resources/db/migration/V{N}__{description}.sql`
   - Use next sequential version number (e.g., V2, V3, etc.)
   - Use descriptive snake_case name (e.g., `V2__add_exchange_rate_source.sql`)
2. **Write SQL**: Use PostgreSQL/H2 compatible SQL
   - Add tables, columns, indexes, constraints
   - Include COMMENT statements for documentation
3. **Create Undo Migration** (optional): `U{N}__rollback_{description}.sql`
   - Document rollback steps for manual execution
4. **Test Migration**:
   ```bash
   ./gradlew cleanTest test  # Verify migration works on clean database
   ./gradlew bootRun         # Verify migration works on existing database
   ```
5. **Update Entity** (if applicable): Modify JPA entity to match new schema
6. **Verify Validation**: JPA should validate schema matches entities (ddl-auto: validate)
7. **Commit**: Commit migration file with corresponding code changes

### Adding a New External API Client

1. Create client package under `client/`
2. Create response DTOs in client package
3. Configure WebClient in `config/`
4. Implement error handling and retries
5. Write integration tests
6. Document configuration properties

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
- **If encountering "cannot resolve" errors for service-common classes** (e.g., `SoftDeletableEntity`, `SafeLogger`, `ApiErrorResponse`):
  - Navigate to service-common directory: `cd /workspace/service-common`
  - Publish latest artifact: `./gradlew clean build publishToMavenLocal`
  - Return to budget-analyzer-api directory: `cd /workspace/budget-analyzer-api`
  - Retry the build: `./gradlew clean build`

**Checkstyle errors:**
  Review `config/checkstyle/checkstyle.xml` rules and fix violations including warnings if possible.

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
5. **Limit file access to the current directory and below** - Don't read or write files outside of the current budget-analyzer-api directory

### Code Quality

- **All code must be production-quality** - No shortcuts, prototypes, or workarounds
- **Follow service layer architecture** - Services accept/return entities, not API DTOs
- **Use pure JPA only** - No Hibernate-specific imports or annotations
- **Maintain package separation** - Clear boundaries between api, service, repository, domain
- **Always run these commands before committing:**
  1. `./gradlew clean spotlessApply` - Format code
  2. `./gradlew clean build` - Build and test everything

### Checkstyle Warning Handling

**CRITICAL**: When verifying the build with `./gradlew clean build`, always pay attention to Checkstyle warnings.

**Required Actions:**
1. **Always read build output carefully** - Look for Checkstyle warnings even if the build succeeds
2. **Attempt to fix all Checkstyle warnings** - Treat warnings as errors that need immediate resolution
3. **Common Checkstyle issues to watch for:**
    - Javadoc missing periods at end of first sentence
    - Missing Javadoc comments on public methods/classes
    - Import statement violations (wildcard imports, Hibernate imports)
    - Line length violations
    - Naming convention violations
    - Indentation issues
4. **If unable to fix warnings:**
    - Document the specific warning message
    - Explain why it cannot be fixed
    - Notify the user immediately with the warning details
    - Provide the file path and line number where the warning occurs
5. **Never ignore warnings** - Even if the build passes, Checkstyle warnings indicate code quality issues that must be addressed

**Example Response Pattern:**
```
Build completed successfully, but found Checkstyle warnings:
- File: src/main/java/com/bleurubin/service/Example.java:42
- Issue: Javadoc comment missing period at end of first sentence
- Action: Fixed by adding period to Javadoc summary

OR

Build completed with Checkstyle warnings that I cannot resolve:
- File: src/main/java/com/bleurubin/service/Example.java:42
- Warning: [specific warning message]
- Reason: [explanation of why it cannot be fixed]
```

### Architecture Conventions

- Controllers: Thin, HTTP-focused, delegate to services, NEVER import repositories
- Services: Business logic, validation, transactions, entity retrieval by ID
- Repositories: Data access only, ONLY imported by services
- Domain entities: Pure JPA, no business logic
- API DTOs: In `api/` package only, never used by services
- Service DTOs: In `service/dto/` package, for internal service-to-service communication

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

### Completed ✅
- [x] **Implement Redis caching layer** - Fully implemented with distributed caching, JSON serialization, and automatic cache invalidation ([CacheConfig.java](src/main/java/com/bleurubin/budgetanalyzer/currency/config/CacheConfig.java))
- [x] **Add API versioning strategy** - Implemented URL-based versioning with `/v1/` prefix on all endpoints; NGINX gateway handles `/api` prefix ([CurrencySeriesController.java](src/main/java/com/bleurubin/budgetanalyzer/currency/api/CurrencySeriesController.java), [ExchangeRateController.java](src/main/java/com/bleurubin/budgetanalyzer/currency/api/ExchangeRateController.java))
- [x] **Add Flyway for database migrations** - Version-controlled schema evolution with baseline migration; automatic migration on application startup ([V1__initial_schema.sql](src/main/resources/db/migration/V1__initial_schema.sql))
- [x] **Implement distributed locking with ShedLock** - Fully implemented with JDBC/PostgreSQL-based distributed locking; ensures only one scheduler instance runs import jobs in multi-pod deployments ([ShedLockConfig.java](src/main/java/com/bleurubin/budgetanalyzer/currency/config/ShedLockConfig.java), [V2__add_shedlock_table.sql](src/main/resources/db/migration/V2__add_shedlock_table.sql), [ExchangeRateImportScheduler.java:42](src/main/java/com/bleurubin/budgetanalyzer/currency/scheduler/ExchangeRateImportScheduler.java#L42))

### In Progress / Partial 🟡
- [~] **Add Prometheus metrics** - Micrometer instrumentation present with custom metrics in scheduler, but Prometheus endpoint not explicitly configured

### Planned 📋

#### High Priority - Testing & Quality
- [ ] **Add comprehensive integration tests** - Currently only smoke test exists; need controller, service, and repository layer tests
- [ ] **Migrate to Testcontainers for integration tests** - Replace H2 with PostgreSQL/Redis test containers for realistic integration testing; enables use of PostgreSQL-specific features in tests
- [ ] **Add PostgreSQL partial indexes after Testcontainers migration** - Replace full indexes with partial indexes (e.g., `WHERE completion_date IS NULL` for event_publication table) for improved performance and reduced index size
- [ ] **Add WireMock for external API testing** - Mock FRED API responses for reliable external integration tests

#### High Priority - Resilience & Reliability

**Note on Circuit Breakers:** Circuit breakers were considered for the FRED API integration but deemed inappropriate. The scheduled import job makes only 1-3 API calls per day (1 request with max 3 retry attempts), which doesn't match the high-frequency usage pattern that circuit breakers are designed for (hundreds to thousands of requests per minute). The existing retry logic with exponential backoff, combined with alerting via Micrometer metrics, is the appropriate solution for this low-frequency scheduled job. Circuit breakers would add unnecessary complexity without providing meaningful benefit.

#### Medium Priority - Observability
- [ ] **Implement distributed tracing** - Add Zipkin/Jaeger for request flow visibility across microservices
- [ ] **Complete Prometheus metrics setup** - Configure Prometheus endpoint exposure and custom business metrics
- [ ] **Implement request/response logging filter** - Comprehensive HTTP request/response logging for debugging and audit

#### Medium Priority - Data Management
- [ ] **Add audit logging for data changes** - Track who changed what and when using JPA entity listeners or interceptors
- [ ] **Implement MapStruct for DTO mapping** - Replace manual mapping with compile-time safe DTO transformations

#### Low Priority - Optional Features
- [ ] **Implement API rate limiting** - Protect against abuse with request throttling (may be handled at gateway level)
- [ ] **Add GraphQL endpoint (optional)** - Provide GraphQL API alongside REST for flexible querying
- [ ] **Implement event publishing (Kafka/RabbitMQ)** - Publish domain events for exchange rate updates to enable event-driven architecture

## Support and Contact

For questions or issues:
- Review this documentation first
- Check existing GitHub issues
- Create new issue with detailed description and reproduction steps
