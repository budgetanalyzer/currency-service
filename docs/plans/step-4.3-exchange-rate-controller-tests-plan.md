# Step 4.3: ExchangeRateController Tests - Detailed Implementation Plan

## Overview
Implement comprehensive integration tests for the **read-only public endpoint** `GET /v1/exchange-rates` to validate REST API contracts, JSON serialization, HTTP status codes, and query parameter handling.

## Test File
- **Location**: `src/test/java/org/budgetanalyzer/currency/api/ExchangeRateControllerTest.java`
- **Extends**: `AbstractControllerTest` (provides MockMvc + WireMock + TestContainers)
- **Estimated Tests**: 15-18 test methods

## Test Structure (Following Existing Pattern)

### A. Happy Path Tests (5 tests)

#### 1. shouldReturnExchangeRatesForValidDateRange
- **Query**: `?targetCurrency=EUR&startDate=2024-01-01&endDate=2024-12-31`
- **Setup**: Save EUR series + 365 exchange rates
- **Verify**: 200 OK, JSON array, correct date range, all fields present
- **Assertions**:
  - `status().isOk()`
  - `content().contentType(MediaType.APPLICATION_JSON)`
  - `jsonPath("$").isArray()`
  - `jsonPath("$.length()").value(365)`
  - `jsonPath("$[*].baseCurrency").value(everyItem(is("USD")))`
  - `jsonPath("$[*].targetCurrency").value(everyItem(is("EUR")))`

#### 2. shouldReturnExchangeRatesWithOptionalStartDate
- **Query**: `?targetCurrency=EUR&endDate=2024-12-31` (no startDate)
- **Setup**: Save EUR series + rates from earliest available to Dec 31
- **Verify**: 200 OK, returns all rates up to endDate
- **Test**: Service determines earliest date from database

#### 3. shouldReturnExchangeRatesWithOptionalEndDate
- **Query**: `?targetCurrency=EUR&startDate=2024-01-01` (no endDate)
- **Setup**: Save EUR series + rates from Jan 1 onwards
- **Verify**: 200 OK, returns all rates from startDate onwards
- **Test**: Service uses latest available date or current date

#### 4. shouldReturnExchangeRatesWithoutDateParameters
- **Query**: `?targetCurrency=EUR` (no date params)
- **Setup**: Save EUR series + all available rates
- **Verify**: 200 OK, returns all rates for currency
- **Test**: Service handles both optional parameters missing

#### 5. shouldReturnGapFilledExchangeRates
- **Query**: `?targetCurrency=EUR&startDate=2024-01-01&endDate=2024-01-10`
- **Setup**: Save EUR series + rates for weekdays only (Mon-Fri, Jan 1-5)
- **Verify**: Response includes weekend dates (Jan 6-7) with gap-filled rates
- **Critical Assertion**:
  - Weekend entries have `publishedDate != date`
  - Weekend entries use Friday's rate (forward-fill logic)
  - Total 10 entries (5 weekdays + 2 weekend days + 3 weekdays)

### B. Validation Error Tests (400 Bad Request) (5 tests)

#### 6. shouldReturn400WhenTargetCurrencyIsMissing
- **Query**: `?startDate=2024-01-01&endDate=2024-12-31` (no targetCurrency)
- **Verify**: 400, error type = VALIDATION_ERROR
- **Assertion**: `jsonPath("$.type").value("VALIDATION_ERROR")`

#### 7. shouldReturn400WhenTargetCurrencyIsInvalid
- **Query**: `?targetCurrency=INVALID&startDate=2024-01-01`
- **Verify**: 400, invalid ISO 4217 format error
- **Note**: Spring's Currency binding validation may reject before controller

#### 8. shouldReturn400WhenStartDateIsAfterEndDate
- **Query**: `?targetCurrency=EUR&startDate=2024-12-31&endDate=2024-01-01`
- **Verify**: 400, message = "Start date must be before or equal to end date"
- **Code Reference**: ExchangeRateController.java:115-117
- **Assertion**: `jsonPath("$.message").value("Start date must be before or equal to end date")`

#### 9. shouldReturn400ForInvalidDateFormat
- **Query**: `?targetCurrency=EUR&startDate=2024-13-01` (invalid month)
- **Verify**: 400, date parsing error
- **Spring Validation**: `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)` rejects

