# Step 6.2: Event-Driven Messaging Tests Implementation Plan

## Overview
Create comprehensive isolated tests for Spring Modulith event-driven messaging using `@ApplicationModuleTest` from spring-modulith-starter-test. This provides proper module testing support with built-in event verification.

## Key Design Decisions

### 1. **Use Spring Modulith Test Support**
- **Annotation**: `@ApplicationModuleTest` - designed specifically for testing Spring Modulith events
- **Benefits**: Built-in event publication verification, proper module boundaries
- **Alternative considered**: `@SpringBootTest` (too heavy, not module-focused)

### 2. **Isolated Test Class** (Not extending AbstractIntegrationTest)
- **Rationale**: Seed data conflicts + different testing approach
- **Approach**: Standalone test with dedicated TestContainers
- **Cleanup**: Simple `@BeforeEach` truncates

### 3. **Mock ExchangeRateProvider**
- **Why**: Prevent real FRED API calls
- **Implementation**: `@TestConfiguration` with `@Primary` bean

## Implementation Plan

### Step 1: Create Isolated Test Infrastructure
**File**: `src/test/java/org/budgetanalyzer/currency/integration/MessagingIntegrationTest.java`

**Core Setup**:
```java
@ApplicationModuleTest  // Spring Modulith test support
@Testcontainers
@ActiveProfiles("test")
class MessagingIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withReuse(true);

    @Container
    @ServiceConnection
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        .withReuse(true);

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3-management-alpine")
        .withReuse(true);

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired CurrencyService currencyService;
    @Autowired ExchangeRateRepository exchangeRateRepository;
    @Autowired ExchangeRateProvider mockProvider;

    // Spring Modulith test support for event verification
    @Autowired ApplicationEvents applicationEvents;  // Key benefit!
}
```

**Key Benefit**: `ApplicationEvents` provides:
- `assertThatEventOfType()` - fluent assertions for domain events
- Event history tracking during tests
- Proper module boundary verification

### Step 2: Mock Provider Configuration
```java
@TestConfiguration
static class TestConfig {
    @Bean
    @Primary
    ExchangeRateProvider mockExchangeRateProvider() {
        return mock(ExchangeRateProvider.class);
    }
}
```

### Step 3: Simple Cleanup Strategy
```java
@BeforeEach
void cleanup() {
    // Clear database
    jdbcTemplate.execute("TRUNCATE TABLE exchange_rate CASCADE");
    jdbcTemplate.execute("TRUNCATE TABLE currency_series CASCADE");
    jdbcTemplate.execute("DELETE FROM event_publication");

    // Clear RabbitMQ queues
    rabbitAdmin.purgeQueue("currency.created");
    rabbitAdmin.purgeQueue("currency.created.dlq");

    // Clear mock
    clearInvocations(mockProvider);
}
```

### Step 4: Implement Test Categories

#### A. Event Publishing Tests (3 tests)
**Using ApplicationEvents API**:

1. `shouldPersistCurrencyCreatedEventToDatabase()`
   ```java
   var currency = currencyService.create(...);

   // Spring Modulith test API
   applicationEvents.assertThatEventOfType(CurrencyCreatedEvent.class)
       .matching(e -> e.currencySeriesId().equals(currency.getId()))
       .wasPublished();

   // Verify persistence
   assertThat(countCurrencyCreatedEvents()).isEqualTo(1);
   ```

2. `shouldPersistCurrencyUpdatedEventToDatabase()`
   - Update currency
   - Use `applicationEvents.assertThatEventOfType(CurrencyUpdatedEvent.class)`

3. `shouldPublishEventEvenForDisabledCurrency()`
   - Create disabled currency
   - Verify event published (listener filters later)

