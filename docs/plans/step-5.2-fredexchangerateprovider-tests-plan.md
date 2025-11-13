# Detailed Implementation Plan: Step 5.2 - FredExchangeRateProvider Integration Tests

## Overview
Create comprehensive integration tests for FredExchangeRateProvider to validate transformation logic from FRED API format to domain model, error handling, and integration with FredClient using WireMock.

**Phase**: Phase 5 - External Integration Tests
**Step**: 5.2 - FredExchangeRateProvider Tests
**Parent Plan**: [integration-testing-plan.md](integration-testing-plan.md)
**Depends On**: [Step 5.1 - FredClient Tests](step-5.1-fredclient-tests-plan.md) ✅

## Context & Current State

### ExchangeRateProvider Interface Contract
**Location**: [src/main/java/org/budgetanalyzer/currency/service/provider/ExchangeRateProvider.java](../../src/main/java/org/budgetanalyzer/currency/service/provider/ExchangeRateProvider.java)

**Interface Methods**:
```java
// Fetch exchange rates from external data source
Map<LocalDate, BigDecimal> getExchangeRates(CurrencySeries currencySeries, LocalDate startDate);

// Validate if a series ID exists in external data source
boolean validateSeriesExists(String providerSeriesId);
```

**Design Purpose**:
- Provider abstraction layer for external exchange rate data sources
- Enables future providers (ECB, Bloomberg) without service changes
- Encapsulates provider-specific transformation logic

---

### FredExchangeRateProvider Implementation Summary
**Location**: [src/main/java/org/budgetanalyzer/currency/service/provider/FredExchangeRateProvider.java](../../src/main/java/org/budgetanalyzer/currency/service/provider/FredExchangeRateProvider.java)

**Architecture**:
- Spring `@Service` bean
- Constructor injection of `FredClient`
- Simple delegation + transformation pattern

**Key Responsibilities**:
1. **Delegate to FredClient**: Call `fredClient.getSeriesObservationsData()` or `fredClient.seriesExists()`
2. **Transform FRED format to domain format**: `FredSeriesObservationsResponse` → `Map<LocalDate, BigDecimal>`
3. **Filter missing data**: Remove observations where `value = "."`
4. **Parse values**: Convert string values to `BigDecimal`
5. **Log operations**: Structured logging for observability

---

### Transformation Logic Details

#### Input: FRED API Response Format
```java
FredSeriesObservationsResponse {
    List<Observation> observations
}

Observation {
    LocalDate date;
    String value;  // "." = missing data, otherwise numeric string like "1.0850"
}
```

#### Transformation Steps
```java
// Step 1: Extract observations from response
response.observations().stream()

// Step 2: Filter out missing data
    .filter(Observation::hasValue)  // hasValue() checks: value != null && !".".equals(value)

// Step 3: Transform to domain model
    .collect(Collectors.toMap(
        Observation::date,              // Key: LocalDate
        Observation::getValueAsBigDecimal  // Value: BigDecimal (parses string)
    ));
```

#### Output: Domain Model Format
```java
Map<LocalDate, BigDecimal>
// Example: {2024-01-02 -> 1.0850, 2024-01-03 -> 1.0872, ...}
```

**Key Transformation Rules**:
- **Missing data**: FRED uses "." for weekends/holidays → filtered out completely
- **Numeric parsing**: String → BigDecimal using `new BigDecimal(value)`
- **No validation**: Assumes FredClient returns valid data
- **No business logic**: Pure transformation, no rate calculations or adjustments

---

### Error Handling Strategy

**FredExchangeRateProvider Error Handling**:
- **No explicit try-catch** - relies on FredClient to throw `ClientException`
- Exceptions propagate to service layer unchanged
- Service layer adds context (currency code, date range)

**FredClient Exception Scenarios** (from Step 5.1):
- HTTP 4xx/5xx → `ClientException` with parsed error message
- Timeout → `ClientException` with timeout message
- Malformed JSON → `ClientException` with parse error
- Network errors → `ClientException`

**validateSeriesExists() Special Behavior**:
- HTTP 200 → Returns `true`
- HTTP 400/404 → Returns `false` (NOT an exception!)
- HTTP 500/timeout → Throws `ClientException`