#### 10. shouldReturn400ForMalformedDateFormat
- **Query**: `?targetCurrency=EUR&startDate=not-a-date`
- **Verify**: 400, date format error
- **Spring Validation**: Type conversion failure

### C. Business Error Tests (422 Unprocessable Entity) (3 tests)

#### 11. shouldReturn422WhenNoCurrencySeriesExists
- **Query**: `?targetCurrency=ZAR` (valid ISO 4217 but no series in DB)
- **Setup**: Empty database (or only EUR/THB series)
- **Verify**: 422, code = NO_EXCHANGE_RATE_DATA_AVAILABLE
- **Service Logic**: ExchangeRateService throws BusinessException
- **Assertion**: `jsonPath("$.code").value("NO_EXCHANGE_RATE_DATA_AVAILABLE")`

#### 12. shouldReturn422WhenStartDateOutOfRange
- **Query**: `?targetCurrency=EUR&startDate=1900-01-01&endDate=2024-01-01`
- **Setup**: EUR series with data starting from 2000-01-03 only
- **Verify**: 422, code = START_DATE_OUT_OF_RANGE
- **Assertion**:
  - `jsonPath("$.code").value("START_DATE_OUT_OF_RANGE")`
  - `jsonPath("$.message").value(containsString("2000-01-03"))`

#### 13. shouldReturn422WhenNoDataAvailableForDateRange
- **Query**: `?targetCurrency=EUR&startDate=2030-01-01&endDate=2030-12-31`
- **Setup**: EUR series with data only up to 2024
- **Verify**: 422, business validation error (no data in future range)
- **Expected Behavior**: Service validates date range against available data

### D. Response Structure Validation (3 tests)

#### 14. shouldReturnCorrectJsonStructureWithAllFields
- **Query**: `?targetCurrency=EUR&startDate=2024-01-02&endDate=2024-01-02`
- **Setup**: Single EUR rate for Jan 2, 2024 with rate 0.8500
- **Verify**: Array with 1 element containing all fields:
  ```json
  [{
    "baseCurrency": "USD",
    "targetCurrency": "EUR",
    "date": "2024-01-02",
    "rate": 0.8500,
    "publishedDate": "2024-01-02"
  }]
  ```
- **Assertions**:
  - `jsonPath("$[0].baseCurrency").isString()`
  - `jsonPath("$[0].baseCurrency").value("USD")`
  - `jsonPath("$[0].targetCurrency").isString()`
  - `jsonPath("$[0].targetCurrency").value("EUR")`
  - `jsonPath("$[0].date").isString()`
  - `jsonPath("$[0].date").value("2024-01-02")`
  - `jsonPath("$[0].rate").isNumber()`
  - `jsonPath("$[0].rate").value(0.85)`
  - `jsonPath("$[0].publishedDate").isString()`
  - `jsonPath("$[0].publishedDate").value("2024-01-02")`

#### 15. shouldReturnEmptyArrayWhenNoRatesInRange
- **Query**: `?targetCurrency=EUR&startDate=2025-01-01&endDate=2025-01-31`
- **Setup**: EUR series with no rates in 2025
- **Verify**: 200 OK, empty JSON array `[]`
- **Assertion**:
  - `status().isOk()`
  - `jsonPath("$").isArray()`
  - `jsonPath("$.length()").value(0)`

#### 16. shouldHandleMultipleCurrencies
- **Query 1**: `?targetCurrency=EUR&startDate=2024-01-01&endDate=2024-01-10`
- **Query 2**: `?targetCurrency=THB&startDate=2024-01-01&endDate=2024-01-10`
- **Setup**: Both EUR and THB series with rates for Jan 1-10
- **Verify**: Each query returns only rates for requested currency
- **Assertions**:
  - EUR query: `jsonPath("$[*].targetCurrency").value(everyItem(is("EUR")))`
  - THB query: `jsonPath("$[*].targetCurrency").value(everyItem(is("THB")))`
  - Verify no cross-contamination

### E. Edge Cases (2-3 tests)

