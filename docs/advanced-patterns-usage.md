# Advanced Patterns Usage Guide

This service implements ALL advanced patterns from service-common. This guide provides implementation details and usage examples specific to the currency service.

For complete pattern documentation, see [service-common/docs/advanced-patterns.md](../../service-common/docs/advanced-patterns.md).

## Provider Abstraction Pattern

### Overview

Service layer depends on `ExchangeRateProvider` interface, never on concrete FRED implementation. This decouples the service from the external data provider and allows switching providers without service layer changes.

### Architecture

```
CurrencyService
    ↓ (depends on interface)
ExchangeRateProvider (interface)
    ↑ (implements)
FredExchangeRateProvider
    ↓ (uses)
FredClient (HTTP communication)
```

### Dependency Rules

✅ **Allowed:**
- `CurrencyService` imports `ExchangeRateProvider` interface
- `FredExchangeRateProvider` implements `ExchangeRateProvider`
- `FredClient` handles HTTP calls to FRED API

❌ **Forbidden:**
- `CurrencyService` importing `FredExchangeRateProvider` directly
- `CurrencyService` importing anything from `client.fred` package
- Service layer code mentioning "FRED" or any provider name

### Implementation Example

**Service Layer (Good):**
```java
@Service
public class CurrencyServiceImpl implements CurrencyService {
    private final ExchangeRateProvider exchangeRateProvider;  // Interface only

    public void importExchangeRates(String currencyCode) {
        List<ExchangeRate> rates = exchangeRateProvider.fetchRates(currencyCode);
        // Process rates...
    }
}
```

**Service Layer (Bad):**
```java
@Service
public class CurrencyServiceImpl implements CurrencyService {
    private final FredExchangeRateProvider fredProvider;  // ❌ Concrete implementation

    public void importExchangeRates(String currencyCode) {
        List<ExchangeRate> rates = fredProvider.fetchRates(currencyCode);  // ❌ Tight coupling
        // Process rates...
    }
}
```

### Adding a New Provider

To add ECB, Bloomberg, or other provider:

1. **Create provider implementation:**
```java
@Component
@Profile("ecb")  // Activate with spring.profiles.active=ecb
public class EcbExchangeRateProvider implements ExchangeRateProvider {
    private final EcbClient ecbClient;

    @Override
    public List<ExchangeRate> fetchRates(String currencyCode) {
        // ECB-specific implementation
    }
}
```

2. **Create client:**
```java
@Component
public class EcbClient {
    private final RestTemplate restTemplate;

    public EcbResponse fetchExchangeRates(String currency) {
        // HTTP calls to ECB API
    }
}
```

3. **Add configuration:**
```yaml
currency-service:
  ecb:
    base-url: https://api.ecb.europa.eu
    api-key: ${ECB_API_KEY}
```

4. **No service layer changes needed** - service already uses interface

### Discovery Commands

```bash
# View provider interface
cat src/main/java/org/budgetanalyzer/currency/service/provider/ExchangeRateProvider.java

# View FRED implementation
cat src/main/java/org/budgetanalyzer/currency/service/provider/FredExchangeRateProvider.java

# View FRED client
cat src/main/java/org/budgetanalyzer/currency/client/fred/FredClient.java

# Verify service uses interface only
grep -r "ExchangeRateProvider" src/main/java/*/service/impl/
```

## ShedLock Distributed Locking

### Overview

Daily scheduled import runs exactly once across all pods using database-backed distributed lock. Prevents duplicate imports in multi-pod Kubernetes deployments.

### Configuration

**Schedule:** Daily at 11 PM UTC

**Lock Parameters:**
- `lockAtMostFor: 15m` - Safety timeout (task takes ~30 seconds)
- `lockAtLeastFor: 1m` - Prevents rapid re-execution

**Lock Storage:** PostgreSQL (table: `shedlock`)

### Implementation Example

```java
@Component
public class ExchangeRateImportScheduler {

    @Scheduled(cron = "0 0 23 * * *")  // 11 PM UTC daily
    @SchedulerLock(
        name = "importExchangeRates",
        lockAtMostFor = "15m",
        lockAtLeastFor = "1m"
    )
    public void importDailyExchangeRates() {
        currencyService.importAllCurrencyRates();
    }
}
```