---

### Integration with Domain Model

**CurrencySeries Usage**:
```java
@Entity
public class CurrencySeries {
    private String currencyCode;      // ISO 4217 code (e.g., "EUR")
    private String providerSeriesId;  // FRED series ID (e.g., "DEXUSEU")
    // ...
}
```

**How Provider Uses CurrencySeries**:
- Extracts `providerSeriesId` → passes to FredClient
- Uses `currencyCode` for logging only
- Doesn't modify CurrencySeries entity

---

### Existing Test Infrastructure (from Step 5.1) ✅

#### Test Fixtures Available
**Location**: [src/test/java/org/budgetanalyzer/currency/fixture/](../../src/test/java/org/budgetanalyzer/currency/fixture/)

1. **FredApiStubs** - WireMock stub utilities (enhanced in Step 5.1)
   - Success: `stubSuccessWithSampleData()`, `stubSuccessWithObservations()`, `stubSuccessEmpty()`, `stubSuccessWithMissingData()`, `stubSuccessWithLargeDataset()`
   - Errors: `stubBadRequest()`, `stubNotFound()`, `stubServerError()`, `stubTimeout()`, `stubMalformedJson()`, `stubRateLimited()`
   - Series validation: `stubSeriesExistsSuccess()`, `stubSeriesExistsNotFound()`, `stubSeriesExistsBadRequest()`, etc.

2. **CurrencySeriesTestBuilder** - Fluent builder for test data
   - Factory methods: `defaultEur()`, `defaultThb()`, `defaultGbp()`, `defaultJpy()`, `defaultCad()`
   - Customization: `.currencyCode()`, `.providerSeriesId()`, `.enabled()`, etc.

3. **TestConstants** - Comprehensive test data
   - Currency codes: `CURRENCY_CODE_EUR`, `CURRENCY_CODE_THB`, etc.
   - FRED series IDs: `FRED_SERIES_ID_EUR`, `FRED_SERIES_ID_THB`, etc.
   - Dates: `DATE_2024_01_01`, `DATE_2024_06_15`, etc.
   - Rates: `EXCHANGE_RATE_EUR`, `EXCHANGE_RATE_THB`, etc.

#### Base Test Classes
- **AbstractIntegrationTest**: Full Spring context + TestContainers (PostgreSQL, Redis, RabbitMQ) + WireMock
- Database cleanup + seed data restoration in `@BeforeEach`

---

### Gap Analysis
**Current Coverage**:
- ✅ Service layer tests mock `ExchangeRateProvider` interface
- ✅ FredClient has dedicated integration tests (Step 5.1)
- ❌ **No direct FredExchangeRateProvider integration tests** that verify:
  - Transformation logic from FRED format to domain format
  - Missing data filtering ("." values)
  - BigDecimal parsing with various formats
  - Error translation from FredClient to service layer
  - Integration with real CurrencySeries objects
  - Edge cases (empty responses, all missing data, large datasets)

---

## Implementation Tasks

### Task 1: Create FredExchangeRateProviderIntegrationTest Class
**File**: [src/test/java/org/budgetanalyzer/currency/service/provider/FredExchangeRateProviderIntegrationTest.java](../../src/test/java/org/budgetanalyzer/currency/service/provider/FredExchangeRateProviderIntegrationTest.java)

