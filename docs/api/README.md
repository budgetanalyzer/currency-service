# Currency Service - API Documentation

**Service:** currency-service
**Base URL (Local):** http://localhost:8084
**Gateway URL:** http://localhost:8080/api/v1
**API Version:** v1

## Overview

This service provides RESTful APIs for currency series and exchange rate management. Includes automated import from FRED (Federal Reserve Economic Data).

## Quick Start

### Access API Documentation

**Swagger UI (Interactive):**
```bash
# Start service
./gradlew bootRun

# Open browser
open http://localhost:8084/swagger-ui.html
```

**OpenAPI Spec:**
```bash
# View spec
curl http://localhost:8084/v3/api-docs

# Download spec
curl http://localhost:8084/v3/api-docs > openapi.json
```

### Test Endpoints

```bash
# Health check
curl http://localhost:8084/actuator/health

# List currency series
curl http://localhost:8080/api/v1/currencies

# Get exchange rates
curl "http://localhost:8080/api/v1/exchange-rates?seriesId={id}&startDate=2025-01-01&endDate=2025-11-10"
```

## API Endpoints

### Currency Series

**List Currency Series**
```
GET /api/v1/currencies
Query params: active (true/false)
Response: List<CurrencySeries>
```

**Get Currency Series**
```
GET /api/v1/currencies/{id}
Response: CurrencySeries
```

**Create Currency Series**
```
POST /api/v1/currencies
Body: CurrencySeriesRequest
Response: CurrencySeries (201 Created)
```

**Update Currency Series**
```
PUT /api/v1/currencies/{id}
Body: CurrencySeriesRequest
Response: CurrencySeries
```

**Delete Currency Series**
```
DELETE /api/v1/currencies/{id}
Response: 204 No Content
```

**Activate/Deactivate Series**
```
PATCH /api/v1/currencies/{id}/active
Body: {"active": true}
Response: CurrencySeries
```

### Exchange Rates

**Query Exchange Rates**
```
GET /api/v1/exchange-rates
Query params: seriesId (required), startDate, endDate
Response: List<ExchangeRate>
```

**Get Latest Rate**
```
GET /api/v1/exchange-rates/latest/{seriesId}
Response: ExchangeRate
```

**Import Rates (Manual Trigger)**
```
POST /api/v1/exchange-rates/import/{seriesId}
Query params: startDate, endDate
Response: ImportResponse
```

### Admin Endpoints

**Trigger Import for All Active Series**
```
POST /api/v1/admin/import-all
Response: BulkImportResponse
```

**View Import Status**
```
GET /api/v1/admin/import-status
Response: ImportStatusResponse
```

**Clear Cache**
```
POST /api/v1/admin/cache/clear
Query params: pattern (optional)
Response: CacheClearResponse
```

## Request/Response Examples

### CurrencySeriesRequest

```json
{
  "seriesCode": "DEXTHUS",
  "sourceCurrency": "THB",
  "targetCurrency": "USD",
  "name": "Thailand Baht to US Dollar Exchange Rate",
  "provider": "FRED",
  "active": true
}
```

### CurrencySeries Response

```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "seriesCode": "DEXTHUS",
  "sourceCurrency": "THB",
  "targetCurrency": "USD",
  "name": "Thailand Baht to US Dollar Exchange Rate",
  "provider": "FRED",
  "active": true,
  "lastImportDate": "2025-11-10",
  "createdAt": "2025-01-01T00:00:00Z",
  "updatedAt": "2025-11-10T06:00:00Z"
}
```

### ExchangeRate Response

```json
{
  "id": "987e6543-e21b-12d3-a456-426614174000",
  "seriesId": "123e4567-e89b-12d3-a456-426614174000",
  "rateDate": "2025-11-10",
  "rate": 0.02857,
  "provider": "FRED",
  "importedAt": "2025-11-10T06:00:00Z"
}
```

### ImportResponse

```json
{
  "seriesId": "123e4567-e89b-12d3-a456-426614174000",
  "seriesCode": "DEXTHUS",
  "imported": 45,
  "skipped": 5,
  "failed": 0,
  "startDate": "2025-09-01",
  "endDate": "2025-11-10",
  "durationMs": 1234
}
```

## Error Handling

**Pattern**: Standardized error response format with HTTP status codes, error types, and field-level validation messages.

