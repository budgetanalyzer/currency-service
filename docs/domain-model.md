# Currency Service - Domain Model

This service keeps a catalog of currency series and the daily USD exchange rates imported for them
from an external provider. FRED (Federal Reserve Economic Data) is currently the only provider.

The JPA entities live in `src/main/java/org/budgetanalyzer/currency/domain/`. Flyway migrations
`V1`–`V8` in `src/main/resources/db/migration/` own the schema, and Hibernate only validates it
(`ddl-auto: validate`).

## Entities

Both entities extend service-common's `AuditableEntity`, which adds:

| Field | Column | Notes |
|---|---|---|
| `createdAt` | `created_at` | Set on insert, not updatable |
| `updatedAt` | `updated_at` | Set on insert and update |
| `createdBy` | `created_by` | Up to 50 characters, from Spring Data auditing, not updatable |
| `updatedBy` | `updated_by` | Up to 50 characters, from Spring Data auditing |

Neither entity is soft-deletable.

### CurrencySeries

Table `currency_series`. One row maps an ISO 4217 currency to the provider series that supplies its
rates.

| Field | Column | Type | Rules |
|---|---|---|---|
| `id` | `id` | `Long` | Identity primary key |
| `currencyCode` | `currency_code` | `VARCHAR(3)` | Not null, unique |
| `providerSeriesId` | `provider_series_id` | `VARCHAR(50)` | Not null, unique (`uk_currency_series_provider_series_id`) |
| `enabled` | `enabled` | `BOOLEAN` | Not null; the entity defaults to `true` |

Rules:

- `currencyCode` must be a valid ISO 4217 code. `CurrencyService` checks it on create.
- `providerSeriesId` must exist at the provider. `CurrencyService` checks it through
  `ExchangeRateProvider` on create.
- `currencyCode` and `providerSeriesId` cannot be changed after creation. Only `enabled` can be
  updated.
- `enabled = false` is how a series is retired. Disabled series are not imported and cannot be
  queried for rates, but their stored rates are kept.
- `isUsdPerForeignSeries()` is `true` when `providerSeriesId` starts with `DEXUS`. Those FRED
  series report USD per foreign unit, for example `DEXUSEU`. Other series, such as `DEXTHUS`, report
  foreign units per USD.

Migration `V6` seeds 23 currencies, all disabled.

### ExchangeRate

Table `exchange_rate`. One row is one published rate for a currency pair on one date.

| Field | Column | Type | Rules |
|---|---|---|---|
| `id` | `id` | `Long` | Identity primary key |
| `currencySeries` | `currency_series_id` | `CurrencySeries` | Not null, lazy `@ManyToOne`, foreign key `fk_exchange_rate_currency_series` |
| `baseCurrency` | `base_currency` | `Currency` (`VARCHAR(3)`) | Not null |
| `targetCurrency` | `target_currency` | `Currency` (`VARCHAR(3)`) | Not null |
| `date` | `date` | `LocalDate` | Not null |
| `rate` | `rate` | `BigDecimal` (`NUMERIC(38, 4)`) | Precision 38, scale 4; the column is nullable |

Rules:

- `(base_currency, target_currency, date)` is unique (`uk_exchange_rate_currency_date`).
- The stored direction follows the provider series. For a `DEXUS*` series, `baseCurrency` is the
  foreign currency and `targetCurrency` is USD. For other series, `baseCurrency` is USD and
  `targetCurrency` is the foreign currency.
- The currency pair duplicates data that is reachable through `currencySeries`. The duplication is
  deliberate: indexed queries can filter on the pair without loading the series.
- An import overwrites a stored rate only when the provider returns a different value for the same
  date.

## Relationship

```text
CurrencySeries 1 ──── * ExchangeRate   (exchange_rate.currency_series_id)
```

The relationship is mapped only on `ExchangeRate`. `CurrencySeries` has no collection of rates, and
nothing cascades from a series to its rates. The foreign key uses the `RESTRICT` delete rule, so deleting a
series that still has rates violates `fk_exchange_rate_currency_series`. No API deletes a series;
disable it instead.

## Services

| Service | Responsibilities |
|---|---|
| `CurrencyService` | Creates, reads, lists, and updates currency series. Validates the ISO 4217 code and the provider series ID on create. Publishes `CurrencyCreatedEvent` and `CurrencyUpdatedEvent`. |
| `ExchangeRateService` | Answers rate queries for an enabled currency over an optional date range. Returns one USD-based rate per day and fills weekends and holidays with the latest earlier rate. Results are cached. |
| `ExchangeRateImportService` | Imports rates from `ExchangeRateProvider`: `importLatestExchangeRates()` for all enabled series (scheduler, startup, and the import endpoint) and `importExchangeRatesForSeries(id)` for one series (message consumer). `importMissingExchangeRates()`, for enabled series with no stored rates, has no production caller. Every import inserts new dates, updates changed rates, skips unchanged ones, and evicts the rate cache. |