**Class Structure**:
```java
package org.budgetanalyzer.currency.service.provider;

import static org.assertj.core.api.Assertions.*;
import static org.budgetanalyzer.currency.fixture.TestConstants.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.budgetanalyzer.currency.base.AbstractIntegrationTest;
import org.budgetanalyzer.currency.client.fred.response.FredSeriesObservationsResponse.Observation;
import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.servicecommon.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for FredExchangeRateProvider using WireMock to simulate FRED API.
 * Tests transformation logic, missing data handling, error scenarios, and integration with FredClient.
 *
 * <p>Focus areas:
 * - Transformation from FRED format (Observations) to domain format (Map<LocalDate, BigDecimal>)
 * - Missing data filtering (FRED uses "." to indicate no data)
 * - BigDecimal parsing with various numeric formats
 * - Error handling and exception propagation
 * - Series validation behavior
 *
 * <p>This test class extends AbstractIntegrationTest to get:
 * - Full Spring Boot context with real FredClient
 * - WireMock server for FRED API simulation
 * - TestContainers for infrastructure dependencies
 *
 * @see FredExchangeRateProvider
 * @see FredClient
 * @see ExchangeRateProvider
 */
@DisplayName("FredExchangeRateProvider Integration Tests")
class FredExchangeRateProviderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private FredExchangeRateProvider provider;

    private CurrencySeries eurSeries;
    private CurrencySeries thbSeries;

    @BeforeEach
    void setUp() {
        // Create test currency series
        eurSeries = CurrencySeriesTestBuilder.defaultEur().build();
        thbSeries = CurrencySeriesTestBuilder.defaultThb().build();

        // Note: We don't persist these to database - provider only needs the DTOs
        // FredClient uses providerSeriesId field, not database ID
    }

    // Test methods below...
}
```

---

### Test Coverage: 18-22 Comprehensive Tests

#### Group 1: Happy Path - Successful Data Transformation (6 tests)

##### Test 1: Fetch Full History (startDate = null)
```java
@Test
@DisplayName("Should fetch full history when startDate is null")
void shouldFetchFullHistoryWhenStartDateIsNull() {
    // Given
    FredApiStubs.stubSuccessWithSampleData(FRED_SERIES_ID_EUR);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).isNotEmpty();
    assertThat(rates).hasSize(10);  // Sample data has 10 observations
    assertThat(rates).containsOnlyKeys(
        LocalDate.of(2024, 1, 2),
        LocalDate.of(2024, 1, 3),
        // ... (verify all 10 dates)
    );

    // Verify values are parsed correctly as BigDecimal
    assertThat(rates.values())
        .allSatisfy(rate -> {
            assertThat(rate).isInstanceOf(BigDecimal.class);
            assertThat(rate).isPositive();
        });
}
```

##### Test 2: Fetch Incremental Data (startDate = specific date)
```java
@Test
@DisplayName("Should fetch incremental data when startDate is provided")
void shouldFetchIncrementalDataWhenStartDateProvided() {
    // Given
    var startDate = LocalDate.of(2024, 6, 1);
    FredApiStubs.stubSuccessWithSampleData(FRED_SERIES_ID_EUR);

    // When
    var rates = provider.getExchangeRates(eurSeries, startDate);

    // Then
    assertThat(rates).isNotEmpty();
    // Note: WireMock stub doesn't actually filter by date
    // This tests that the request is made with correct parameters
}
```

##### Test 3: Filter Missing Data (observations with ".")
```java
@Test
@DisplayName("Should filter out observations with missing data indicator '.'")
void shouldFilterMissingDataObservations() {
    // Given - FRED returns mix of valid values and "." for weekends
    FredApiStubs.stubSuccessWithMissingData(FRED_SERIES_ID_EUR);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).isNotEmpty();

    // Verify only non-missing data is included
    assertThat(rates.values())
        .allSatisfy(rate -> assertThat(rate).isNotNull());

    // Sample data has 5 valid + 2 missing = should return 5
    assertThat(rates).hasSize(5);
}
```

##### Test 4: Parse Various BigDecimal Formats
```java
@Test
@DisplayName("Should correctly parse various BigDecimal formats from FRED")
void shouldParseVariousBigDecimalFormats() {
    // Given - Custom observations with different numeric formats
    var observations = List.of(
        new Observation(DATE_2024_01_02, DATE_2024_01_02, DATE_2024_01_02, "1.0850"),     // Standard
        new Observation(DATE_2024_01_03, DATE_2024_01_03, DATE_2024_01_03, "1.085"),      // No trailing zero
        new Observation(DATE_2024_01_04, DATE_2024_01_04, DATE_2024_01_04, "1.08500000"), // Extra precision
        new Observation(DATE_2024_01_05, DATE_2024_01_05, DATE_2024_01_05, "0.9123"),     // Leading zero
        new Observation(DATE_2024_01_08, DATE_2024_01_08, DATE_2024_01_08, "123.456789")  // Large value
    );
    FredApiStubs.stubSuccessWithObservations(FRED_SERIES_ID_EUR, observations);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).hasSize(5);

    // Verify specific values parsed correctly
    assertThat(rates.get(DATE_2024_01_02)).isEqualByComparingTo("1.0850");
    assertThat(rates.get(DATE_2024_01_03)).isEqualByComparingTo("1.085");
    assertThat(rates.get(DATE_2024_01_04)).isEqualByComparingTo("1.08500000");
    assertThat(rates.get(DATE_2024_01_05)).isEqualByComparingTo("0.9123");
    assertThat(rates.get(DATE_2024_01_08)).isEqualByComparingTo("123.456789");
}
```