#### 17. shouldHandleLeapYearDate
- **Query**: `?targetCurrency=EUR&startDate=2024-02-29&endDate=2024-02-29`
- **Setup**: EUR rate for Feb 29, 2024 (leap year)
- **Verify**: 200 OK, correctly returns leap year date
- **Assertion**: `jsonPath("$[0].date").value("2024-02-29")`

#### 18. shouldHandleCaseInsensitiveCurrency (Optional - depends on implementation)
- **Query 1**: `?targetCurrency=eur`
- **Query 2**: `?targetCurrency=EUR`
- **Verify**: Behavior matches controller implementation
- **Note**: Spring's Currency binding may auto-uppercase or reject lowercase

#### 19. shouldHandleSameDateForStartAndEnd (Optional)
- **Query**: `?targetCurrency=EUR&startDate=2024-01-02&endDate=2024-01-02`
- **Setup**: EUR rate for Jan 2
- **Verify**: 200 OK, returns single date (inclusive range)

## Test Setup Pattern

```java
package org.budgetanalyzer.currency.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import org.budgetanalyzer.currency.base.AbstractControllerTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.ExchangeRateTestBuilder;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;

/**
 * Integration tests for {@link ExchangeRateController}.
 *
 * <p>Tests the read-only public endpoint for querying exchange rates:
 *
 * <ul>
 *   <li>GET /v1/exchange-rates - Query exchange rates by currency and date range
 * </ul>
 *
 * <p>These are full integration tests covering HTTP layer → Controller → Service → Repository →
 * Database using real PostgreSQL via TestContainers.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Happy paths (with/without date parameters, gap-filling)
 *   <li>Validation errors (missing params, invalid formats, date range validation)
 *   <li>Business errors (no data available, date out of range)
 *   <li>Response structure validation (JSON schema, data types)
 *   <li>Edge cases (leap years, multiple currencies, empty results)
 * </ul>
 */
@SpringBootTest
class ExchangeRateControllerTest extends AbstractControllerTest {

  @Autowired private CurrencySeriesRepository seriesRepository;
  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @BeforeEach
  void setUp() {
    exchangeRateRepository.deleteAll();
    seriesRepository.deleteAll();
  }

  // ===========================================================================================
  // A. Happy Path Tests
  // ===========================================================================================

  @Test
  @DisplayName("GET /v1/exchange-rates - should return exchange rates for valid date range")
  void shouldReturnExchangeRatesForValidDateRange() throws Exception {
    // Test implementation
  }

  // ... more test methods organized by category (A, B, C, D, E)
}
```

## Helper Methods

```java
/**
 * Saves a currency series with exchange rates for a date range.
 *
 * @param currencyCode the ISO 4217 currency code
 * @param startDate the start date (inclusive)
 * @param endDate the end date (inclusive)
 * @param rate the exchange rate value for all dates
 */
private void saveExchangeRatesForSeries(
    String currencyCode, LocalDate startDate, LocalDate endDate, BigDecimal rate) {
  var series = CurrencySeriesTestBuilder.defaultEur()
      .withCurrencyCode(currencyCode)
      .build();
  seriesRepository.save(series);

  var rates = ExchangeRateTestBuilder.buildDateRange(series, startDate, endDate, rate);
  exchangeRateRepository.saveAll(rates);
}

/**
 * Saves a currency series with exchange rates for weekdays only.
 *
 * @param currencyCode the ISO 4217 currency code
 * @param startDate the start date (inclusive)
 * @param endDate the end date (inclusive)
 * @param rate the exchange rate value for all dates
 */
private void saveWeekdayRatesForSeries(
    String currencyCode, LocalDate startDate, LocalDate endDate, BigDecimal rate) {
  var series = CurrencySeriesTestBuilder.defaultEur()
      .withCurrencyCode(currencyCode)
      .build();
  seriesRepository.save(series);

  var rates = ExchangeRateTestBuilder.buildWeekdaysOnly(series, startDate, endDate, rate);
  exchangeRateRepository.saveAll(rates);
}
```

## Key Testing Considerations

### 1. No WireMock Needed
- **Reason**: Read-only endpoint, no external FRED API calls
- **Focus**: Database query and service logic only

### 2. Gap-Filling Logic
- **Critical Test**: Verify weekend/holiday handling
- **Assertion Strategy**: Check `publishedDate` field differs from `date` for gap-filled entries
- **Example**:
  ```
  Date: 2024-01-06 (Saturday)
  Rate: 0.8500
  PublishedDate: 2024-01-05 (Friday) <- Gap-filled from previous weekday
  ```