### How It Works

1. Scheduler triggers on all pods at 11 PM UTC
2. First pod to execute acquires lock in database
3. Other pods see lock exists and skip execution
4. Lock auto-releases after task completes
5. If pod crashes, lock expires after 15 minutes (safety timeout)

### Lock Duration Guidelines

**lockAtMostFor** (maximum lock duration):
- Should be longer than expected task duration
- Safety mechanism for crashed pods
- Current: 15m for 30-second task (30x buffer)

**lockAtLeastFor** (minimum lock duration):
- Prevents rapid re-execution if task completes early
- Should be less than schedule interval
- Current: 1m minimum between executions

### Multi-Pod Behavior

**Expected (one pod acquires lock):**
```
Pod 1: Lock acquired, importing rates...
Pod 2: Lock already held, skipping
Pod 3: Lock already held, skipping
Pod 1: Import complete, lock released
```

**Crash Recovery:**
```
Pod 1: Lock acquired, importing rates...
Pod 1: [CRASHES]
[15 minutes later - lock expires]
Next scheduled run:
Pod 2: Lock expired, acquiring and importing...
```

### Adding New Scheduled Tasks

1. **Create scheduled method:**
```java
@Scheduled(cron = "0 0 2 * * *")  // 2 AM UTC daily
@SchedulerLock(
    name = "cleanupExpiredData",  // Must be unique
    lockAtMostFor = "10m",
    lockAtLeastFor = "30s"
)
public void cleanupExpiredData() {
    // Task implementation
}
```

2. **Choose lock duration:**
- `lockAtMostFor` = expected duration × 10-30
- `lockAtLeastFor` = 10-20% of schedule interval

3. **Test multi-pod:**
```bash
# Start multiple instances
./gradlew bootRun --args='--server.port=8084'
./gradlew bootRun --args='--server.port=8085'

# Verify only one executes (check logs)
```

### Discovery Commands

```bash
# Find scheduled tasks
grep -r "@Scheduled" src/main/java/*/scheduler/

# View lock configuration
cat src/main/resources/application.yml | grep -A 5 "shedlock"

# Check lock table
psql -d budget_analyzer -c "SELECT * FROM shedlock;"
```

## Redis Distributed Caching

### Overview

Exchange rate queries cached with 1-hour TTL. Dramatically improves response times: cache hit 1-3ms vs. cache miss 50-200ms.

### Performance Impact

- **Cache Hit:** 1-3ms (from Redis)
- **Cache Miss:** 50-200ms (PostgreSQL query)
- **Expected Hit Rate:** 80-95% for typical usage
- **Speedup:** 50-200x faster on cache hit

### Implementation Example

**Query Method (Cached):**
```java
@Service
public class CurrencyServiceImpl implements CurrencyService {

    @Cacheable(value = "exchangeRates", key = "#targetCurrency + ':' + #startDate + ':' + #endDate")
    public List<ExchangeRate> getExchangeRates(
        String targetCurrency,
        LocalDate startDate,
        LocalDate endDate
    ) {
        return exchangeRateRepository.findByCurrencySeriesAndDateRange(
            targetCurrency, startDate, endDate
        );
    }
}
```

**Update Method (Cache Eviction):**
```java
@CacheEvict(value = "exchangeRates", allEntries = true)
public void importExchangeRates(String currencyCode) {
    // Import new rates from provider
    // Cache automatically cleared after method completes
}
```

### Cache Key Strategy

**Format:** `{targetCurrency}:{startDate}:{endDate}`

**Examples:**
- `USD:2024-01-01:2024-12-31` - Full year USD rates
- `EUR:2024-11-01:2024-11-15` - Two weeks EUR rates
- `THB:2024-11-15:2024-11-15` - Single day THB rate

**Why this format:**
- Natural key for rate queries
- Avoids cache collision across currencies
- Supports range queries efficiently

### Cache Configuration

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  cache:
    type: redis
    redis:
      time-to-live: 3600000  # 1 hour in milliseconds