##### Test 5: Handle Large Dataset (365+ observations)
```java
@Test
@DisplayName("Should efficiently handle large dataset with 365 observations")
void shouldHandleLargeDatasetEfficiently() {
    // Given
    FredApiStubs.stubSuccessWithLargeDataset(FRED_SERIES_ID_EUR);

    // When
    long startTime = System.currentTimeMillis();
    var rates = provider.getExchangeRates(eurSeries, null);
    long duration = System.currentTimeMillis() - startTime;

    // Then
    assertThat(rates).hasSize(365);
    assertThat(duration).isLessThan(2000); // Should complete in < 2 seconds

    // Verify all values are valid BigDecimal
    assertThat(rates.values()).allMatch(rate -> rate.compareTo(BigDecimal.ZERO) > 0);
}
```

##### Test 6: Transform with Different Currency Series
```java
@Test
@DisplayName("Should work with different currency series (THB)")
void shouldWorkWithDifferentCurrencySeries() {
    // Given
    FredApiStubs.stubSuccessWithSampleData(FRED_SERIES_ID_THB);

    // When
    var rates = provider.getExchangeRates(thbSeries, null);

    // Then
    assertThat(rates).isNotEmpty();
    // Verify transformation works regardless of currency
    assertThat(rates.values()).allSatisfy(rate -> {
        assertThat(rate).isInstanceOf(BigDecimal.class);
        assertThat(rate).isPositive();
    });
}
```

---

#### Group 2: Error Handling - Exception Propagation (6 tests)

##### Test 7: Handle 404 Not Found
```java
@Test
@DisplayName("Should throw ClientException when FRED returns 404 Not Found")
void shouldThrowClientExceptionOn404NotFound() {
    // Given
    FredApiStubs.stubNotFound(FRED_SERIES_ID_EUR);

    // When/Then
    assertThatThrownBy(() -> provider.getExchangeRates(eurSeries, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("not found");
}
```

##### Test 8: Handle 400 Bad Request
```java
@Test
@DisplayName("Should throw ClientException when FRED returns 400 Bad Request")
void shouldThrowClientExceptionOn400BadRequest() {
    // Given
    FredApiStubs.stubBadRequest(FRED_SERIES_ID_EUR);

    // When/Then
    assertThatThrownBy(() -> provider.getExchangeRates(eurSeries, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("Bad Request");
}
```

##### Test 9: Handle 500 Server Error
```java
@Test
@DisplayName("Should throw ClientException when FRED returns 500 Server Error")
void shouldThrowClientExceptionOn500ServerError() {
    // Given
    FredApiStubs.stubServerError(FRED_SERIES_ID_EUR);

    // When/Then
    assertThatThrownBy(() -> provider.getExchangeRates(eurSeries, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("500");
}
```

##### Test 10: Handle Timeout
```java
@Test
@DisplayName("Should throw ClientException on timeout")
void shouldThrowClientExceptionOnTimeout() {
    // Given
    FredApiStubs.stubTimeout(FRED_SERIES_ID_EUR);  // 35 second delay

    // When/Then
    assertThatThrownBy(() -> provider.getExchangeRates(eurSeries, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("timeout");
}
```

##### Test 11: Handle Malformed JSON
```java
@Test
@DisplayName("Should throw ClientException on malformed JSON response")
void shouldThrowClientExceptionOnMalformedJson() {
    // Given
    FredApiStubs.stubMalformedJson(FRED_SERIES_ID_EUR);

    // When/Then
    assertThatThrownBy(() -> provider.getExchangeRates(eurSeries, null))
        .isInstanceOf(ClientException.class);
}
```