#### B. Event Processing Tests (4 tests)
1. `shouldProcessCurrencyCreatedEventAndPublishToRabbitMQ()`
   ```java
   setupMockProvider("EUR", "DEXUSEU");
   var currency = currencyService.create(...);

   await().atMost(10, SECONDS).untilAsserted(() -> {
       assertThat(countCompletedEvents()).isEqualTo(1);
       // Verify message in RabbitMQ
   });
   ```

2. `shouldIncludeCorrelationIdInPublishedMessage()`
   - Set MDC correlation ID
   - Verify in RabbitMQ message

3. `shouldNotPublishMessageForDisabledCurrency()`
   - Create disabled currency
   - Verify event published but NOT processed

4. `shouldMarkEventAsCompletedAfterSuccessfulPublishing()`
   - Verify completion_date set

#### C. Consumer Execution Tests (5 tests)
1. `shouldImportExchangeRatesWhenCurrencyCreatedMessageReceived()`
   ```java
   setupMockProvider("EUR", "DEXUSEU");
   var currency = currencyService.create(...);

   await().atMost(10, SECONDS).untilAsserted(() -> {
       var count = exchangeRateRepository.countByCurrencySeries(currency);
       assertThat(count).isGreaterThan(0);
   });
   ```

2. `shouldOnlyImportForEnabledCurrency()`
   - Create enabled currency → verify import
   - Create disabled currency → verify no import

3. `shouldPropagateCorrelationIdThroughEntireFlow()`
   - Set correlation ID
   - Verify same ID throughout flow

4. `shouldHandleMultipleCurrenciesIndependently()`
   - Create 2 currencies
   - Verify both imported correctly

5. `shouldReturnImportResultWithCounts()`
   - Mock provider with 10 rates
   - Verify counts (10 new, 0 updated)

#### D. Async Processing Tests (3 tests)
1. `shouldCompleteFullFlowWithinReasonableTime()`
   - Create currency
   - Awaitility (max 10s): verify import completed

2. `shouldProcessEventsInOrder()`
   - Create 3 currencies rapidly
   - Verify all imported

3. `shouldHandleConcurrentCreations()`
   - Create 5 currencies in parallel
   - Verify all imported

#### E. Transaction Boundaries Tests (3 tests)
1. `shouldRollbackEventWhenCurrencyCreationFails()`
   ```java
   // Use applicationEvents to verify NO event published on rollback
   applicationEvents.assertThatEventOfType(CurrencyCreatedEvent.class)
       .wasNotPublished();
   ```

2. `shouldCommitEventWithCurrencyInSameTransaction()`
   - Create currency
   - Verify both currency and event exist

3. `shouldNotLoseEventOnListenerFailure()`
   - Mock publisher to throw exception
   - Verify event remains unpublished
   - Verify Spring Modulith retries

#### F. Retry Logic Tests (4 tests)
1. `shouldRetryEventProcessingOnTransientFailure()`
   - Mock publisher to fail 2x, succeed 3rd
   - Verify event eventually complete

2. `shouldRetryConsumerOnImportServiceFailure()`
   - Mock import service to fail 2x
   - Verify consumer retries

3. `shouldStopRetryingAfterMaxAttempts()`
   - Mock import to always fail
   - Verify 3 attempts then stop

4. `shouldUseExponentialBackoffForRetries()`
   - Verify retry timing pattern

#### G. Dead Letter Queue Tests (3 tests)
1. `shouldRoutePoisonMessageToDLQAfterMaxRetries()`
   - Mock import to always fail
   - Verify message in DLQ

2. `shouldContinueProcessingOtherMessagesAfterDLQ()`
   - Send poison + valid message
   - Verify valid processed

3. `shouldPreserveOriginalMessageInDLQ()`
   - Verify DLQ message contents