**When to consult @service-common/docs/error-handling.md**:
- Understanding error response format → See ApiErrorResponse Format
- Choosing correct exception type → Review Exception Hierarchy
- Implementing business rule violations → Check BusinessException examples
- Handling validation errors → See Bean Validation Integration

**Standard HTTP Status Codes:**
- `200 OK` - Successful GET/PUT/PATCH
- `201 Created` - Successful POST
- `204 No Content` - Successful DELETE
- `400 Bad Request` - Validation error or invalid request
- `404 Not Found` - Resource not found
- `422 Unprocessable Entity` - Business rule violation (includes error code)
- `500 Internal Server Error` - Server error
- `503 Service Unavailable` - External provider unavailable

**Quick reference**:
- `ResourceNotFoundException` → 404 (entity not found)
- `InvalidRequestException` → 400 (bad input)
- `BusinessException` → 422 (business rule violation with error code)
- `ServiceUnavailableException` → 503 (FRED API down)
- All exceptions auto-converted to standardized format by `DefaultApiExceptionHandler`

**Error Response Example:**
```json
{
  "type": "APPLICATION_ERROR",
  "message": "Currency series not found",
  "code": "SERIES_NOT_FOUND"
}
```

**For complete error handling patterns: @service-common/docs/error-handling.md**

## Authentication & Authorization

**Status:** Not yet implemented

**Future:**
- Admin endpoints require authentication
- Read-only access for regular users
- API keys for programmatic access

**Current:** All endpoints publicly accessible (local dev only)

## Rate Limiting

**FRED API Limits:**
- 120 requests per minute
- Handled internally with retry + backoff

**Service Limits:**
- Not yet implemented
- Future: Per-user rate limits

## Caching

**Pattern**: Redis distributed cache (cache-aside) for high-performance queries.

**When to consult @service-common/docs/advanced-patterns.md#redis-distributed-caching**:
- Understanding cache strategy → See Redis Caching section
- Adjusting TTL values → Review Configuration
- Adding new cached endpoints → Check Service Layer Annotations

**Cached Endpoints:**
- `GET /api/v1/exchange-rates` - 1 hour TTL
- `GET /api/v1/exchange-rates/latest/{seriesId}` - 30 minutes TTL
- `GET /api/v1/currencies` - 6 hours TTL

**Cache Headers:**
```
Cache-Control: max-age=3600
ETag: "{hash}"
```

**Cache Invalidation:**
- Automatic on import
- Manual via admin endpoint
- TTL expiration

**Quick reference**:
- `@Cacheable` on GET endpoints
- `@CacheEvict(allEntries = true)` after imports
- Expected hit rate: 80-95%

## Validation Rules

### CurrencySeries

- `seriesCode` - Required, 1-50 characters, unique
- `sourceCurrency` - Required, valid ISO 4217 code
- `targetCurrency` - Required, valid ISO 4217 code, ≠ sourceCurrency
- `name` - Required, 1-200 characters
- `provider` - Required, must be "FRED"
- `active` - Required, boolean

### ExchangeRate

- `seriesId` - Required, valid UUID, series must exist
- `rateDate` - Required, not in future
- `rate` - Required, positive number (> 0)

## Scheduled Operations

### Daily Import

**Schedule:** 6:00 AM UTC
**Duration:** ~5-10 minutes (depends on active series count)
**Behavior:**
- Imports last 30 days for all active series
- Skips existing dates (idempotent)
- Uses ShedLock to prevent concurrent runs

**Monitoring:**
```bash
# View last import status
curl http://localhost:8084/api/v1/admin/import-status

# Trigger manual import (testing)
curl -X POST http://localhost:8084/api/v1/admin/import-all
```

## Discovery Commands

```bash
# Find all controllers
grep -r "@RestController" src/main/java

# Find all endpoints
grep -r "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping" src/main/java

# View scheduled jobs
grep -r "@Scheduled" src/main/java

# Check Redis cache keys
docker exec redis redis-cli KEYS "exchange-rates:*"
```

## References

- **Swagger UI:** http://localhost:8084/swagger-ui.html (when service running)
- **OpenAPI Spec:** http://localhost:8084/v3/api-docs
- **Domain Model:** [../domain-model.md](../domain-model.md)
- **Database Schema:** [../database-schema.md](../database-schema.md) (not yet created)
- **Error Handling:** @service-common/docs/error-handling.md
- **Advanced Patterns:** @service-common/docs/advanced-patterns.md