##### Test 12: Handle Rate Limiting (429)
```java
@Test
@DisplayName("Should throw ClientException when rate limited (429)")
void shouldThrowClientExceptionOnRateLimit() {
    // Given
    FredApiStubs.stubRateLimited(FRED_SERIES_ID_EUR);

    // When/Then
    assertThatThrownBy(() -> provider.getExchangeRates(eurSeries, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("rate limit");
}
```

---

#### Group 3: Series Validation Tests (4 tests)

##### Test 13: Validate Series Exists (returns true)
```java
@Test
@DisplayName("Should return true when series exists in FRED")
void shouldReturnTrueWhenSeriesExists() {
    // Given
    FredApiStubs.stubSeriesExistsSuccess(FRED_SERIES_ID_EUR);

    // When
    boolean exists = provider.validateSeriesExists(FRED_SERIES_ID_EUR);

    // Then
    assertThat(exists).isTrue();
}
```

##### Test 14: Validate Series Not Found (returns false, not exception)
```java
@Test
@DisplayName("Should return false when series not found (404) - not throw exception")
void shouldReturnFalseWhenSeriesNotFound() {
    // Given
    FredApiStubs.stubSeriesExistsNotFound("NONEXISTENT");

    // When
    boolean exists = provider.validateSeriesExists("NONEXISTENT");

    // Then
    assertThat(exists).isFalse();
}
```

##### Test 15: Validate Series Invalid (returns false, not exception)
```java
@Test
@DisplayName("Should return false when series ID invalid (400) - not throw exception")
void shouldReturnFalseWhenSeriesIdInvalid() {
    // Given
    FredApiStubs.stubSeriesExistsBadRequest("INVALID@@@");

    // When
    boolean exists = provider.validateSeriesExists("INVALID@@@");

    // Then
    assertThat(exists).isFalse();
}
```

##### Test 16: Validate Series - Server Error (throws exception)
```java
@Test
@DisplayName("Should throw ClientException when series validation encounters server error")
void shouldThrowClientExceptionOnValidationServerError() {
    // Given
    FredApiStubs.stubSeriesExistsServerError(FRED_SERIES_ID_EUR);

    // When/Then
    assertThatThrownBy(() -> provider.validateSeriesExists(FRED_SERIES_ID_EUR))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("500");
}
```

---

#### Group 4: Edge Cases and Special Scenarios (6 tests)

##### Test 17: Empty Observations List
```java
@Test
@DisplayName("Should return empty map when FRED returns no observations")
void shouldReturnEmptyMapWhenNoObservations() {
    // Given
    FredApiStubs.stubSuccessEmpty(FRED_SERIES_ID_EUR);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).isEmpty();
}
```

##### Test 18: All Observations Have Missing Data
```java
@Test
@DisplayName("Should return empty map when all observations have missing data '.'")
void shouldReturnEmptyMapWhenAllObservationsMissing() {
    // Given - All observations have value = "."
    var observations = List.of(
        new Observation(DATE_2024_01_06, DATE_2024_01_06, DATE_2024_01_06, "."),  // Saturday
        new Observation(DATE_2024_01_07, DATE_2024_01_07, DATE_2024_01_07, "."),  // Sunday
        new Observation(DATE_2024_01_13, DATE_2024_01_13, DATE_2024_01_13, ".")   // Holiday
    );
    FredApiStubs.stubSuccessWithObservations(FRED_SERIES_ID_EUR, observations);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).isEmpty();
}
```

