# Currency Service - Domain Model

**Status:** Active
**Service:** currency-service

## Overview

This service manages currencies and exchange rates with automated import from external providers (currently FRED - Federal Reserve Economic Data).

## Core Entities

### CurrencySeries

**Purpose:** Represents a currency exchange rate time series

**Key Attributes:**
- `id` (UUID) - Unique identifier
- `seriesCode` (String) - FRED series code (e.g., "DEXTHUS" for THB/USD)
- `sourceCurrency` (String) - Source currency code (e.g., "THB")
- `targetCurrency` (String) - Target currency code (e.g., "USD")
- `name` (String) - Human-readable name
- `provider` (String) - Data provider ("FRED")
- `active` (Boolean) - Whether actively importing
- `lastImportDate` (LocalDate) - Last successful import

**Business Rules:**
- Series code must be unique
- Currency codes must be valid ISO 4217
- Source and target currencies must be different
- Active series imported daily

**Discovery:**
```bash
# Find CurrencySeries entity
grep -r "class CurrencySeries" src/main/java
```

### ExchangeRate

**Purpose:** Represents a single exchange rate observation

**Key Attributes:**
- `id` (UUID) - Unique identifier
- `seriesId` (UUID) - Reference to CurrencySeries
- `rateDate` (LocalDate) - Date of observation
- `rate` (BigDecimal) - Exchange rate value
- `provider` (String) - Data provider
- `importedAt` (Instant) - When imported

**Business Rules:**
- One rate per series per date (unique constraint)
- Rate must be positive
- Historical data immutable
- Rate date cannot be in future

**Discovery:**
```bash
# Find ExchangeRate entity
grep -r "class ExchangeRate" src/main/java
```

## Domain Relationships

```
CurrencySeries 1 ──→ * ExchangeRate
```

**One-to-many:** A currency series has many exchange rate observations over time.

## Aggregates

### CurrencySeries Aggregate

**Root:** CurrencySeries
**Entities:**
- CurrencySeries (root)
- ExchangeRate (child)

**Aggregate Boundary:**
- Always load ExchangeRate through CurrencySeries
- Delete cascade: Deleting series deletes all rates
- Import operations modify entire aggregate

## Value Objects

### ExchangeRateValue

**Components:**
- `rate` (BigDecimal) - Rate value
- `date` (LocalDate) - Observation date

**Business Rules:**
- Rate must be positive (> 0)
- Precision: Up to 10 decimal places
- Immutable

### CurrencyPair

**Components:**
- `sourceCurrency` (String) - From currency
- `targetCurrency` (String) - To currency

**Business Rules:**
- Both currencies required
- Must be different
- Valid ISO 4217 codes
- Immutable

## Domain Services

### ExchangeRateImportService

**Responsibilities:**
- Coordinate import from external providers
- Transform provider data to domain entities
- Ensure data consistency

**Key Operations:**
- `importRatesForSeries(CurrencySeries, DateRange)` - Import rates for a series
- `importAllActiveSeries()` - Scheduled import for all active series

### ExchangeRateQueryService

**Responsibilities:**
- Query exchange rates
- Date range filtering
- Rate interpolation (future)

**Key Operations:**
- `getRatesForSeries(seriesId, startDate, endDate)` - Query rates
- `getLatestRate(seriesId)` - Get most recent rate

## External Provider Integration

**Pattern**: Provider abstraction decouples service from specific external data sources.

**When to consult @service-common/docs/advanced-patterns.md#provider-abstraction-pattern**:
- Adding new providers (ECB, Bloomberg)
- Understanding provider interface design
- Modifying provider implementations

**Quick reference**:
- Interface: `ExchangeRateProvider`
- Implementation: `FredExchangeRateProvider`
- Service never imports FRED directly

**Data Flow:**
```
1. Scheduled job triggers
2. ExchangeRateImportService queries provider
3. FredExchangeRateProvider calls FRED API
4. Provider transforms FRED response to domain model
5. Service saves ExchangeRate entities
6. Transaction commits
7. Event published (CurrencyImportedEvent)
```

## Domain Events

### CurrencySeriesCreated

**When:** New currency series added
**Data:** seriesId, seriesCode, currencies

### ExchangeRatesImported

**When:** Successful import from provider
**Data:** seriesId, count, dateRange, provider

### ImportFailed

**When:** Import fails (provider error, validation error)
**Data:** seriesId, error message, timestamp

## Caching Strategy

**Pattern**: Cache-aside with Redis for high-performance queries.

**When to consult @service-common/docs/advanced-patterns.md#redis-distributed-caching**:
- Adjusting TTL values
- Adding new cached queries
- Understanding cache key design

**Quick reference**:
- Cached: Recent exchange rates (last 90 days) + series metadata
- Keys: `exchange-rates:{seriesId}:{date}`, `currency-series:{id}`
- TTL: Historical (24h), recent (1h), metadata (6h)

## Scheduled Operations

**Pattern**: ShedLock ensures scheduled tasks run once across all pods.

**When to consult @service-common/docs/advanced-patterns.md#shedlock-distributed-locking**:
- Adjusting lock durations
- Adding new scheduled jobs
- Debugging multi-pod coordination

**Quick reference**:
- **Daily Import** - 6:00 AM UTC (30m lock, idempotent)
- **Cache Warmup** - Every hour (5m lock)

## Discovery Commands

```bash
# Find all entities
find src/main/java -name "*Entity*.java"

# Find domain services
grep -r "@Service" src/main/java | grep -i "domain\|import\|query"

# View provider implementations
find src/main/java -name "*Provider*.java"

# Check scheduled jobs
grep -r "@Scheduled" src/main/java
```

## Business Rules Summary

### Currency Series Rules
1. Series code must be unique
2. Active series imported daily
3. Source ≠ target currency
4. Provider must be supported (currently: FRED only)

### Exchange Rate Rules
1. One rate per series per date
2. Rates must be positive
3. Historical data immutable
4. Rates cached for performance

### Import Rules
1. Scheduled daily at 6:00 AM UTC
2. Idempotent (duplicate dates skipped)
3. Distributed lock prevents concurrent imports
4. Failed imports retry next scheduled run

## References

- **Database Schema:** [database-schema.md](database-schema.md) (not yet created)
- **API Spec:** [api/README.md](api/README.md)
- **Advanced Patterns:** @service-common/docs/advanced-patterns.md
- **FRED Integration:** [fred-integration.md](fred-integration.md) (future)
