# Advanced Patterns Usage Guide

This guide shows how the currency service applies the shared service-common patterns: provider
abstraction, ShedLock, Redis caching, and the Spring Modulith transactional outbox. Code blocks are
abridged excerpts from the real classes. Property values and defaults are owned by
[configuration.md](configuration.md).

For the cross-service pattern documentation, see
[service-common advanced patterns](https://github.com/budgetanalyzer/service-common/blob/main/docs/advanced-patterns.md).

## Provider Abstraction Pattern

### Overview

The service layer depends on the `ExchangeRateProvider` interface, never on the FRED
implementation. Two services use it:

```
CurrencyService (validates provider series IDs)     ExchangeRateImportService (imports rates)
            \                                                    /
             '------------> ExchangeRateProvider (interface) <--'
                                      ^ implements
                            FredExchangeRateProvider
                                      | uses
                            FredClient (HTTP to the FRED API)
```

The interface has two methods:

```java
public interface ExchangeRateProvider {

  Map<LocalDate, BigDecimal> getExchangeRates(CurrencySeries currencySeries, LocalDate startDate);

  boolean validateSeriesExists(String providerSeriesId);
}
```

### Dependency Rules

**Allowed:**
- `CurrencyService` and `ExchangeRateImportService` inject `ExchangeRateProvider`.
- `FredExchangeRateProvider` implements `ExchangeRateProvider` and uses `FredClient`.
- `FredClient` (in `client.fred`) handles HTTP calls to the FRED API.

**Forbidden:**
- A service in `service` importing `FredExchangeRateProvider`, `FredClient`, or anything from
  `client.fred`.
- FRED-specific types in service method signatures.

### Implementation

`CurrencyService` validates a new series against the provider and maps provider failures to a
service error:

```java
private final ExchangeRateProvider exchangeRateProvider;

private void validateProviderSeriesId(String providerSeriesId) {
  boolean exists;
  try {
    exists = exchangeRateProvider.validateSeriesExists(providerSeriesId);
  } catch (ClientException e) {
    throw new ServiceUnavailableException(
        "Unable to validate provider series ID due to external provider API error: "
            + e.getMessage(),
        e);
  }
  // ...throws BusinessException INVALID_PROVIDER_SERIES_ID when !exists
}
```

`ExchangeRateImportService` fetches rates for one series from a start date (`null` means full
history):

```java
var dateRateMap = exchangeRateProvider.getExchangeRates(currencySeries, startDate);
```

See [fred-integration.md](fred-integration.md) for how imports choose the start date and handle
FRED errors.

### Adding a New Provider

1. Add a client for the new source in its own package under `client/`.
2. Implement `ExchangeRateProvider` in `service/provider/` using that client. Map the source's
   failures to `ClientException`, which is what the services expect.
3. Add the provider's settings to `CurrencyServiceProperties` and `application.yml`.
4. Make sure exactly one `ExchangeRateProvider` bean exists at runtime, because the services inject
   a single provider. Activate each implementation conditionally, for example with `@Profile` or
   `@ConditionalOnProperty`, including `FredExchangeRateProvider`.

The services need no changes.

### Discovery Commands

```bash
# Provider interface and implementations
find src/main/java -type f -name '*Provider.java'

# FRED client
cat src/main/java/org/budgetanalyzer/currency/client/fred/FredClient.java

# Services that depend on the provider interface
rg -n 'ExchangeRateProvider' src/main/java/org/budgetanalyzer/currency/service/*.java

# Services must not reference FRED types (expect no output)
rg -n 'client\.fred|FredExchangeRateProvider|FredClient' \
  src/main/java/org/budgetanalyzer/currency/service/*.java
```

## ShedLock Distributed Locking

### Overview

The daily import runs once per trigger across all pods, coordinated by a database lock in the
PostgreSQL `shedlock` table (created by `V2__add_shedlock_table.sql`).

### Implementation

```java
@Scheduled(cron = "${currency-service.exchange-rate-import.cron:0 0 23 * * ?}", zone = "UTC")
@SchedulerLock(name = "exchangeRateImport", lockAtMostFor = "15m", lockAtLeastFor = "1m")
public void importDailyRates() {
  // ...
  executeImport(1);
}
```

`executeImport` calls `ExchangeRateImportService.importLatestExchangeRates()`. If it throws and
attempts remain, it schedules the next attempt on the `TaskScheduler` bean:

```java
taskScheduler.schedule(
    () -> {
      log.info("Executing retry attempt {} (scheduled retry)", attemptNumber);
      executeImport(attemptNumber);
    },
    retryTime);
```

`ShedLockConfig` registers a `JdbcTemplateLockProvider` and sets
`@EnableSchedulerLock(defaultLockAtMostFor = "10m")`. The schedule, lock, and retry values are
listed in [configuration.md](configuration.md#scheduled-tasks).

### How It Works

1. The cron trigger fires on every pod at 23:00 UTC.
2. The first pod to write the `exchangeRateImport` row in `shedlock` runs the import.
3. Other pods find the lock held and skip that trigger.
4. When the method returns, the lock is released, but it is held for at least 1 minute.
5. If the pod dies, the lock expires 15 minutes after it was taken.

Retries are scheduled on the pod whose attempt failed. They run outside the ShedLock lock, because
only the `@Scheduled` method is locked.

Each import runs in one transaction across all enabled series. A failure in any series rolls back
the whole import, and the retry starts over.

The scheduler records Micrometer metrics: `exchange.rate.import.duration`,
`exchange.rate.import.executions`, `exchange.rate.import.retry.scheduled`, and
`exchange.rate.import.exhausted`.

### Lock Duration Guidelines

- `lockAtMostFor` must exceed the longest expected run. It only matters when a pod dies while
  holding the lock.
- `lockAtLeastFor` must be shorter than the schedule interval. It prevents another pod from running
  the same trigger when the task finishes quickly or pod clocks differ slightly.
- Give every scheduled task its own lock name.

### Adding New Scheduled Tasks

Add `@SchedulerLock` with a unique name to any `@Scheduled` method that must run once across pods:

```java
@Scheduled(cron = "0 0 2 * * *", zone = "UTC")
@SchedulerLock(name = "uniqueTaskName", lockAtMostFor = "10m", lockAtLeastFor = "30s")
public void runTask() {
  // Delegate to a service
}
```

### Discovery Commands

```bash
# Scheduled tasks and their locks
rg -n '@Scheduled|@SchedulerLock' src/main/java

# Lock provider configuration
rg -n 'EnableSchedulerLock|LockProvider' src/main/java/org/budgetanalyzer/currency/config

# Current locks (local database)
psql -d currency -c "SELECT * FROM shedlock;"
```

## Redis Distributed Caching

### Overview

`GET /v1/exchange-rates` is the only cached read. Entries have no TTL; every import evicts all of
them after it commits. The full cache rules are in [configuration.md](configuration.md#caching).

### Cached Read

```java
@Transactional(readOnly = true)
@Cacheable(
    cacheNames = CacheConfig.EXCHANGE_RATES_CACHE,
    key = "#targetCurrency.currencyCode + ':' + #startDate + ':' + #endDate")
public List<ExchangeRateData> getExchangeRates(
    Currency targetCurrency, LocalDate startDate, LocalDate endDate) {
  // ...business validation, query...
  if (definedRates.isEmpty()) {
    return Collections.emptyList();
  }
  // ...builds and returns an ArrayList with one rate per day
}
```

Requests that throw, such as a disabled currency, are not cached.

### Eviction After Import

Each import method in `ExchangeRateImportService` evicts all entries:

```java
@Transactional
@CacheEvict(cacheNames = CacheConfig.EXCHANGE_RATES_CACHE, allEntries = true)
public List<ExchangeRateImportResult> importLatestExchangeRates() {
  var seriesToImport = getAllEnabledSeries();
  return importExchangeRatesForSeries(seriesToImport);
}
```

Evicting everything keeps the code simple: an import can change rates for any currency, and imports
are rare. Because the cache manager is transaction-aware, the eviction runs after the import
commits; a rolled-back import leaves the cache unchanged.

### Cache Key Strategy

**Format:** `{currencyCode}:{startDate}:{endDate}`, stored as
`currency-service:exchangeRates::{currencyCode}:{startDate}:{endDate}`.

**Examples:**
- `currency-service:exchangeRates::EUR:2024-01-01:2024-01-31`
- `currency-service:exchangeRates::THB:2024-06-01:null` (no end date)
- `currency-service:exchangeRates::JPY:null:null` (all available data)

### Cache Configuration

`CacheConfig` builds the cache configuration in code, not from `spring.cache.redis.*`:

```java
@Bean
public RedisCacheConfiguration cacheConfiguration(
    GenericJacksonJsonRedisSerializer redisSerializer) {
  return RedisCacheConfiguration.defaultCacheConfig()
      .entryTtl(Duration.ZERO)
      .disableCachingNullValues()
      .serializeKeysWith(
          RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
      .serializeValuesWith(
          RedisSerializationContext.SerializationPair.fromSerializer(redisSerializer))
      .prefixCacheNameWith("currency-service:");
}

@Bean
public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(
    RedisConnectionFactory redisConnectionFactory) {
  return builder ->
      builder
          .cacheWriter(
              RedisCacheWriter.create(
                  redisConnectionFactory, configurer -> configurer.immediateWrites()))
          .transactionAware();
}
```

- **Synchronous writer:** `immediateWrites()` makes puts, evictions, and clears finish before the
  cache call returns, instead of the asynchronous Lettuce default in Spring Data Redis 4.
- **Typed JSON values:** the value serializer is a copy of the application `JsonMapper` with
  `DefaultTyping.NON_FINAL`, restricted by a `BasicPolymorphicTypeValidator` that accepts only
  subtypes from `org.budgetanalyzer`, `java.util`, `java.time`, and `java.math`.

### Immutable Collections Must Not Be Cached

Values of final classes are written without a type id. Jackson reads most of them back as their
natural JSON type, but a JDK immutable collection, such as the result of `List.of()` or
`Stream.toList()`, is written as a bare JSON array and fails to deserialize on the next cache read.
Return `ArrayList` or `Collections.emptyList()` from `@Cacheable` methods. This is why
`getExchangeRates` returns `Collections.emptyList()` for a range with no rates.

### When to Cache

**Good candidates:** reads that are repeated with the same parameters and change only on a known
write path that can evict them, such as exchange rates that change only on import.

**Bad candidates:** data that changes outside an evicting write path, rarely repeated queries, very
large results, and anything used for authorization decisions.

### Discovery Commands

```bash
# Cached methods and eviction points
rg -n '^\s*@(Cacheable|CacheEvict)\b' src/main/java

# Cache manager, serializer, and type allowlist
cat src/main/java/org/budgetanalyzer/currency/config/CacheConfig.java

# Redis connection and cache type
rg -n 'redis:|cache:' src/main/resources/application.yml
```

## Event-Driven Messaging

### Overview

Creating an enabled currency or enabling a disabled one requests an exchange-rate import through
RabbitMQ. The Spring Modulith transactional outbox makes the request durable: the publication is
stored in `event_publication` in the same transaction as the currency change.

### Why Transactional Outbox

Sending to RabbitMQ inside the database transaction can lose the message when the send fails after
the commit, or send a message for a change that later rolls back. The outbox stores the intent with
the business data and sends the message after commit.

### Implementation

**1. Domain events** (`domain/event/`) are records:

```java
public record CurrencyCreatedEvent(
    Long currencySeriesId, String currencyCode, boolean enabled, String correlationId) {}
```

`CurrencyUpdatedEvent` has the same fields.

**2. `CurrencyService` publishes them** inside its transaction, and only when an import is needed:

```java
var saved = currencySeriesRepository.save(currencySeries);
var correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);

if (saved.isEnabled()) {
  eventPublisher.publishEvent(
      new CurrencyCreatedEvent(
          saved.getId(), saved.getCurrencyCode(), saved.isEnabled(), correlationId));
}
```

`update` publishes `CurrencyUpdatedEvent` only when a series changes from disabled to enabled.

**3. `MessagingEventListener` bridges each event to RabbitMQ** after commit:

```java
@ApplicationModuleListener
void onCurrencyCreated(CurrencyCreatedEvent event) {
  MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, event.correlationId());
  MDC.put("eventType", "currency_created");

  try {
    exchangeRateImportMessagePublisher.publishExchangeRateImportRequested(
        new ExchangeRateImportRequestedMessage(
            event.currencySeriesId(), event.currencyCode(), event.correlationId()));
  } catch (Exception e) {
    // logged, then rethrown so Spring Modulith marks the publication failed
    throw e;
  } finally {
    MDC.clear();
  }
}
```

`ExchangeRateImportMessagePublisher` sends the message with
`streamBridge.send("exchangeRateImportRequested-out-0", message)`. The service never injects the
publisher.

**4. `ExchangeRateImportConsumer` imports the series:**

```java
@Bean
public Consumer<ExchangeRateImportRequestedMessage> importExchangeRates() {
  return message -> {
    MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, message.correlationId());
    MDC.put("eventType", "exchange_rate_import_requested");

    try {
      var result =
          exchangeRateImportService.importExchangeRatesForSeries(message.currencySeriesId());
      // ...logs the result
    } finally {
      MDC.clear();
    }
  };
}
```

Exceptions propagate to Spring Cloud Stream, which retries the message and then sends it to the
dead-letter queue. See [configuration.md](configuration.md#consumer-retry-and-dead-letters) for the
retry policy.

### Event Flow

1. `CurrencyService` publishes a domain event inside its transaction.
2. Spring Modulith stores a publication for the listener in `event_publication` in the same
   transaction.
3. The transaction commits the currency change and the publication together.
4. After commit, Spring Modulith invokes the `@ApplicationModuleListener` method asynchronously on
   the task executor.
5. The listener sends `ExchangeRateImportRequestedMessage` to `exchange-rate.import.requested`.
6. When the listener returns, Spring Modulith marks the publication completed
   (`status = 'COMPLETED'`, `completion_date` set). The row is kept permanently as an audit trail.
7. A consumer in the `exchange-rate-import-service` group imports rates for the series.

### Operational Logging

Currency enablement and import-trigger messaging emit info-level breadcrumbs across the flow:

1. `CurrencyService` logs when an update changes a currency from disabled to enabled and is about
   to publish the `CurrencyUpdatedEvent`.
2. `ExchangeRateImportMessagePublisher` logs every outbound
   `exchange-rate.import.requested` message with the binding, currency series ID, and currency
   code.
3. `ExchangeRateImportConsumer` logs the matching inbound consumption with the currency series ID
   and currency code before running the import.

The correlation ID from the original HTTP request travels in the event and the message and is put
in the MDC on both sides.

### Delivery Guarantees

**Normal operation:**
```
1. Save currency + store publication (one transaction)
2. Commit
3. Listener sends the message to RabbitMQ
4. Publication marked completed and kept
```

**Crash after commit, before the listener completes:**
```
1. Save currency + store publication, commit
2. [APPLICATION STOPS]
3. [RESTART] republish-outstanding-events-on-restart resubmits the incomplete publication
4. Listener sends the message, publication marked completed
```

**Listener fails (for example, RabbitMQ unavailable):**
```
1. Save currency + store publication, commit
2. Listener throws; publication marked FAILED and stays incomplete
3. Nothing retries it while the application keeps running
4. At the next startup the publication is resubmitted
```

A failed publication therefore waits for a restart. Delivery is at least once: a publication can be
resubmitted after RabbitMQ already accepted its message, so the consumer must tolerate duplicates.
A duplicate processed after the first import committed starts after the latest stored rate. See
[domain-model.md](domain-model.md#domain-events) for duplicate and concurrency behavior.

### Publication Retention

Completed publications are kept permanently in `event_publication` as an audit trail. This uses
Spring Modulith's default `completion-mode` (`update`) and is deliberate: nothing deletes or
archives completed rows. See [configuration.md](configuration.md#transactional-outbox) for the
growth rate and the V8 and U8 schema scripts.

### RabbitMQ Configuration

```yaml
spring:
  cloud:
    function:
      definition: importExchangeRates
    stream:
      bindings:
        exchangeRateImportRequested-out-0:
          destination: exchange-rate.import.requested
        importExchangeRates-in-0:
          destination: exchange-rate.import.requested
          group: exchange-rate-import-service
      rabbit:
        bindings:
          importExchangeRates-in-0:
            consumer:
              auto-bind-dlq: true
              republish-to-dlq: true
```

### Adding New Events

1. Add a record to `domain/event/`.
2. Publish it from a service method with `ApplicationEventPublisher`, inside the transaction that
   changes the data.
3. Add an `@ApplicationModuleListener` method in `messaging/listener/` that converts it to an
   external message record in `messaging/message/` and sends it through a publisher in
   `messaging/publisher/`.
4. Add the output binding and destination to `application.yml`, and document the new message.

### Discovery Commands

```bash
# Domain events
find src/main/java -type f -path '*/domain/event/*.java'

# Event publishers in services
rg -n 'publishEvent' src/main/java/org/budgetanalyzer/currency/service

# Outbox listeners, message sends, and consumers
rg -n '^\s*@ApplicationModuleListener|streamBridge\.send|Consumer<' \
  src/main/java/org/budgetanalyzer/currency/messaging

# Stream, Rabbit, and Modulith configuration
rg -n -A 19 '^  cloud:' src/main/resources/application.yml
rg -n -A 4 '^  modulith:' src/main/resources/application.yml

# Recent event publications (local database)
psql -d currency -c "SELECT event_type, status, publication_date, completion_date FROM event_publication ORDER BY publication_date DESC LIMIT 10;"
```

## Testing Advanced Patterns

Follow the
[canonical service-common testing patterns](https://github.com/budgetanalyzer/service-common/blob/main/docs/testing-patterns.md)
before adding or changing tests. Keep application-owned providers, services, repositories,
listeners, consumers, and schedulers real. Use the infrastructure boundary that matches the
behavior: Testcontainers for PostgreSQL, Redis, and RabbitMQ; WireMock for FRED HTTP responses.
Test fixtures live in `src/test/java/org/budgetanalyzer/currency/fixture/`.

### Provider Abstraction Testing

Run the real provider and FRED client against WireMock. Assert the provider result or mapped error,
not calls between application beans. `AbstractWireMockTest` supplies the WireMock server and the
Testcontainers application context.

```java
class FredExchangeRateProviderIntegrationTest extends AbstractWireMockTest {

  @Autowired private FredExchangeRateProvider fredExchangeRateProvider;

  @Test
  void shouldFetchFullHistoryWhenStartDateIsNull() {
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);

    var rates = fredExchangeRateProvider.getExchangeRates(eurSeries, null);

    // Sample data has 10 days; the two weekend observations are "." and are filtered out
    assertThat(rates).hasSize(8);
  }
}
```

See
[`FredExchangeRateProviderIntegrationTest`](../src/test/java/org/budgetanalyzer/currency/service/provider/FredExchangeRateProviderIntegrationTest.java)
for transformation, missing-data, and provider-error coverage, and
[`FredClientIntegrationTest`](../src/test/java/org/budgetanalyzer/currency/client/fred/FredClientIntegrationTest.java)
for HTTP error mapping.

### ShedLock Testing

Do not test ShedLock itself by starting multiple scheduler objects and verifying a service call
count. Test scheduler-owned import and retry outcomes with the real application service,
Testcontainers PostgreSQL, controlled WireMock responses, persisted rates, and metrics. When retry
timing must be deterministic, use a focused recording implementation of Spring's `TaskScheduler`
that exposes the scheduled task and time; do not replace the application import service.
[`ExchangeRateImportSchedulerIntegrationTest`](../src/test/java/org/budgetanalyzer/currency/scheduler/ExchangeRateImportSchedulerIntegrationTest.java)
does this with its `RecordingTaskScheduler`.

The full application integration context validates that the JDBC lock provider and Flyway-managed
`shedlock` table can start together. Multi-pod lock coordination belongs to deployed-system
verification.

### Redis Caching Testing

The test configuration sets `spring.cache.type: none`. Enable the real Redis cache only in
cache-specific tests, drive reads and mutations through the real API or service, and assert the
cache entry or the returned data.

```java
@TestPropertySource(properties = "spring.cache.type=redis")
class ExchangeRateImportCacheIntegrationTest extends AbstractControllerTest {

  @Test
  void shouldEvictCacheAfterImport() throws Exception {
    currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().build());
    var cache = cacheManager.getCache(CacheConfig.EXCHANGE_RATES_CACHE);
    cache.put("EUR:2024-01-01:2024-01-05", "some cached data");
    FredApiStubs.stubSuccessWithObservations(
        TestConstants.FRED_SERIES_EUR,
        List.of(new FredApiStubs.Observation("2024-01-01", "0.8500")));

    performPost("/v1/exchange-rates/import", "").andExpect(status().isOk());

    assertThat(cache.get("EUR:2024-01-01:2024-01-05")).isNull();
  }
}
```

See
[`ExchangeRateImportCacheIntegrationTest`](../src/test/java/org/budgetanalyzer/currency/api/ExchangeRateImportCacheIntegrationTest.java)
for eviction, cached empty and non-empty results read back from Redis, and the
`currency-service:` key namespace.
[`ExchangeRateImportServiceIntegrationTest`](../src/test/java/org/budgetanalyzer/currency/service/ExchangeRateImportServiceIntegrationTest.java)
covers eviction for each import method and an unchanged cache after rollback.

### Event-Driven Messaging Testing

Run the real listener, publisher, RabbitMQ binding, consumer, import service, and repositories.
Assert completed outbox publications and imported database rows instead of publisher invocation
counts.

```java
class EventListenerIntegrationTest extends AbstractWireMockTest {

  @Test
  void shouldOnlyPublishImportEventForEnabledCurrency() {
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    // ...same stubs for GBP
    var createdEnabled =
        currencyService.create(CurrencySeriesTestBuilder.defaultEur().enabled(true).build());
    var createdDisabled =
        currencyService.create(CurrencySeriesTestBuilder.defaultGbp().enabled(false).build());

    await()
        .atMost(WAIT_TIME, SECONDS)
        .untilAsserted(
            () -> {
              assertThat(testDatabaseHelper.countCompletedEvents()).isEqualTo(1L);
              assertThat(exchangeRateRepository.countByCurrencySeries(createdEnabled))
                  .isEqualTo(8L);
              assertThat(exchangeRateRepository.countByCurrencySeries(createdDisabled))
                  .isEqualTo(0L);
            });
  }
}
```

The test configuration keeps completed publications (`completion-mode: update`) so tests can count
them, and turns off resubmission on restart.

See [`EventListenerIntegrationTest`](../src/test/java/org/budgetanalyzer/currency/messaging/EventListenerIntegrationTest.java)
for enabled and disabled event filtering,
[`MessageConsumerIntegrationTest`](../src/test/java/org/budgetanalyzer/currency/messaging/MessageConsumerIntegrationTest.java)
for consumer behavior, and
[`EndToEndMessagingFlowIntegrationTest`](../src/test/java/org/budgetanalyzer/currency/integration/EndToEndMessagingFlowIntegrationTest.java)
for broker-driven consumption, concurrency, and imported-rate outcomes.

## Common Patterns and Best Practices

### Provider Pattern

**Do:**
- Depend on `ExchangeRateProvider` in services.
- Keep FRED-specific types in `client.fred` and `FredExchangeRateProvider`.
- Map provider failures to `ClientException`.

**Don't:**
- Import a concrete provider or client into a service.
- Mix provider response handling with business rules.

### Distributed Locking

**Do:**
- Use the PostgreSQL-backed lock provider.
- Set `lockAtMostFor` above the longest expected run and `lockAtLeastFor` below the schedule
  interval.
- Use a unique lock name per task.

**Don't:**
- Rely on in-memory locks.
- Reuse lock names across tasks.

### Caching

**Do:**
- Cache repeated reads that change only on a known write path.
- Evict on every write path that changes cached data.
- Return mutable `ArrayList` or `Collections.emptyList()` from cacheable methods.

**Don't:**
- Return `List.of()` or `Stream.toList()` results from cacheable methods.
- Use the cache for security decisions.

### Messaging

**Do:**
- Publish domain events from services inside the transaction.
- Bridge events to the broker in listeners.
- Make consumers tolerate duplicate messages.

**Don't:**
- Send to RabbitMQ from inside a service transaction.
- Inject message publishers into services.
- Assume exactly-once delivery.