The service layer reaches the provider only through the `ExchangeRateProvider` interface;
`FredExchangeRateProvider` is the implementation. See [fred-integration.md](fred-integration.md)
and the [provider abstraction usage](advanced-patterns-usage.md#provider-abstraction-pattern).

Business rule violations raise `BusinessException` with a `CurrencyServiceError` code:

| Code | Raised by | When |
|---|---|---|
| `DUPLICATE_CURRENCY_CODE` | `CurrencyService.create` | Any unique-constraint violation on insert, including a duplicate `providerSeriesId` |
| `INVALID_ISO_4217_CODE` | `CurrencyService.create` | `currencyCode` is not an ISO 4217 code |
| `INVALID_PROVIDER_SERIES_ID` | `CurrencyService.create` | The provider reports that the series does not exist |
| `CURRENCY_NOT_ENABLED` | `ExchangeRateService.getExchangeRates` | No enabled series exists for the currency |
| `NO_EXCHANGE_RATE_DATA_AVAILABLE` | `ExchangeRateService.getExchangeRates` | The series has no stored rates |
| `START_DATE_OUT_OF_RANGE` | `ExchangeRateService.getExchangeRates` | `startDate` is before the earliest stored rate |

## Domain Events

Domain events live in `domain/event/`. `CurrencyService` publishes them inside its transaction, and
Spring Modulith stores each one in `event_publication` as part of that transaction (transactional
outbox).

| Event | Published when | Fields |
|---|---|---|
| `CurrencyCreatedEvent` | An enabled series is created | `currencySeriesId`, `currencyCode`, `enabled`, `correlationId` |
| `CurrencyUpdatedEvent` | A disabled series is changed to enabled | `currencySeriesId`, `currencyCode`, `enabled`, `correlationId` |

Creating a disabled series, disabling a series, and updates that leave `enabled` unchanged publish
nothing.

Event flow:

```text
CurrencyService (transaction)
  └─> CurrencyCreatedEvent / CurrencyUpdatedEvent  → stored in event_publication
        (after commit, asynchronously)
        └─> MessagingEventListener (@ApplicationModuleListener)
              └─> ExchangeRateImportRequestedMessage → exchange-rate.import.requested (RabbitMQ)
                    └─> ExchangeRateImportConsumer
                          └─> ExchangeRateImportService.importExchangeRatesForSeries(currencySeriesId)
```

`ExchangeRateImportRequestedMessage` (in `messaging/message/`) carries `currencySeriesId`,
`currencyCode`, and `correlationId`. The import starts the day after the latest stored rate for the
series, or imports the full history if none is stored.

Publication lifecycle:

- When the listener succeeds, the publication is marked completed and kept permanently as an audit
  record. This is Spring Modulith's default `completion-mode` (`update`). No cleanup or archiving
  is configured.
- When the listener fails, the publication stays incomplete. Nothing retries it while the
  application runs. `republish-outstanding-events-on-restart: true` resubmits every incomplete
  publication at the next startup.
- Delivery is therefore at least once, and the consumer must tolerate duplicate import requests. A
  duplicate processed after an earlier import committed starts after the latest stored rate and
  skips dates already stored with the same rate. Duplicates processed concurrently can collide on
  `uk_exchange_rate_currency_date`, in which case the losing import fails and its transaction
  rolls back.

See [advanced-patterns-usage.md](advanced-patterns-usage.md#event-driven-messaging) for the
messaging configuration.

## Caching

`ExchangeRateService.getExchangeRates` is the only cached method. It uses the Redis cache
`exchangeRates`:

- **Key:** `currency-service:exchangeRates::{currencyCode}:{startDate}:{endDate}`. An omitted date
  appears as `null`.
- **TTL:** none. Entries stay until they are evicted.
- **Eviction:** each `ExchangeRateImportService` import method evicts every `exchangeRates` entry
  (`@CacheEvict(allEntries = true)`). The cache manager is transaction-aware, so eviction happens
  only after the import commits.

Currency series reads are not cached. See `CacheConfig` and
[advanced-patterns-usage.md](advanced-patterns-usage.md#redis-distributed-caching).

## Scheduled Import

`ExchangeRateImportScheduler.importDailyRates` imports the latest rates for every enabled series:

- **Schedule:** daily at 23:00 UTC (`currency-service.exchange-rate-import.cron`, default
  `0 0 23 * * ?`, zone `UTC`).
- **Lock:** ShedLock lock `exchangeRateImport` with `lockAtMostFor = 15m` and
  `lockAtLeastFor = 1m`, so one pod runs each trigger.
- **Retry:** up to `retry.max-attempts` total attempts (default 3), `retry.delay-minutes` apart
  (default 1).

The same import also runs once at startup when `import-on-startup` is `true` (the default), and on
demand through `POST /v1/exchange-rates/import`. See
[advanced-patterns-usage.md](advanced-patterns-usage.md#shedlock-distributed-locking).

## Discovery Commands

Run these from the repository root:

```bash
# Entities, domain events, and the external message
find src/main/java/org/budgetanalyzer/currency/domain src/main/java/org/budgetanalyzer/currency/messaging/message -name '*.java' | sort

# Schema migrations, including constraints and foreign keys
rg -n 'UNIQUE|FOREIGN KEY|REFERENCES|ON DELETE' src/main/resources/db/migration

# Services and business error codes
find src/main/java/org/budgetanalyzer/currency/service -maxdepth 1 -name '*.java' | sort
rg -n 'CurrencyServiceError\.' src/main/java

# Event publishing and listeners
rg -n 'publishEvent|@ApplicationModuleListener' src/main/java

# Provider implementations
find src/main/java -name '*Provider.java'

# Caching and scheduled work
rg -n '@Cacheable|@CacheEvict|@Scheduled|@SchedulerLock' src/main/java
```

## References

- [API reference](api/README.md)
- [FRED integration](fred-integration.md)
- [Advanced patterns usage](advanced-patterns-usage.md)
- [Configuration](configuration.md)
- [service-common Spring Boot conventions](https://github.com/budgetanalyzer/service-common/blob/main/docs/spring-boot-conventions.md)
- [service-common advanced patterns](https://github.com/budgetanalyzer/service-common/blob/main/docs/advanced-patterns.md)