```

### When to Cache

✅ **Good candidates:**
- Frequently accessed data (exchange rates queried often)
- Slow to compute/fetch (database queries)
- Changes infrequently (rates imported once daily)
- Same parameters used repeatedly (date ranges)

❌ **Bad candidates:**
- Data changes frequently (real-time data)
- Unique queries (never repeated)
- Large result sets (memory concerns)
- Security-sensitive data (risk of stale permissions)

### Cache Eviction Strategies

**1. Evict all entries (current approach):**
```java
@CacheEvict(value = "exchangeRates", allEntries = true)
public void importExchangeRates(String currencyCode) {
    // Clears entire cache
}
```

**When to use:** Data changes affect many cache entries (our case: new imports invalidate all ranges)

**2. Evict specific key:**
```java
@CacheEvict(value = "exchangeRates", key = "#currencyCode + ':*'")
public void updateCurrency(String currencyCode) {
    // Clears only this currency (requires key pattern matching)
}
```

**When to use:** Targeted updates affecting specific entries only

**3. Time-based expiration (automatic):**
- TTL: 1 hour configured in application.yml
- Redis automatically removes expired entries
- No code changes needed

### Monitoring Cache Performance

**Enable cache statistics:**
```yaml
spring:
  cache:
    cache-names: exchangeRates
    redis:
      enable-statistics: true
```

**Check cache metrics:**
```bash
# Via actuator (if enabled)
curl http://localhost:8084/actuator/metrics/cache.gets?tag=name:exchangeRates
curl http://localhost:8084/actuator/metrics/cache.evictions?tag=name:exchangeRates
```

**Expected metrics:**
- Hit rate: 80-95%
- Miss rate: 5-20%
- Evictions: 1x daily (after import)

### Discovery Commands

```bash
# Find cached methods
grep -r "@Cacheable" src/main/java/

# Find cache eviction points
grep -r "@CacheEvict" src/main/java/

# View cache configuration
cat src/main/resources/application.yml | grep -A 10 "redis"

# Check Redis connection
redis-cli -h localhost -p 6379 ping
```

## Event-Driven Messaging

### Overview

Transactional outbox ensures 100% guaranteed message delivery. Events persisted in database atomically with business data, then published to RabbitMQ asynchronously.

### Why Transactional Outbox

**Problem without outbox:**
```java
// ❌ Unreliable - can lose messages
@Transactional
public void createCurrency(Currency currency) {
    currencyRepository.save(currency);  // Succeeds
    rabbitTemplate.send(event);         // Fails - message lost!
}
```

**Solution with outbox:**
```java
// ✅ Reliable - guaranteed delivery
@Transactional
public void createCurrency(Currency currency) {
    currencyRepository.save(currency);
    applicationEventPublisher.publishEvent(new CurrencyCreatedEvent(currency));
    // Both persisted in same transaction
    // Message delivered asynchronously even if we crash
}
```

### Implementation Example

**1. Define domain event:**
```java
public class CurrencyCreatedEvent {
    private final String currencyCode;
    private final String currencyName;
    // Constructor, getters
}
```

**2. Publish event from service:**
```java
@Service
public class CurrencyServiceImpl implements CurrencyService {
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public CurrencyCreated createCurrency(CreateCurrencyRequest request) {
        var currency = currencyRepository.save(newCurrency);

        // Event persisted in event_publication table (same transaction)
        eventPublisher.publishEvent(
            new CurrencyCreatedEvent(currency.getCode(), currency.getName())
        );

        return currency;
    }
}
```

**3. Bridge to external message broker:**
```java
@Component
public class CurrencyEventListener {

    @ApplicationModuleListener
    void on(CurrencyCreatedEvent event) {
        // Convert domain event to external message
        var message = new CurrencyMessage(event.getCurrencyCode(), event.getCurrencyName());

        // Publish to RabbitMQ
        rabbitTemplate.convertAndSend("currency.exchange", "currency.created", message);
    }
}
```

**4. Consumer in other service:**
```java
@Component
public class CurrencyEventConsumer {

