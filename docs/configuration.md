# Configuration

This document owns the configuration facts for the currency service: properties, environment
variables, and their defaults. [`application.yml`](../src/main/resources/application.yml) is the
source of truth; other documents link here instead of repeating values.

## Server

| Property | Value |
|---|---|
| `server.port` | `8084` |
| `server.servlet.context-path` | `/currency-service` |

Every direct URL therefore starts with `http://localhost:8084/currency-service`. See
[local-development.md](local-development.md#api-access) for direct and gateway URLs.

## Infrastructure Dependencies

| Component | Purpose | Default connection |
|---|---|---|
| PostgreSQL | Data, Flyway migrations, ShedLock locks, Spring Modulith `event_publication` | `jdbc:postgresql://localhost:5432/currency`, user `currency_service` |
| Redis | Exchange-rate query cache | `localhost:6379`, user `currency-service`, TLS off |
| RabbitMQ | Exchange-rate import request messages | `localhost:5671`, user `currency-service`, TLS off |
| FRED API | Exchange-rate data source | `https://api.stlouisfed.org/fred` |

The datasource URL is not a placeholder. Spring Boot's relaxed binding still lets
`SPRING_DATASOURCE_URL` override it.

Port `5671` is the AMQPS port, but RabbitMQ and Redis TLS default to off and their SSL bundles
default to empty. The local run in [local-development.md](local-development.md#running-locally)
turns TLS on for both and selects the `infra-ca` bundle, whose CA certificate comes from
`INFRA_CA_CERT_PATH`.

## Environment Variables

Every `${...}` placeholder in `application.yml` is listed below. Where two variables are shown,
the first wins and the second is a legacy fallback.

### Required

| Variable | Default | Description |
|---|---|---|
| `FRED_API_KEY` | none | FRED API key ([register here](https://fred.stlouisfed.org/docs/api/api_key.html)). The placeholder has no default, so startup fails without it. |
| `SPRING_DATASOURCE_PASSWORD` | empty | PostgreSQL password for the datasource user |
| `SPRING_DATA_REDIS_PASSWORD`, then `REDIS_PASSWORD` | empty | Redis password |
| `SPRING_RABBITMQ_PASSWORD`, then `RABBITMQ_PASSWORD` | empty | RabbitMQ password |

The passwords default to empty so that the placeholders always resolve. The platform's PostgreSQL,
Redis, and RabbitMQ require them.

### Optional

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_USERNAME` | `currency_service` | PostgreSQL user |
| `SPRING_DATA_REDIS_HOST`, then `REDIS_HOST` | `localhost` | Redis host |
| `SPRING_DATA_REDIS_PORT`, then `REDIS_PORT` | `6379` | Redis port |
| `SPRING_DATA_REDIS_USERNAME`, then `REDIS_USERNAME` | `currency-service` | Redis ACL user |
| `SPRING_DATA_REDIS_SSL_ENABLED` | `false` | Enable Redis TLS |
| `SPRING_DATA_REDIS_SSL_BUNDLE` | empty | SSL bundle for Redis TLS, for example `infra-ca` |
| `SPRING_RABBITMQ_HOST`, then `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `SPRING_RABBITMQ_PORT`, then `RABBITMQ_PORT` | `5671` | RabbitMQ port |
| `SPRING_RABBITMQ_USERNAME`, then `RABBITMQ_USERNAME` | `currency-service` | RabbitMQ user |
| `SPRING_RABBITMQ_SSL_ENABLED` | `false` | Enable RabbitMQ TLS |
| `SPRING_RABBITMQ_SSL_BUNDLE` | empty | SSL bundle for RabbitMQ TLS, for example `infra-ca` |
| `SPRING_RABBITMQ_SSL_VALIDATE_SERVER_CERTIFICATE` | `true` | Validate the broker certificate when TLS is on |
| `SPRING_RABBITMQ_SSL_VERIFY_HOSTNAME` | `true` | Check the broker hostname against its certificate when TLS is on |
| `INFRA_CA_CERT_PATH` | empty | Location of the infrastructure CA PEM for the `infra-ca` bundle (`spring.ssl.bundle.pem.infra-ca.truststore.certificate`) |

## Application Properties

`currency-service.exchange-rate-import.*` binds to `CurrencyServiceProperties`:

| Property | `application.yml` value | Notes |
|---|---|---|
| `cron` | `0 0 23 * * ?` | Daily import trigger, evaluated in UTC |
| `import-on-startup` | `true` | Run the import once when the application is ready. If it fails, startup fails. |
| `fred.base-url` | `https://api.stlouisfed.org/fred` | FRED API root |
| `fred.api-key` | `${FRED_API_KEY}` | Required, must not be blank |
| `fred.timeout-seconds` | `30` | Per-request timeout, 1 to 120 |
| `retry.max-attempts` | `3` | Total scheduled-import attempts, including the first, 1 to 10 |
| `retry.delay-minutes` | `1` | Delay between scheduled-import attempts, 1 to 60. The Java default is 5; `application.yml` sets 1. |

`currency-service.messaging.*` also binds to `CurrencyServiceProperties`:

| Property | `application.yml` value | Notes |
|---|---|---|
| `publish-confirm-timeout-seconds` | `10` | Longest wait for the RabbitMQ publisher confirm of an import request, 1 to 60. See [Publisher Confirms](#publisher-confirms). |

`budgetanalyzer.service.http-logging.*` configures service-common's HTTP request logging. It logs
request bodies, not response bodies, at `DEBUG`, up to 10000 bytes. Actuator, Swagger UI, and
OpenAPI paths are excluded by default.

## Caching

- **Store:** Redis (`spring.cache.type: redis`), cache-aside on `GET /v1/exchange-rates` only.
  `ExchangeRateService.getExchangeRates` is the only `@Cacheable` method, in the cache
  `exchangeRates`. Currency series reads are not cached.
- **Key:** `currency-service:exchangeRates::{currencyCode}:{startDate}:{endDate}`. An omitted date
  appears as `null`. The `currency-service:` prefix keeps the service's keys apart from other
  services that share the Redis instance.
- **TTL:** one hour (`CacheConfig.EXCHANGE_RATES_TTL`), counted from when each entry is written.
  The TTL exists for two reasons:
  - A cache-aside read whose query started before an import committed can write its stale result
    after that import's after-commit eviction. Without a TTL, that entry would stay until the next
    import, up to 24 hours later. The TTL limits it to one hour.
  - Keys include arbitrary date ranges, so the TTL also bounds the Redis memory the cache uses.

  Spring Data Redis writes each entry with a single `SET … PX`, which the service's Redis ACL
  (`+set`) allows. The TTL is fixed in code, not a configuration property, and there is no
  time-to-idle: refreshing expiry on read needs `GETEX`, which the ACL does not grant.
- **Eviction:** every `ExchangeRateImportService` import method evicts all `exchangeRates` entries
  (`@CacheEvict(allEntries = true)`), regardless of the TTL. The cache manager is
  `transactionAware`, so the eviction runs only after the import transaction commits. A rolled-back
  import leaves the cache unchanged.
- **Writes:** the cache writer uses `immediateWrites`, so puts, evictions, and clears finish before
  the cache call returns.
- **Values:** JSON with Jackson default typing for non-final types. On read, type ids are accepted
  only from `org.budgetanalyzer`, `java.util`, `java.time`, and `java.math`; any other type is
  rejected. Null values are not cached.
- **Collection rule:** cached values must not be JDK immutable collections, such as the results of
  `List.of()` or `Stream.toList()`. Their classes are final, so they are written without a type id
  and cannot be read back. Return `ArrayList` or `Collections.emptyList()` from cacheable methods.

`CacheConfig` defines the only `RedisCacheConfiguration`, so `spring.cache.redis.*` properties
other than `cache-names` and `enable-statistics` have no effect. Neither is set, so cache statistics
are off. See
[advanced-patterns-usage.md](advanced-patterns-usage.md#redis-distributed-caching) for the code.

## Scheduled Tasks

| Task | Schedule | Lock | Retry |
|---|---|---|---|
| `ExchangeRateImportScheduler.importDailyRates` | `0 0 23 * * ?` in UTC (23:00 UTC daily), from `currency-service.exchange-rate-import.cron` | ShedLock `exchangeRateImport`, `lockAtMostFor = 15m`, `lockAtLeastFor = 1m` | In process, from `currency-service.exchange-rate-import.retry.*` |

- The task imports the latest rates for every **enabled** currency series.
- ShedLock stores locks in the PostgreSQL `shedlock` table and uses database time
  (`usingDbTime()`): lock timestamps come from the PostgreSQL server clock in UTC, not pod clocks.
  `@EnableSchedulerLock` sets a default `lockAtMostFor` of `10m` for locks that do not set their
  own.
- When an attempt throws, the scheduler schedules the next attempt `retry.delay-minutes` later on
  its own `TaskScheduler`, until `retry.max-attempts` attempts have run. Retries run on the pod that
  ran the failed attempt and do not take the ShedLock lock.
- Scheduling uses a 2-thread pool (`spring.task.scheduling.pool.size`) with thread prefix
  `scheduler-`. Asynchronous work, including Spring Modulith listeners, uses the task executor
  (`spring.task.execution`: core 5, max 10, queue 100, prefix `async-task-`). Both wait up to 60 s
  for running tasks at shutdown.

See [advanced-patterns-usage.md](advanced-patterns-usage.md#shedlock-distributed-locking) for
distributed locking details.

## Event-Driven Messaging

| Item | Value |
|---|---|
| Outbound binding | `exchangeRateImportRequested-out-0` |
| Inbound binding | `importExchangeRates-in-0` (function `importExchangeRates`) |
| Destination | `exchange-rate.import.requested` |
| Consumer group | `exchange-rate-import-service` |
| Domain events | `CurrencyCreatedEvent`, `CurrencyUpdatedEvent` |
| External message | `ExchangeRateImportRequestedMessage` |

All pods share the consumer group, so each import request is consumed by one pod.

### Publisher Confirms

Import request sends use RabbitMQ publisher confirms and returns:

| Property | Value | Effect |
|---|---|---|
| `spring.rabbitmq.publisher-confirm-type` | `correlated` | The broker confirms each publish, and Spring AMQP completes the message's `CorrelationData` with an ack or a nack. |
| `spring.rabbitmq.publisher-returns` | `true` | The broker returns an unroutable mandatory message, and Spring AMQP attaches it to its `CorrelationData`. |
| `spring.cloud.stream.rabbit.bindings.exchangeRateImportRequested-out-0.producer.use-confirm-header` | `true` | The binder publishes the message as mandatory and correlates the confirm and return through the `CorrelationData` in the message's `AmqpHeaders.PUBLISH_CONFIRM_CORRELATION` header. |

No producer `error-channel-enabled` or `confirm-ack-channel` is set. `PublisherConfirmIntegrationTest`
shows that these three keys alone correlate acks, nacks, and returns.

`ExchangeRateImportMessagePublisher` waits for the confirm for at most
`currency-service.messaging.publish-confirm-timeout-seconds`. It returns only when the broker acked
the message and did not return it. Otherwise it throws `MessagePublishException`.

- **Where the wait runs:** only on the Spring Modulith task-executor thread (`async-task-*`) that
  runs the outbox listener after the currency transaction commits. HTTP responses never wait for
  RabbitMQ.
- **How long it lasts:** a healthy broker confirms within milliseconds. A persistent message on a
  durable queue can take tens of milliseconds while the broker writes it to disk. The timeout
  bounds the wait only when the broker is unhealthy.
- **Why each message is awaited:** import requests are low volume, one per enabled currency create
  or enablement. Waiting on each message limits throughput to hundreds of messages per second,
  which this service never approaches.
- **Nack reason:** a nack can arrive without a reason. For example, RabbitMQ gives none when a
  `x-overflow: reject-publish` queue is full.

### Consumer Retry and Dead Letters

No retry properties are set, so Spring Cloud Stream's consumer defaults apply: 3 attempts, 1 s
initial back-off, multiplier 2, and 10 s maximum back-off. After the last attempt, the Rabbit
binder republishes the message to the dead-letter queue (`auto-bind-dlq: true`,
`republish-to-dlq: true`), with the exception details in its headers. To change the retry policy,
set `spring.cloud.stream.bindings.importExchangeRates-in-0.consumer.max-attempts` and the
`back-off-*` keys beside it.

Because the connection factory uses correlated confirms, the binder's DLQ republish also waits up
to 10 s, a value fixed in the binder, for its own confirm. If the broker nacks or returns the
republished message, or the wait times out, the binder rejects the original message without
requeueing it instead of acknowledging it.

### Transactional Outbox

- Spring Modulith stores each domain-event publication in `event_publication` in the same
  transaction as the currency change.
- When the listener succeeds, the publication is completed in the default `completion-mode`
  (`update`): it gets a `completion_date` and `status = 'COMPLETED'`. **`COMPLETED` means the
  broker confirmed and routed the import request message.**
- **Every completed publication is kept permanently as an audit trail.** This is deliberate. There
  is no time-based cleanup or archiving, so `event_publication` grows by one row for each
  import-triggering currency create or update, which is one row per import request sent.
- Spring Modulith invokes the listener once per publication. A listener that throws leaves the
  publication `FAILED` and incomplete. A crash after commit but before the listener completes
  leaves it `PUBLISHED` or `PROCESSING`. Nothing resubmits either one.
- The listener fails, and the publication is marked `FAILED`, in any of these cases:
  - The send cannot reach RabbitMQ, for example because the connection is unavailable.
  - The broker nacks the message.
  - The broker returns the message as unroutable.
  - No confirm arrives within the [confirm timeout](#publisher-confirms).
- Incomplete publications stay in `event_publication` as a record of failed or interrupted sends.
  Find them with `status <> 'COMPLETED'` or `completion_date IS NULL`. Because confirms make the
  statuses accurate, this finds every failed send. The exception is a crash during the confirm wait,
  which leaves the publication `PROCESSING`.
- [Consumer retries and the dead-letter queue](#consumer-retry-and-dead-letters) cover only
  messages that reached RabbitMQ. They never retry a failed outbox send.

#### Outbox Recovery

The outbox records the import request atomically with the currency change and never sends for a
rolled-back change. Delivery of a failed or lost send relies on import reconciliation:

- The startup import (`import-on-startup`, default `true`) and the daily scheduled import (23:00
  UTC) each import every enabled series from the day after its latest stored rate, or the full
  history when none is stored.
- A currency whose import request was lost therefore catches up at the next startup or 23:00 UTC
  run. With `import-on-startup: false`, it waits for the scheduled run.

The service does not set `spring.modulith.events.republish-outstanding-events-on-restart`, and it
intentionally adds no application-written resubmission code (no `FailedEventPublications` or
`IncompleteEventPublications` calls and no scheduled resubmission job):

- The
  [Spring Modulith property reference](https://docs.spring.io/spring-modulith/reference/appendix.html)
  says restart republication is usually not recommended in multi-instance deployments, because
  other instances might still be processing events. In Spring Modulith 2.1.x the startup path
  resubmits every incomplete publication, including ones another pod is still processing.
- A single publication that cannot be deserialized makes startup fail on every restart
  ([spring-modulith#1904](https://github.com/spring-projects/spring-modulith/issues/1904), open).
- Upstream proposes deprecating the property in favor of application-written resubmission
  ([spring-modulith#1916](https://github.com/spring-projects/spring-modulith/issues/1916)).
- The reconciliation imports already cover every import request a resubmitted publication would
  send.

### Event Publication Schema

- `V5__add_event_publication_table.sql` creates `event_publication`.
- `V8__add_modulith_2_event_publication_columns.sql` adds the Spring Modulith 2 `status`,
  `completion_attempts`, and `last_resubmission_date` columns.
- `U8__rollback_modulith_2_event_publication_columns.sql` documents the manual rollback of V8.
  Flyway Community never runs `U*` scripts. U8 is valid only as part of a downgrade to Spring
  Modulith 1, because Spring Modulith 2 cannot load publications without those columns.

See [advanced-patterns-usage.md](advanced-patterns-usage.md#event-driven-messaging) for the code
and event flow.

## Security

The service uses service-common's claims-header security. It trusts the `X-User-Id`,
`X-Permissions`, and `X-Roles` headers only as set by the platform's validated gateway path, and
every endpoint enforces a `currencies:read` or `currencies:write` permission with `@PreAuthorize`.
Health, Swagger UI, and OpenAPI paths are public. See
[api/README.md](api/README.md#authentication-and-authorization) for the endpoint permissions and the
orchestration
[security architecture](https://github.com/budgetanalyzer/orchestration/blob/main/docs/architecture/security-architecture.md)
for the gateway and session model. The permission service owns roles and permissions.
