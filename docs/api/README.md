# Currency Service - API Reference

This service exposes REST endpoints for the currency series catalog and for USD-based exchange
rates imported from FRED (Federal Reserve Economic Data).

The generated OpenAPI document is the authoritative contract for request and response schemas.
This page summarizes routes, permissions, validation, and behavior that the schema alone does not
explain.

## Paths and Entry Points

All controller routes live under `/v1`. The service runs on port `8084` with the servlet context
path `/currency-service`. The two ways to reach a route therefore differ only in prefix:

| Access | Example |
|---|---|
| Gateway (browser and frontend) | `https://app.budgetanalyzer.localhost/api/v1/currencies` |
| Direct to the service | `http://localhost:8084/currency-service/v1/currencies` |

The gateway maps the resource path `/api/v1/...` to `/currency-service/v1/...` on this service. See
the orchestration
[resource-based routing pattern](https://github.com/budgetanalyzer/orchestration/blob/main/docs/architecture/resource-routing-pattern.md)
for how routing works and where the routes are defined.

Generated documentation, served directly by the service:

| Resource | URL |
|---|---|
| OpenAPI JSON | `http://localhost:8084/currency-service/v3/api-docs` |
| Swagger UI | `http://localhost:8084/currency-service/swagger-ui.html` |
| Health check | `http://localhost:8084/currency-service/actuator/health` |

The ecosystem-wide API docs surface is `https://app.budgetanalyzer.localhost/api-docs`. See the
orchestration
[getting-started guide](https://github.com/budgetanalyzer/orchestration/blob/main/docs/development/getting-started.md).

## Authentication and Authorization

The service uses service-common's claims-header security. It reads the `X-User-Id`,
`X-Permissions`, and `X-Roles` headers that the platform's ingress external-auth path sets after it
validates the session. Clients must not supply these headers themselves. Call the API through the
gateway; a direct call without claims headers returns `401`. Health and OpenAPI routes are public.

Every endpoint enforces one permission with `@PreAuthorize`:

| Permission | Endpoints |
|---|---|
| `currencies:read` | `GET /v1/currencies`, `GET /v1/currencies/{id}`, `GET /v1/exchange-rates` |
| `currencies:write` | `POST /v1/currencies`, `PUT /v1/currencies/{id}`, `POST /v1/exchange-rates/import` |

Missing authentication returns `401 UNAUTHORIZED`. Missing the required permission returns
`403 FORBIDDEN`. The permission-service owns roles and permission assignment.

## Endpoints

| Method | Path | Permission | Success |
|---|---|---|---|
| `GET` | `/v1/currencies` | `currencies:read` | `200` list of `CurrencySeriesResponse` |
| `POST` | `/v1/currencies` | `currencies:write` | `201` `CurrencySeriesResponse` with a `Location` header |
| `GET` | `/v1/currencies/{id}` | `currencies:read` | `200` `CurrencySeriesResponse` |
| `PUT` | `/v1/currencies/{id}` | `currencies:write` | `200` `CurrencySeriesResponse` |
| `GET` | `/v1/exchange-rates` | `currencies:read` | `200` list of `ExchangeRateResponse` |
| `POST` | `/v1/exchange-rates/import` | `currencies:write` | `200` list of `ExchangeRateImportResultResponse` |

### `GET /v1/currencies`

Lists currency series.

| Query parameter | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `enabledOnly` | boolean | No | `false` | `true` returns only enabled series; `false` returns all series |

Flyway migration `V6` seeds 23 FRED-backed currencies, all disabled. Enable one with
`PUT /v1/currencies/{id}`.

### `POST /v1/currencies`

Creates a currency series. The 23 seeded currencies already exist, so this endpoint is for currency
pairs that FRED adds later.

Request body (`CurrencySeriesCreateRequest`):

```json
{
  "currencyCode": "EUR",
  "providerSeriesId": "DEXUSEU",
  "enabled": true
}
```

The service checks `currencyCode` against ISO 4217 and asks the exchange-rate provider whether
`providerSeriesId` exists. If the new series is enabled, the service publishes a
`CurrencyCreatedEvent`, which requests an asynchronous import of its full rate history. See
[domain-model.md](../domain-model.md#domain-events).

| Status | Error type | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `currencyCode` or `providerSeriesId` fails a Bean Validation rule |
| `400` | `INVALID_REQUEST` | `enabled` is missing or `null`, or the body is not valid JSON (see [Validation Rules](#validation-rules)) |
| `422` | `APPLICATION_ERROR`, code `INVALID_ISO_4217_CODE` | `currencyCode` is three uppercase letters but not an ISO 4217 code |
| `422` | `APPLICATION_ERROR`, code `INVALID_PROVIDER_SERIES_ID` | The provider reports that the series does not exist |
| `422` | `APPLICATION_ERROR`, code `DUPLICATE_CURRENCY_CODE` | Any unique-constraint violation on insert, including a duplicate `providerSeriesId` |
| `503` | `SERVICE_UNAVAILABLE` | The provider could not be reached to validate `providerSeriesId` |

### `GET /v1/currencies/{id}`

Returns one currency series by its numeric `id`. An unknown `id` returns `404 NOT_FOUND`.

### `PUT /v1/currencies/{id}`

Updates the `enabled` flag. `currencyCode` and `providerSeriesId` cannot be changed.

Request body (`CurrencySeriesUpdateRequest`):

```json
{
  "enabled": true
}
```

If a disabled series becomes enabled, the service publishes a `CurrencyUpdatedEvent`, which
requests an asynchronous import from the day after the latest stored rate (or the full history if
none is stored). Disabling a series publishes nothing and keeps its stored rates.

An unknown `id` returns `404 NOT_FOUND`. A missing or `null` `enabled` returns `400 INVALID_REQUEST`
(see [Validation Rules](#validation-rules)) and leaves the series unchanged.

### `GET /v1/exchange-rates`

Returns one rate per calendar day, quoted as units of `targetCurrency` per 1 USD.

| Query parameter | Type | Required | Meaning |
|---|---|---|---|
| `targetCurrency` | ISO 4217 code | Yes | Currency to quote against USD |
| `startDate` | ISO date (`yyyy-MM-dd`) | No | First day to return; defaults to the earliest stored rate in range |
| `endDate` | ISO date (`yyyy-MM-dd`) | No | Last day to return; defaults to the latest stored rate in range |

Response behavior:

- FRED does not publish rates for weekends and holidays. Each missing day carries the most recent
  earlier published rate, and `publishedDate` gives the date that rate was published.
- `baseCurrency` is always `USD`. Rates from "USD per foreign currency" series (`DEXUS*`) are
  inverted and rounded to 4 decimal places.
- A valid range that contains no stored rates returns `200` with an empty list.

```json
[
  {
    "baseCurrency": "USD",
    "targetCurrency": "THB",
    "date": "2025-11-02",
    "rate": 32.68,
    "publishedDate": "2025-10-31"
  }
]
```

| Status | Error type | When |
|---|---|---|
| `400` | `INVALID_REQUEST` | `targetCurrency` is missing or not an ISO 4217 code, a date is not an ISO date, or `startDate` is after `endDate` |
| `422` | `APPLICATION_ERROR`, code `CURRENCY_NOT_ENABLED` | No enabled series exists for `targetCurrency` |
| `422` | `APPLICATION_ERROR`, code `NO_EXCHANGE_RATE_DATA_AVAILABLE` | The series is enabled but no rates are stored yet |
| `422` | `APPLICATION_ERROR`, code `START_DATE_OUT_OF_RANGE` | `startDate` is before the earliest stored rate |

### `POST /v1/exchange-rates/import`

Runs the same import as the daily job, synchronously, for every enabled series. For each series it
fetches rates from the day after the latest stored rate, or the full history if none is stored. It
takes no request body or parameters.

Each response element (`ExchangeRateImportResultResponse`) reports `currencyCode`,
`providerSeriesId`, `newRecords`, `updatedRecords`, `skippedRecords`, the nullable
`earliestExchangeRateDate` and `latestExchangeRateDate`, and the `timestamp` of the import. The list
is empty when no series is enabled.

This endpoint does not take the ShedLock lock that the scheduled job uses.

## Validation Rules

Request shape is validated at the API boundary. Business rules are enforced in the service layer
and return `422 APPLICATION_ERROR` with a `code`.

| Field | Rules | Failure |
|---|---|---|
| `currencyCode` (create) | Required, not blank, exactly 3 characters, matches `^[A-Z]{3}$` | `400 VALIDATION_ERROR` |
| `currencyCode` (create) | Must be an ISO 4217 code | `422 INVALID_ISO_4217_CODE` |
| `currencyCode` (create) | Must be unique | `422 DUPLICATE_CURRENCY_CODE` |
| `providerSeriesId` (create) | Required, not blank, at most 50 characters | `400 VALIDATION_ERROR` |
| `providerSeriesId` (create) | Must exist at the provider and be unique | `422 INVALID_PROVIDER_SERIES_ID` or `422 DUPLICATE_CURRENCY_CODE` |
| `enabled` (create and update) | Required boolean; there is no default | `400 INVALID_REQUEST` |
| `targetCurrency` (query) | Required ISO 4217 code | `400 INVALID_REQUEST` |
| `startDate`, `endDate` (query) | Optional ISO dates, `startDate` not after `endDate` | `400 INVALID_REQUEST` |

**`enabled` is required.** Both request records declare it as a primitive `boolean`, and the
OpenAPI schema lists it as required. Jackson 3 rejects a missing or `null` value
(`FAIL_ON_NULL_FOR_PRIMITIVES`) instead of defaulting it to `false`, so the request is never
processed. The service-common exception handler maps the resulting
`HttpMessageNotReadableException` to `400 INVALID_REQUEST`, with no `fieldErrors`. Malformed JSON
bodies get the same response. Before Jackson 3, a missing `enabled` silently became `false`, which
disabled the series on update. Clients must now send it explicitly.

## Error Responses

Every error body uses the shared `ApiErrorResponse` schema with `type` and `message`, plus `code`
for `APPLICATION_ERROR` and `fieldErrors` for `VALIDATION_ERROR`:

```json
{
  "type": "APPLICATION_ERROR",
  "message": "Currency is not enabled: EUR",
  "code": "CURRENCY_NOT_ENABLED"
}
```

The service-specific `code` values come from `CurrencyServiceError`. For the full list of error
types, the exception-to-status mapping, and the standard OpenAPI error responses, see
[service-common error handling](https://github.com/budgetanalyzer/service-common/blob/main/docs/error-handling.md).

## Caching

Only `GET /v1/exchange-rates` is cached. `ExchangeRateService.getExchangeRates` is `@Cacheable` in
the Redis cache `exchangeRates`:

- **Key:** `currency-service:exchangeRates::{targetCurrency}:{startDate}:{endDate}`. An omitted
  date appears as `null`, for example `currency-service:exchangeRates::EUR:null:null`.
- **TTL:** one hour from when the entry is written. See
  [configuration.md](../configuration.md#caching) for why.
- **Eviction:** every import method evicts all `exchangeRates` entries with
  `@CacheEvict(allEntries = true)`. The cache manager is transaction-aware, so eviction runs only
  after the import transaction commits.
- Requests that end in an error are not cached.

The cache is server-side only. The service sets no caching or validation headers of its own (no
`ETag`); responses carry Spring Security's default no-store cache-control headers. See
[advanced-patterns-usage.md](../advanced-patterns-usage.md#redis-distributed-caching) and
[configuration.md](../configuration.md) for details.

## Rate Limiting

This service does not rate-limit requests. The platform applies general per-client API rate
limiting at the Istio ingress, as described in the orchestration
[resource-based routing pattern](https://github.com/budgetanalyzer/orchestration/blob/main/docs/architecture/resource-routing-pattern.md).
For FRED's own limits, see [fred-integration.md](../fred-integration.md#fred-api-rate-limits).

## Scheduled and Startup Imports

**Daily import.** `ExchangeRateImportScheduler.importDailyRates` runs
`importLatestExchangeRates()`, the same import as `POST /v1/exchange-rates/import`:

- **Schedule:** cron `currency-service.exchange-rate-import.cron`, default `0 0 23 * * ?`
  (23:00 UTC), zone `UTC`.
- **Lock:** ShedLock lock `exchangeRateImport` with `lockAtMostFor = 15m` and `lockAtLeastFor = 1m`,
  so one pod runs the job per trigger.
- **Retry:** a failed run is retried up to `currency-service.exchange-rate-import.retry.max-attempts`
  total attempts (default 3), `retry.delay-minutes` apart (default 1). Retries run on the task
  scheduler outside the ShedLock lock.

**Startup import.** When `currency-service.exchange-rate-import.import-on-startup` is `true` (the
default), the same import runs once on `ApplicationReadyEvent`. If it fails, startup fails.

Imports are idempotent. Dates already stored with the same rate are counted as `skippedRecords`.
A stored rate that changed upstream is overwritten and counted as `updatedRecords`.

## Discovery Commands

Run these from the repository root:

```bash
# Controllers and routes
rg -n '@RestController|@RequestMapping|@(Get|Post|Put|Patch|Delete)Mapping' src/main/java

# Endpoint permissions
rg -n '@PreAuthorize' src/main/java

# Request validation rules
rg -n '@NotBlank|@NotNull|@Size|@Pattern' src/main/java/org/budgetanalyzer/currency/api

# Business error codes
rg -n 'CurrencyServiceError\.' src/main/java

# Cache reads and evictions
rg -n '@Cacheable|@CacheEvict' src/main/java

# Scheduled jobs and locks
rg -n '@Scheduled|@SchedulerLock' src/main/java

# Generated contract (service running locally)
curl -s http://localhost:8084/currency-service/v3/api-docs
```

## References

- [Domain model](../domain-model.md)
- [Configuration](../configuration.md)
- [FRED integration](../fred-integration.md)
- [Advanced patterns usage](../advanced-patterns-usage.md)
- [Local development](../local-development.md)
- [service-common error handling](https://github.com/budgetanalyzer/service-common/blob/main/docs/error-handling.md)
- [service-common advanced patterns](https://github.com/budgetanalyzer/service-common/blob/main/docs/advanced-patterns.md)