    @RabbitListener(queues = "transaction-service.currency-events")
    public void handleCurrencyCreated(CurrencyMessage message) {
        // Handle event (e.g., update local cache)
        transactionService.onCurrencyCreated(message);
    }
}
```

### Event Flow

1. **Service publishes domain event** (in transaction)
2. **Spring Modulith persists to `event_publication` table** (same transaction)
3. **Transaction commits** (both business data and event saved)
4. **Spring Modulith polls `event_publication` table** (async)
5. **Listener receives event** and publishes to RabbitMQ
6. **RabbitMQ delivers** to subscribed services
7. **Event marked complete** in `event_publication` table

### Guaranteed Delivery

**Scenario 1: Normal operation**
```
1. Save currency + persist event (transaction)
2. Commit transaction
3. Publish to RabbitMQ
4. Mark event complete
✅ Message delivered
```

**Scenario 2: Crash before RabbitMQ publish**
```
1. Save currency + persist event (transaction)
2. Commit transaction
3. [APPLICATION CRASHES]
4. [RESTART]
5. Spring Modulith finds unpublished events
6. Publishes to RabbitMQ
7. Mark event complete
✅ Message delivered after restart
```

**Scenario 3: RabbitMQ publish fails**
```
1. Save currency + persist event (transaction)
2. Commit transaction
3. Publish to RabbitMQ → FAILS
4. Event remains in event_publication
5. Spring Modulith retries automatically
6. Eventually succeeds
✅ Message delivered after retry
```

### RabbitMQ Configuration

**Exchange and Routing:**
```yaml
currency-service:
  rabbitmq:
    exchange: currency.exchange
    routing-key-prefix: currency
```

**Queue Binding (in consumer service):**
```java
@Bean
public Queue currencyEventQueue() {
    return new Queue("transaction-service.currency-events", true);
}

@Bean
public Binding currencyEventBinding(Queue queue, TopicExchange exchange) {
    return BindingBuilder.bind(queue)
        .to(exchange)
        .with("currency.*");  // Matches currency.created, currency.updated, etc.
}
```

### Adding New Events

1. **Define event class:**
```java
public class CurrencyUpdatedEvent {
    private final String currencyCode;
    private final String newName;
    // Constructor, getters
}
```

2. **Publish from service:**
```java
@Transactional
public void updateCurrency(String code, String newName) {
    var currency = currencyRepository.findById(code)
        .orElseThrow(() -> new ResourceNotFoundException("Currency not found"));

    currency.setName(newName);
    currencyRepository.save(currency);

    eventPublisher.publishEvent(new CurrencyUpdatedEvent(code, newName));
}
```

3. **Add listener:**
```java
@ApplicationModuleListener
void on(CurrencyUpdatedEvent event) {
    var message = new CurrencyMessage(event.getCurrencyCode(), event.getNewName());
    rabbitTemplate.convertAndSend("currency.exchange", "currency.updated", message);
}
```

4. **Consumers automatically receive** (if bound to `currency.*`)

### Discovery Commands

```bash
# Find domain events
find src/main/java -type f -path "*/domain/event/*.java"

# Find event publishers
grep -r "publishEvent" src/main/java/*/service/

# Find event listeners
grep -r "@ApplicationModuleListener" src/main/java/*/messaging/