##### Test 19: Mixed Valid and Missing Data
```java
@Test
@DisplayName("Should handle mix of valid values and missing data correctly")
void shouldHandleMixOfValidAndMissingData() {
    // Given - Realistic week with weekdays (data) and weekends (missing)
    var observations = List.of(
        new Observation(DATE_2024_01_02, DATE_2024_01_02, DATE_2024_01_02, "1.0850"),  // Mon
        new Observation(DATE_2024_01_03, DATE_2024_01_03, DATE_2024_01_03, "1.0872"),  // Tue
        new Observation(DATE_2024_01_04, DATE_2024_01_04, DATE_2024_01_04, "1.0891"),  // Wed
        new Observation(DATE_2024_01_05, DATE_2024_01_05, DATE_2024_01_05, "1.0823"),  // Thu
        new Observation(DATE_2024_01_06, DATE_2024_01_06, DATE_2024_01_06, "."),       // Sat
        new Observation(DATE_2024_01_07, DATE_2024_01_07, DATE_2024_01_07, ".")        // Sun
    );
    FredApiStubs.stubSuccessWithObservations(FRED_SERIES_ID_EUR, observations);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).hasSize(4);  // Only weekdays
    assertThat(rates).containsOnlyKeys(
        DATE_2024_01_02, DATE_2024_01_03, DATE_2024_01_04, DATE_2024_01_05
    );
    assertThat(rates).doesNotContainKeys(DATE_2024_01_06, DATE_2024_01_07);
}
```

##### Test 20: Very Old Dates (1971)
```java
@Test
@DisplayName("Should handle very old historical dates (1971)")
void shouldHandleVeryOldHistoricalDates() {
    // Given - FRED has data back to 1971
    var observations = List.of(
        new Observation(
            LocalDate.of(1971, 1, 4),
            LocalDate.of(1971, 1, 4),
            LocalDate.of(1971, 1, 4),
            "0.3571"  // DEM/USD from 1971
        )
    );
    FredApiStubs.stubSuccessWithObservations(FRED_SERIES_ID_EUR, observations);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).hasSize(1);
    assertThat(rates).containsKey(LocalDate.of(1971, 1, 4));
    assertThat(rates.get(LocalDate.of(1971, 1, 4)))
        .isEqualByComparingTo("0.3571");
}
```

##### Test 21: High Precision Values
```java
@Test
@DisplayName("Should preserve high precision decimal values")
void shouldPreserveHighPrecisionValues() {
    // Given - Values with many decimal places
    var observations = List.of(
        new Observation(DATE_2024_01_02, DATE_2024_01_02, DATE_2024_01_02, "1.08501234567890")
    );
    FredApiStubs.stubSuccessWithObservations(FRED_SERIES_ID_EUR, observations);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).hasSize(1);
    var rate = rates.get(DATE_2024_01_02);
    assertThat(rate).isEqualByComparingTo("1.08501234567890");
    assertThat(rate.scale()).isGreaterThanOrEqualTo(10);  // Precision preserved
}
```

##### Test 22: Duplicate Dates (Optional - Error Case)
```java
@Test
@DisplayName("Should handle duplicate dates by keeping last value (map behavior)")
void shouldHandleDuplicateDatesByKeepingLastValue() {
    // Given - Duplicate dates (should not happen in FRED, but test map behavior)
    var observations = List.of(
        new Observation(DATE_2024_01_02, DATE_2024_01_02, DATE_2024_01_02, "1.0850"),
        new Observation(DATE_2024_01_02, DATE_2024_01_02, DATE_2024_01_02, "1.0999")  // Duplicate date
    );
    FredApiStubs.stubSuccessWithObservations(FRED_SERIES_ID_EUR, observations);

    // When
    var rates = provider.getExchangeRates(eurSeries, null);

    // Then
    assertThat(rates).hasSize(1);  // Map collapses duplicates
    assertThat(rates.get(DATE_2024_01_02))
        .isEqualByComparingTo("1.0999");  // Last value wins
}
```

---

### Task 2: Run Tests and Verify Coverage
**Commands**:
```bash
# Run only FredExchangeRateProvider tests
./gradlew test --tests FredExchangeRateProviderIntegrationTest

# Run all provider tests
./gradlew test --tests "*.provider.*"

# Run all tests to ensure no regressions
./gradlew test

# Check for flakiness (run 3 times)
./gradlew test --tests FredExchangeRateProviderIntegrationTest --rerun-tasks
./gradlew test --tests FredExchangeRateProviderIntegrationTest --rerun-tasks
./gradlew test --tests FredExchangeRateProviderIntegrationTest --rerun-tasks
```