### Step 5: Helper Methods
```java
// Database queries
private long countCurrencyCreatedEvents() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE ?",
        Long.class, "%CurrencyCreatedEvent");
}

private long countCompletedEvents() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NOT NULL",
        Long.class);
}

// Mock setup
private void setupMockProvider(String currencyCode, String seriesId) {
    when(mockProvider.validateSeriesExists(seriesId)).thenReturn(true);
    var rates = Map.of(
        LocalDate.of(2024, 1, 1), new BigDecimal("1.10"),
        LocalDate.of(2024, 1, 2), new BigDecimal("1.11"),
        LocalDate.of(2024, 1, 3), new BigDecimal("1.12")
    );
    when(mockProvider.getExchangeRates(any(), any())).thenReturn(rates);
}

// RabbitMQ helpers
private CurrencyCreatedMessage receiveMessage(String queueName) {
    return (CurrencyCreatedMessage) rabbitTemplate.receiveAndConvert(queueName, 5000);
}
```

## Key Advantages of @ApplicationModuleTest

1. **Built-in Event Verification**: `ApplicationEvents` API for fluent assertions
2. **Module Boundary Testing**: Ensures proper Spring Modulith module isolation
3. **Event History**: Automatic tracking of all published events during test
4. **Cleaner Tests**: No manual event_publication table queries for event verification
5. **Integration with Awaitility**: Works seamlessly for async event processing

## Success Criteria
- ✅ 25 tests covering all 7 categories (A-G)
- ✅ Use `@ApplicationModuleTest` and `ApplicationEvents` API
- ✅ Simple cleanup with TRUNCATE in `@BeforeEach`
- ✅ Isolated database (no AbstractIntegrationTest)
- ✅ All async verified with Awaitility
- ✅ Complete event flow tested end-to-end
- ✅ All tests pass reliably (<5% flakiness)

## Estimated Effort
- **Setup infrastructure**: 30 minutes
- **Implement 25 tests**: 4-5 hours (faster with ApplicationEvents API)
- **Debug and stabilize**: 1-2 hours
- **Total**: ~6 hours

## Event Flow Reference

```
HTTP POST /v1/currencies (Thread 1)
  │
  ├─> CurrencyService.create()
  │   ├─> repository.save(currency)           [Transaction starts]
  │   ├─> eventPublisher.publishEvent(...)    [In-memory event]
  │   │   └─> Spring Modulith intercepts
  │   │       └─> Saves to event_publication  [Same transaction]
  │   └─> return currency                     [Transaction commits]
  │
  └─> HTTP 201 Created (returns immediately)

Background Async Processing (Thread 2)
  │
  ├─> Spring Modulith polls event_publication table
  ├─> Finds unpublished events (completion_date IS NULL)
  ├─> Calls @ApplicationModuleListener
  │   └─> MessagingEventListener.onCurrencyCreated()
  │       ├─> Checks if enabled=true
  │       ├─> Sets correlationId in MDC
  │       └─> CurrencyMessagePublisher.publishCurrencyCreated()
  │           └─> StreamBridge.send("currencyCreated-out-0", message)
  │               └─> RabbitMQ: currency.created exchange
  │
  └─> Marks event as completed (completion_date = now)

RabbitMQ Consumer (Thread 3)
  │
  ├─> ExchangeRateImportConsumer.importExchangeRates()
  │   ├─> Sets correlationId in MDC
  │   ├─> ExchangeRateImportService.importExchangeRatesForSeries(id)
  │   │   ├─> Fetches currency series from DB
  │   │   ├─> Calls ExchangeRateProvider.getExchangeRates()
  │   │   ├─> Saves exchange rates to DB
  │   │   └─> @CacheEvict(allEntries=true) - clears cache
  │   └─> Returns ExchangeRateImportResult
  │
  └─> ACK message to RabbitMQ
```

## Notes
- Test file will be ~800-1000 lines (comprehensive)
- Use deleted MessagingIntegrationTest (commit 0bba545) as reference for patterns
- Focus on deterministic assertions (avoid timing-dependent tests)
- Ensure RabbitMQ queues are cleaned between tests
- Use TestConstants for test data where possible