# Find RabbitMQ consumers
grep -r "@RabbitListener" src/main/java/*/messaging/

# View RabbitMQ configuration
cat src/main/resources/application.yml | grep -A 10 "rabbitmq"

# Check event publication table
psql -d budget_analyzer -c "SELECT * FROM event_publication ORDER BY publication_date DESC LIMIT 10;"
```

## Testing Advanced Patterns

### Provider Abstraction Testing

**Unit test with mock provider:**
```java
@Test
void testImportExchangeRates() {
    // Mock the interface, not FRED
    ExchangeRateProvider mockProvider = mock(ExchangeRateProvider.class);
    when(mockProvider.fetchRates("USD")).thenReturn(testRates);

    var service = new CurrencyServiceImpl(mockProvider, repository);
    service.importExchangeRates("USD");

    verify(repository).saveAll(testRates);
}
```

**Integration test with real provider:**
```java
@SpringBootTest
@TestPropertySource(properties = {"spring.profiles.active=test"})
class FredProviderIntegrationTest {

    @Autowired
    private ExchangeRateProvider provider;  // Real FRED implementation

    @Test
    void testFetchRatesFromFred() {
        List<ExchangeRate> rates = provider.fetchRates("USD");
        assertThat(rates).isNotEmpty();
    }
}
```

### ShedLock Testing

**Test lock behavior:**
```java
@SpringBootTest
class SchedulerLockTest {

    @Test
    void testOnlyOneInstanceExecutes() throws Exception {
        // Start two scheduler instances
        var scheduler1 = new ExchangeRateImportScheduler(service);
        var scheduler2 = new ExchangeRateImportScheduler(service);

        // Trigger both simultaneously
        CompletableFuture.allOf(
            CompletableFuture.runAsync(scheduler1::importDailyExchangeRates),
            CompletableFuture.runAsync(scheduler2::importDailyExchangeRates)
        ).join();

        // Verify import executed exactly once
        verify(service, times(1)).importAllCurrencyRates();
    }
}
```

### Redis Caching Testing

**Test cache hit:**
```java
@SpringBootTest
@AutoConfigureTestDatabase
class CachingTest {

    @Autowired
    private CurrencyService service;

    @Autowired
    private CacheManager cacheManager;

    @Test
    void testCacheHit() {
        // First call - cache miss
        service.getExchangeRates("USD", startDate, endDate);

        // Second call - cache hit
        service.getExchangeRates("USD", startDate, endDate);

        // Verify repository called only once
        verify(repository, times(1)).findByCurrencySeriesAndDateRange(any(), any(), any());
    }

    @Test
    void testCacheEviction() {
        service.getExchangeRates("USD", startDate, endDate);  // Cached

        service.importExchangeRates("USD");  // Evicts cache

        service.getExchangeRates("USD", startDate, endDate);  // Cache miss again

        verify(repository, times(2)).findByCurrencySeriesAndDateRange(any(), any(), any());
    }
}
```

### Event-Driven Messaging Testing

**Test event publication:**
```java
@SpringBootTest
class EventPublishingTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @Test
    void testCurrencyCreatedEventPublished() {
        var currency = currencyService.createCurrency(request);

        // Verify event persisted
        var events = eventRepository.findIncomplete();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo("CurrencyCreatedEvent");
    }

    @Test
    void testEventBridgeToRabbitMQ() {
        var event = new CurrencyCreatedEvent("USD", "US Dollar");

        listener.on(event);

        verify(rabbitTemplate).convertAndSend(
            eq("currency.exchange"),
            eq("currency.created"),
            any(CurrencyMessage.class)
        );
    }
}
```

## Common Patterns and Best Practices

### Provider Pattern

✅ **Do:**
- Service depends on interface only
- Provider names never in service layer
- Use Spring profiles for provider selection

❌ **Don't:**
- Service importing concrete provider
- Hardcode provider logic in service
- Mix provider concerns with business logic

### Distributed Locking

✅ **Do:**
- Use database-backed locks (PostgreSQL)
- Set `lockAtMostFor` = task duration × 10-30
- Set `lockAtLeastFor` = 10-20% of interval
- Use unique lock names per task

❌ **Don't:**
- Use in-memory locks (not distributed)
- Set timeout shorter than task duration
- Reuse lock names across different tasks
- Forget to test multi-pod behavior

### Caching

✅ **Do:**
- Cache slow queries with repeated parameters
- Use meaningful cache keys
- Evict cache when data changes
- Monitor cache hit rates

❌ **Don't:**
- Cache fast operations
- Cache frequently changing data
- Forget to evict on updates
- Use cache for security decisions

### Messaging

✅ **Do:**
- Use transactional outbox for reliability
- Publish domain events from service
- Bridge to external broker in listener
- Make consumers idempotent

❌ **Don't:**
- Publish directly to RabbitMQ in transaction
- Put broker logic in service layer
- Assume exactly-once delivery
- Couple services via synchronous calls