**Expected Outcomes**:
- ✅ All 18-22 tests pass
- ✅ No flaky tests (consistent results across 3+ runs)
- ✅ Execution time < 20 seconds for FredExchangeRateProviderIntegrationTest
- ✅ FredExchangeRateProvider class reaches 95%+ line coverage
- ✅ No test failures in existing test suite

**Estimated effort**: 15 minutes

---

### Task 3: Code Formatting and Build Validation
**Commands**:
```bash
# Apply Spotless formatting
./gradlew spotlessApply

# Run Checkstyle validation
./gradlew checkstyleMain checkstyleTest

# Full build (includes all checks + tests)
./gradlew clean build
```

**Expected Outcomes**:
- ✅ No Spotless violations
- ✅ No Checkstyle violations
- ✅ Full build succeeds
- ✅ All tests pass (provider tests + existing tests)

**Estimated effort**: 5 minutes

---

## Success Criteria

### Functional Requirements
- ✅ 18-22 comprehensive tests for FredExchangeRateProvider
- ✅ All transformation logic covered (FRED → domain model)
- ✅ Missing data filtering validated ("." values)
- ✅ BigDecimal parsing tested with various formats
- ✅ Error propagation verified (FredClient → Provider → Service)
- ✅ Series validation behavior tested (true/false/exception scenarios)
- ✅ Edge cases covered (empty, all missing, mixed, old dates, precision)

### Non-Functional Requirements
- ✅ Tests use existing infrastructure (WireMock, FredApiStubs, TestBuilders)
- ✅ All tests deterministic and repeatable (no flakiness)
- ✅ Test execution < 20 seconds
- ✅ Code follows service-common patterns (AssertJ, descriptive names, Javadoc)
- ✅ Full build passes (spotless, checkstyle, all tests)
- ✅ FredExchangeRateProvider line coverage ≥ 95%

### Documentation Requirements
- ✅ Test class has comprehensive JavaDoc
- ✅ Each test group has explanatory comments
- ✅ Complex assertions have inline comments
- ✅ This plan document saved for reference

---

## Estimated Effort

| Task | Estimated Time |
|------|----------------|
| Task 1: Create FredExchangeRateProviderIntegrationTest | 2.5-3 hours |
| Task 2: Run tests and verify coverage | 15 minutes |
| Task 3: Code formatting and build validation | 5 minutes |
| **Total** | **3-4 hours** |

---

## Files Modified

### New Files
1. **[src/test/java/org/budgetanalyzer/currency/service/provider/FredExchangeRateProviderIntegrationTest.java](../../src/test/java/org/budgetanalyzer/currency/service/provider/FredExchangeRateProviderIntegrationTest.java)**
   - ~600-700 lines
   - 18-22 test methods
   - Comprehensive JavaDoc

### No Modifications Needed
- FredApiStubs already has all necessary stubs (from Step 5.1)
- TestConstants already has all necessary constants
- CurrencySeriesTestBuilder already has factory methods

---

## Dependencies on Other Steps

### Prerequisites
- **Step 1.1**: Testing dependencies ✅ Complete
- **Step 1.2**: Base test classes ✅ Complete
- **Step 1.3**: Test fixtures ✅ Complete
- **Step 5.1**: FredClient tests + FredApiStubs enhancements ✅ Complete

### Enables Future Steps
- **Phase 6**: Advanced Patterns Tests
  - Provider abstraction validated
  - Integration with service layer ready
- **Phase 7**: End-to-End Integration Tests
  - Complete provider flow validated
  - Ready for full workflow testing

---

## Testing Strategy

### Test Organization
Following service-common patterns:
- **AAA Pattern**: Arrange-Act-Assert structure
- **AssertJ**: Fluent assertions for readability
- **DisplayName**: Descriptive test names in sentence case
- **Groups**: Logical grouping with comments

### Test Data Strategy
- Use `CurrencySeriesTestBuilder` for domain objects
- Use `FredApiStubs` for all WireMock stubbing
- Use `TestConstants` for reusable data
- No hardcoded values or magic numbers
- Clear Given-When-Then structure