### 3. Date Range Filtering
- **Inclusive Ranges**: Both startDate and endDate are inclusive
- **Edge Cases**: Single-day ranges (start == end)
- **Boundary Testing**: Exact match on earliest/latest available dates

### 4. JSON Serialization
- **BigDecimal**: Verify correct serialization (not scientific notation)
- **LocalDate**: Verify ISO 8601 format (YYYY-MM-DD)
- **Currency**: Verify string representation (e.g., "USD", "EUR")

### 5. Cache Behavior (Optional)
- **If Phase 6.1 completed**: Verify cache hits/misses
- **Test Strategy**: Query same params twice, verify second query faster
- **Out of Scope**: Not required for Step 4.3 baseline

## Validation Against Controller Code

### Controller Signature (ExchangeRateController.java:93-107)
```java
@GetMapping(path = "", produces = "application/json")
public List<ExchangeRateResponse> getExchangeRates(
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) Optional<LocalDate> startDate,
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) Optional<LocalDate> endDate,
    @NotNull @RequestParam Currency targetCurrency
)
```

### Parameter Validation
- **Required**: `targetCurrency` (@NotNull) - 400 if missing
- **Optional**: `startDate`, `endDate` (Optional<LocalDate>) - Service handles nulls
- **Format Validation**: `@DateTimeFormat` ensures ISO date format - 400 if invalid
- **Business Validation**: startDate <= endDate checked in controller (line 115) - 400 if violated

### Service Layer Validation
- **No Data Available**: Throws `BusinessException` → 422
- **Start Date Out of Range**: Throws `BusinessException` → 422
- **See**: ExchangeRateService implementation for exact error codes

## Expected Outcome

### Test Metrics
- **Total Tests**: 15-18 comprehensive test methods
- **Execution Time**: < 30 seconds (with TestContainers reuse)
- **Coverage**:
  - Controller HTTP layer: 100%
  - Request parameter binding: All scenarios
  - Response serialization: All fields

### Quality Standards
- **Pattern Consistency**: Matches CurrencySeriesControllerTest structure
- **Documentation**: JavaDoc on class and complex test methods
- **Readability**: Clear test names following "should" convention
- **Assertions**: Use AssertJ/Hamcrest for fluent assertions
- **Test Data**: Use TestConstants and test builders exclusively

### Integration Verification
- **Full Stack**: HTTP → Controller → Service → Repository → Database
- **Real Infrastructure**: PostgreSQL via TestContainers
- **No Mocks**: Real service layer and repository implementations
- **Transactional Isolation**: Each test starts with clean database

## Implementation Order

### Phase 1: Foundation (Tests 1-5)
- Implement happy path tests first
- Establish test data setup patterns
- Verify basic JSON response structure

### Phase 2: Validation (Tests 6-10)
- Add parameter validation tests
- Verify 400 error responses
- Test Spring's validation framework integration

### Phase 3: Business Logic (Tests 11-13)
- Add business error scenarios
- Verify 422 error responses with correct error codes
- Test service layer exception handling

### Phase 4: Edge Cases (Tests 14-19)
- Add response structure validation
- Test edge cases and boundary conditions
- Verify gap-filling logic

## Success Criteria

✅ All 15-18 tests pass consistently
✅ No flaky tests (deterministic behavior)
✅ Tests follow existing patterns (AbstractControllerTest, test builders)
✅ Full JavaDoc documentation on test class
✅ Code formatted with Spotless
✅ Checkstyle compliant
✅ Execution time < 30 seconds
✅ Integrates with existing test suite (`./gradlew test`)

## References

- **Integration Plan**: docs/plans/integration-testing-plan.md (Step 4.3, lines 88-95)
- **Controller**: src/main/java/org/budgetanalyzer/currency/api/ExchangeRateController.java
- **Response DTO**: src/main/java/org/budgetanalyzer/currency/api/response/ExchangeRateResponse.java
- **Example Test**: src/test/java/org/budgetanalyzer/currency/api/CurrencySeriesControllerTest.java
- **Test Fixtures**: src/test/java/org/budgetanalyzer/currency/fixture/