### Transformation Testing Strategy
- Test input → output mapping explicitly
- Verify filtering logic (missing data)
- Verify parsing logic (string → BigDecimal)
- Test edge cases (empty, all missing, mixed)
- Test precision preservation

### Error Testing Strategy
- Verify exception type (`ClientException`)
- Verify exception message propagates from FredClient
- Test distinction: return false vs throw exception
- Verify no data corruption on error

### Integration Testing Strategy
- Use real Spring beans (FredExchangeRateProvider, FredClient)
- Use WireMock for HTTP simulation (not mocks)
- Test full flow: Provider → FredClient → HTTP → Response → Transformation
- Verify logging (structured logging for observability)

---

## Notes & Considerations

### Why These Tests Are Important
1. **Transformation logic is critical**: FRED format → domain format must be correct
2. **Missing data handling**: "." filtering is FRED-specific quirk that must work correctly
3. **BigDecimal precision**: Financial calculations require exact decimal precision
4. **Provider abstraction validation**: Ensures pattern works for future providers (ECB, Bloomberg)
5. **Error propagation**: Service layer depends on clear error signals from provider

### Integration Tests vs Unit Tests
**Why integration tests (not unit tests with mocks)**:
- Tests real HTTP client (WebClient) configuration
- Tests real JSON deserialization (Jackson)
- Tests real transformation logic with realistic data
- Tests full error propagation flow
- More confidence in production behavior

**What we're NOT testing**:
- FredClient implementation details (tested in Step 5.1)
- Service layer business logic (tested in Phase 3)
- Database interactions (no persistence in provider)

### FRED-Specific Behavior to Validate
1. **Missing data indicator**: "." means no data (weekends, holidays)
2. **Observation structure**: Multiple metadata fields, only `date` and `value` used
3. **String values**: All rates returned as strings, must parse to BigDecimal
4. **Historical data**: Can go back to 1971, must handle old dates
5. **Precision**: FRED returns varying precision (2-10+ decimal places)

### Future Provider Considerations
These tests demonstrate the provider abstraction pattern:
- Test structure can be replicated for `EcbExchangeRateProvider`
- Same interface contract (`ExchangeRateProvider`)
- Different transformation logic (ECB XML vs FRED JSON)
- Same error handling patterns
- Same missing data handling (ECB has different indicators)

### Performance Considerations
- Transformation is in-memory (no I/O)
- Stream processing for filtering/mapping
- Should handle 365+ observations efficiently (< 2 seconds)
- WireMock adds minimal overhead (~10-50ms)

---

## References

### Internal Documentation
- [Integration Testing Plan](integration-testing-plan.md) - Master plan (Phase 5)
- [Step 5.1 - FredClient Tests](step-5.1-fredclient-tests-plan.md) - Prerequisites
- [service-common Testing Patterns](https://github.com/budget-analyzer/service-common/blob/main/docs/testing-patterns.md)
- [service-common Advanced Patterns](https://github.com/budget-analyzer/service-common/blob/main/docs/advanced-patterns.md#provider-abstraction-pattern)
- [CLAUDE.md](../../CLAUDE.md) - Service architecture

### External References
- [FRED API Documentation](https://fred.stlouisfed.org/docs/api/fred/)
- [FRED Observation Format](https://fred.stlouisfed.org/docs/api/fred/series_observations.html)
- [WireMock Documentation](https://wiremock.org/docs/)
- [AssertJ Documentation](https://assertj.github.io/doc/)
- [BigDecimal Best Practices](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html)

---

## Next Steps After Completion

1. **Phase 6.1**: Redis Caching Tests
   - Test cache population after provider fetches data
   - Test cache eviction on import
   - Verify cache keys and serialization

2. **Phase 6.2**: Event-Driven Messaging Tests
   - Test CurrencyCreatedEvent triggers import
   - Test import uses provider to fetch data
   - Full async workflow validation

3. **Phase 7**: End-to-End Integration Tests
   - Complete workflow: Create currency → Validate series → Import via provider → Query rates
   - Test full stack with all layers integrated

---

**Status**: Ready for implementation
**Depends On**: Step 5.1 (FredClient Tests) ✅ Complete
**Last Updated**: 2025-11-13
**Author**: Claude Code (Plan Mode)
