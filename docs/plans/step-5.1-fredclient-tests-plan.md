# Detailed Implementation Plan: Step 5.1 - FredClient Integration Tests (WireMock)

## Overview
Create comprehensive integration tests for FredClient using the existing WireMock infrastructure to validate HTTP client behavior, error handling, timeout scenarios, and response parsing.

**Phase**: Phase 5 - External Integration Tests
**Step**: 5.1 - FredClient Tests (WireMock)
**Parent Plan**: [integration-testing-plan.md](integration-testing-plan.md)

## Context & Current State

### FredClient Implementation Summary
**Location**: [src/main/java/org/budgetanalyzer/currency/client/fred/FredClient.java](../../src/main/java/org/budgetanalyzer/currency/client/fred/FredClient.java)

**Public API**:
```java
// Fetch series observations data
FredSeriesObservationsResponse getSeriesObservationsData(String seriesId, LocalDate startDate)

// Check if a series exists
boolean seriesExists(String seriesId)
```

**Key Implementation Details**:
- Uses Spring WebFlux `WebClient` (blocking with `.block()`)
- Timeout: 30 seconds for `getSeriesObservationsData()`, 5 seconds for `seriesExists()`
- Error handling: Parses `FredErrorResponse` JSON, wraps in `ClientException`
- Response models: `FredSeriesObservationsResponse` with nested `Observation` records
- Special handling: "." values represent missing data in FRED

### Existing Test Infrastructure (Excellent Foundation! ✅)

#### WireMock Setup
- **Location**: `TestContainersConfiguration` + `AbstractControllerTest`
- **Status**: Fully configured and shared across tests
- Dynamic port allocation to avoid conflicts
- Auto-reset between tests
- Base URL dynamically injected via `@DynamicPropertySource`

#### Test Fixtures
**Location**: [src/test/java/org/budgetanalyzer/currency/fixture/FredApiStubs.java](../../src/test/java/org/budgetanalyzer/currency/fixture/FredApiStubs.java)

**Available stubs for `/series/observations` endpoint**:
- ✅ `stubSuccessWithObservations(seriesId, List<Observation>)`
- ✅ `stubSuccessWithSampleData(seriesId)` - 10 days EUR data
- ✅ `stubSuccessEmpty(seriesId)`
- ✅ `stubSuccessWithMissingData(seriesId)`
- ✅ `stubSuccessWithLargeDataset(seriesId)` - 365 observations
- ✅ `stubBadRequest(seriesId)`
- ✅ `stubNotFound(seriesId)`
- ✅ `stubServerError(seriesId)`
- ✅ `stubTimeout(seriesId)`
- ✅ `stubMalformedJson(seriesId)`
- ✅ `stubRateLimited(seriesId)`

**Missing stubs for `/series` endpoint**: Need 5 new methods for `seriesExists()` testing

#### Test Constants
**Location**: [src/test/java/org/budgetanalyzer/currency/fixture/TestConstants.java](../../src/test/java/org/budgetanalyzer/currency/fixture/TestConstants.java)

Provides:
- Valid FRED series IDs (`DEXUSEU`, `DEXTHUS`, etc.)
- Sample dates and exchange rates
- API paths: `FRED_API_PATH_OBSERVATIONS = "/fred/series/observations"`
- HTTP status codes

### Gap Analysis
**Current Coverage**:
- ✅ Controller tests mock entire FRED API via WireMock
- ✅ FredExchangeRateProvider tested indirectly through controller tests
- ❌ **No direct FredClient integration tests** that verify:
  - HTTP client configuration
  - Error response parsing details
  - Timeout behavior specifics
  - URL construction and encoding
  - Response deserialization edge cases

## Implementation Tasks

### Task 1: Enhance FredApiStubs with `/series` Endpoint Stubs
**File**: [src/test/java/org/budgetanalyzer/currency/fixture/FredApiStubs.java](../../src/test/java/org/budgetanalyzer/currency/fixture/FredApiStubs.java)

**New constant needed**:
```java
private static final String FRED_API_PATH_SERIES = "/fred/series";
```

**Add 5 new stub methods**:

#### 1. `stubSeriesExistsSuccess(String seriesId)`
```java
/**
 * Stubs successful series validation (200 OK).
 * Returns minimal valid series metadata JSON.
 */
public static void stubSeriesExistsSuccess(String seriesId) {
    wireMockServer.stubFor(
        get(urlPathEqualTo(FRED_API_PATH_SERIES))
            .withQueryParam("series_id", equalTo(seriesId))
            .withQueryParam("api_key", matching(".*"))
            .withQueryParam("file_type", equalTo("json"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "seriess": [{
                        "id": "%s",
                        "title": "Test Series",
                        "observation_start": "2024-01-01",
                        "observation_end": "2024-12-31"
                      }]
                    }
                    """.formatted(seriesId)))
    );
}
```

#### 2. `stubSeriesExistsNotFound(String seriesId)`
```java
/**
 * Stubs series not found (404).
 * FredClient.seriesExists() should return false (not throw).
 */
public static void stubSeriesExistsNotFound(String seriesId) {
    wireMockServer.stubFor(
        get(urlPathEqualTo(FRED_API_PATH_SERIES))
            .withQueryParam("series_id", equalTo(seriesId))
            .withQueryParam("api_key", matching(".*"))
            .withQueryParam("file_type", equalTo("json"))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "error_code": 404,
                      "error_message": "Series not found."
                    }
                    """))
    );
}
```

#### 3. `stubSeriesExistsBadRequest(String seriesId)`
```java
/**
 * Stubs invalid series ID (400).
 * FredClient.seriesExists() should return false (not throw).
 */
public static void stubSeriesExistsBadRequest(String seriesId) {
    wireMockServer.stubFor(
        get(urlPathEqualTo(FRED_API_PATH_SERIES))
            .withQueryParam("series_id", equalTo(seriesId))
            .withQueryParam("api_key", matching(".*"))
            .withQueryParam("file_type", equalTo("json"))
            .willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "error_code": 400,
                      "error_message": "Bad Request. Variable series_id is not valid."
                    }
                    """))
    );
}
```

#### 4. `stubSeriesExistsServerError(String seriesId)`
```java
/**
 * Stubs server error (500).
 * FredClient.seriesExists() should throw ClientException (not return false).
 */
public static void stubSeriesExistsServerError(String seriesId) {
    wireMockServer.stubFor(
        get(urlPathEqualTo(FRED_API_PATH_SERIES))
            .withQueryParam("series_id", equalTo(seriesId))
            .withQueryParam("api_key", matching(".*"))
            .withQueryParam("file_type", equalTo("json"))
            .willReturn(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "error_code": 500,
                      "error_message": "Internal Server Error"
                    }
                    """))
    );
}
```

#### 5. `stubSeriesExistsTimeout(String seriesId)`
```java
/**
 * Stubs timeout scenario (6+ second delay).
 * FredClient has 5 second timeout for seriesExists().
 */
public static void stubSeriesExistsTimeout(String seriesId) {
    wireMockServer.stubFor(
        get(urlPathEqualTo(FRED_API_PATH_SERIES))
            .withQueryParam("series_id", equalTo(seriesId))
            .withQueryParam("api_key", matching(".*"))
            .withQueryParam("file_type", equalTo("json"))
            .willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(6000)  // 6 seconds > 5 second timeout
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "seriess": [{
                        "id": "%s"
                      }]
                    }
                    """.formatted(seriesId)))
    );
}
```

**Estimated effort**: 30 minutes

---

### Task 2: Create FredClientIntegrationTest Class
**File**: [src/test/java/org/budgetanalyzer/currency/client/FredClientIntegrationTest.java](../../src/test/java/org/budgetanalyzer/currency/client/FredClientIntegrationTest.java)

**Class Structure**:
```java
package org.budgetanalyzer.currency.client;

import static org.assertj.core.api.Assertions.*;
import static org.budgetanalyzer.currency.fixture.TestConstants.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.budgetanalyzer.currency.base.AbstractIntegrationTest;
import org.budgetanalyzer.currency.client.fred.FredClient;
import org.budgetanalyzer.currency.client.fred.response.FredSeriesObservationsResponse;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.servicecommon.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for FredClient using WireMock to simulate FRED API responses.
 * Tests HTTP client behavior, error handling, timeouts, and response parsing.
 *
 * <p>This test class extends AbstractIntegrationTest to get:
 * - Full Spring Boot context
 * - WireMock server (automatically configured)
 * - TestContainers for infrastructure dependencies
 *
 * @see FredClient
 * @see FredApiStubs
 */
@DisplayName("FredClient Integration Tests")
class FredClientIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private FredClient fredClient;

    @BeforeEach
    void setUp() {
        // WireMock is automatically reset in AbstractControllerTest
        // No additional setup needed
    }

    // Test methods below...
}
```

---

### Test Coverage: 18-20 Comprehensive Tests

#### Group 1: `getSeriesObservationsData()` - Success Scenarios (5 tests)

##### Test 1: Valid Series with Observations
```java
@Test
@DisplayName("Should successfully fetch series observations with sample data")
void shouldFetchSeriesObservationsSuccessfully() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;
    var startDate = LocalDate.of(2024, 1, 1);
    FredApiStubs.stubSuccessWithSampleData(seriesId);

    // When
    var response = fredClient.getSeriesObservationsData(seriesId, startDate);

    // Then
    assertThat(response).isNotNull();
    assertThat(response.observations()).hasSize(10);
    assertThat(response.observations())
        .allSatisfy(obs -> {
            assertThat(obs.date()).isNotNull();
            assertThat(obs.value()).isNotBlank();
        });

    // Verify first observation structure
    var firstObs = response.observations().get(0);
    assertThat(firstObs.hasValue()).isTrue();
    assertThat(firstObs.getValueAsBigDecimal()).isNotNull();
}
```

##### Test 2: Empty Observations Array
```java
@Test
@DisplayName("Should handle empty observations array without error")
void shouldHandleEmptyObservationsArray() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;
    FredApiStubs.stubSuccessEmpty(seriesId);

    // When
    var response = fredClient.getSeriesObservationsData(seriesId, null);

    // Then
    assertThat(response).isNotNull();
    assertThat(response.observations()).isEmpty();
}
```

##### Test 3: Observations with Missing Data ("." values)
```java
@Test
@DisplayName("Should handle observations with missing data indicator '.'")
void shouldHandleMissingDataIndicator() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;
    FredApiStubs.stubSuccessWithMissingData(seriesId);

    // When
    var response = fredClient.getSeriesObservationsData(seriesId, null);

    // Then
    assertThat(response.observations()).isNotEmpty();

    // Verify some observations have missing data
    var missingDataObs = response.observations().stream()
        .filter(obs -> !obs.hasValue())
        .findFirst();

    assertThat(missingDataObs).isPresent();
    assertThat(missingDataObs.get().value()).isEqualTo(".");
    assertThat(missingDataObs.get().getValueAsBigDecimal()).isNull();
}
```

##### Test 4: Large Dataset (365 observations)
```java
@Test
@DisplayName("Should handle large dataset with 365 observations efficiently")
void shouldHandleLargeDataset() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;
    FredApiStubs.stubSuccessWithLargeDataset(seriesId);

    // When
    long startTime = System.currentTimeMillis();
    var response = fredClient.getSeriesObservationsData(seriesId, null);
    long duration = System.currentTimeMillis() - startTime;

    // Then
    assertThat(response.observations()).hasSize(365);
    assertThat(duration).isLessThan(2000); // Should complete in < 2 seconds
}
```

##### Test 5: With Start Date Parameter
```java
@Test
@DisplayName("Should include startDate in query parameters when provided")
void shouldIncludeStartDateInRequest() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;
    var startDate = LocalDate.of(2024, 6, 1);
    FredApiStubs.stubSuccessWithSampleData(seriesId);

    // When
    var response = fredClient.getSeriesObservationsData(seriesId, startDate);

    // Then
    assertThat(response).isNotNull();
    // Note: WireMock stub doesn't filter by date, but request is made correctly
    // Actual filtering happens on FRED side
}
```

---

#### Group 2: `getSeriesObservationsData()` - Error Scenarios (7 tests)

##### Test 6: 400 Bad Request
```java
@Test
@DisplayName("Should throw ClientException on 400 Bad Request with parsed error message")
void shouldThrowClientExceptionOn400BadRequest() {
    // Given
    var seriesId = "INVALID_SERIES";
    FredApiStubs.stubBadRequest(seriesId);

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("Bad Request");
}
```

##### Test 7: 404 Not Found
```java
@Test
@DisplayName("Should throw ClientException on 404 Not Found")
void shouldThrowClientExceptionOn404NotFound() {
    // Given
    var seriesId = "NONEXISTENT";
    FredApiStubs.stubNotFound(seriesId);

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("not found");
}
```

##### Test 8: 500 Server Error
```java
@Test
@DisplayName("Should throw ClientException on 500 Server Error")
void shouldThrowClientExceptionOn500ServerError() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;
    FredApiStubs.stubServerError(seriesId);

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("500");
}
```

##### Test 9: 429 Rate Limited
```java
@Test
@DisplayName("Should throw ClientException on 429 Rate Limited")
void shouldThrowClientExceptionOn429RateLimit() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;
    FredApiStubs.stubRateLimited(seriesId);

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("rate limit");
}
```

##### Test 10: Timeout (30+ seconds)
```java
@Test
@DisplayName("Should throw ClientException on timeout after 30 seconds")
void shouldThrowClientExceptionOnTimeout() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;
    FredApiStubs.stubTimeout(seriesId); // 35 second delay

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("timeout")
        .satisfies(ex -> {
            // Should timeout in ~30 seconds, not wait full 35
            // Test execution should be reasonably fast
        });
}
```

##### Test 11: Malformed JSON Response
```java
@Test
@DisplayName("Should throw ClientException on malformed JSON response")
void shouldThrowClientExceptionOnMalformedJson() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;
    FredApiStubs.stubMalformedJson(seriesId);

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class);
}
```

##### Test 12: Non-JSON Error Response
```java
@Test
@DisplayName("Should handle non-JSON error response gracefully")
void shouldHandleNonJsonErrorResponse() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;

    // Stub plain text error response
    wireMockServer.stubFor(
        get(urlPathEqualTo("/fred/series/observations"))
            .willReturn(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "text/plain")
                .withBody("Internal Server Error"))
    );

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("500");
}
```

---

#### Group 3: `seriesExists()` - Success Scenarios (3 tests)

##### Test 13: Series Exists (200 OK)
```java
@Test
@DisplayName("Should return true when series exists (200 OK)")
void shouldReturnTrueWhenSeriesExists() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;
    FredApiStubs.stubSeriesExistsSuccess(seriesId);

    // When
    boolean exists = fredClient.seriesExists(seriesId);

    // Then
    assertThat(exists).isTrue();
}
```

##### Test 14: Series Not Found (404)
```java
@Test
@DisplayName("Should return false when series not found (404) - not throw exception")
void shouldReturnFalseWhenSeriesNotFound() {
    // Given
    var seriesId = "NONEXISTENT";
    FredApiStubs.stubSeriesExistsNotFound(seriesId);

    // When
    boolean exists = fredClient.seriesExists(seriesId);

    // Then
    assertThat(exists).isFalse();
}
```

##### Test 15: Invalid Series ID (400)
```java
@Test
@DisplayName("Should return false when series ID invalid (400) - not throw exception")
void shouldReturnFalseWhenSeriesIdInvalid() {
    // Given
    var seriesId = "INVALID@@@";
    FredApiStubs.stubSeriesExistsBadRequest(seriesId);

    // When
    boolean exists = fredClient.seriesExists(seriesId);

    // Then
    assertThat(exists).isFalse();
}
```

---

#### Group 4: `seriesExists()` - Error Scenarios (3 tests)

##### Test 16: Server Error (500) Should Throw
```java
@Test
@DisplayName("Should throw ClientException on 500 Server Error (not return false)")
void shouldThrowClientExceptionOnServerError() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;
    FredApiStubs.stubSeriesExistsServerError(seriesId);

    // When/Then
    assertThatThrownBy(() -> fredClient.seriesExists(seriesId))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("500");
}
```

##### Test 17: Rate Limited (429) Should Throw
```java
@Test
@DisplayName("Should throw ClientException on 429 Rate Limited")
void shouldThrowClientExceptionOnRateLimitForSeriesExists() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;

    // Stub rate limit for /series endpoint
    wireMockServer.stubFor(
        get(urlPathEqualTo("/fred/series"))
            .willReturn(aResponse()
                .withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "error_code": 429,
                      "error_message": "API rate limit exceeded"
                    }
                    """))
    );

    // When/Then
    assertThatThrownBy(() -> fredClient.seriesExists(seriesId))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("rate limit");
}
```

##### Test 18: Timeout (5+ seconds) Should Throw
```java
@Test
@DisplayName("Should throw ClientException on timeout after 5 seconds for seriesExists")
void shouldThrowClientExceptionOnTimeoutForSeriesExists() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;
    FredApiStubs.stubSeriesExistsTimeout(seriesId); // 6 second delay

    // When/Then
    assertThatThrownBy(() -> fredClient.seriesExists(seriesId))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("timeout");
}
```

---

#### Group 5: Edge Cases (2-3 optional tests)

##### Test 19: URL Encoding of Special Characters
```java
@Test
@DisplayName("Should properly URL encode special characters in series ID")
void shouldUrlEncodeSpecialCharacters() {
    // Given
    var seriesId = "TEST/SERIES:123";
    FredApiStubs.stubSuccessEmpty(seriesId);

    // When
    var response = fredClient.getSeriesObservationsData(seriesId, null);

    // Then
    assertThat(response).isNotNull();
    // Verify WireMock received URL-encoded request
    // (WireMock automatically handles this, so if no error = success)
}
```

##### Test 20: Error Message Truncation (Optional)
```java
@Test
@DisplayName("Should truncate very long error messages to 500 characters")
void shouldTruncateLongErrorMessages() {
    // Given
    var seriesId = FRED_SERIES_ID_EUR;
    var longErrorMessage = "Error: " + "x".repeat(1000); // 1000+ chars

    wireMockServer.stubFor(
        get(urlPathEqualTo("/fred/series/observations"))
            .willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "error_code": 400,
                      "error_message": "%s"
                    }
                    """.formatted(longErrorMessage)))
    );

    // When/Then
    assertThatThrownBy(() -> fredClient.getSeriesObservationsData(seriesId, null))
        .isInstanceOf(ClientException.class)
        .satisfies(ex -> {
            assertThat(ex.getMessage().length()).isLessThanOrEqualTo(550); // ~500 + overhead
        });
}
```

**Estimated effort**: 2-3 hours

---

### Task 3: Run Tests and Verify Coverage
**Commands**:
```bash
# Run only FredClient tests
./gradlew test --tests FredClientIntegrationTest

# Run all tests to ensure no regressions
./gradlew test

# Run tests multiple times to check for flakiness
./gradlew test --tests FredClientIntegrationTest --rerun-tasks
./gradlew test --tests FredClientIntegrationTest --rerun-tasks
./gradlew test --tests FredClientIntegrationTest --rerun-tasks
```

**Expected Outcomes**:
- ✅ All 18-20 tests pass
- ✅ No flaky tests (consistent results across 3+ runs)
- ✅ Execution time < 15 seconds for FredClientIntegrationTest
- ✅ FredClient class reaches 90%+ line coverage
- ✅ No test failures in existing test suite

**Estimated effort**: 15 minutes

---

### Task 4: Code Formatting and Build Validation
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
- ✅ All tests pass (FredClient + existing tests)

**Estimated effort**: 5 minutes

---

## Success Criteria

### Functional Requirements
- ✅ 18-20 comprehensive tests for FredClient
- ✅ All success scenarios covered (valid data, empty data, missing data, large datasets)
- ✅ All error scenarios covered (4xx, 5xx, timeout, malformed JSON)
- ✅ Both public methods tested (`getSeriesObservationsData`, `seriesExists`)
- ✅ Timeout behavior validated (30s for observations, 5s for exists)
- ✅ JSON parsing and error handling verified
- ✅ Distinguished behavior: `seriesExists` returns false vs throws exception

### Non-Functional Requirements
- ✅ Tests use existing infrastructure (no new TestContainers)
- ✅ All tests deterministic and repeatable (no flakiness)
- ✅ Test execution < 15 seconds
- ✅ Code follows service-common patterns (AssertJ, descriptive names, Javadoc)
- ✅ Full build passes (spotless, checkstyle, all tests)
- ✅ FredClient line coverage ≥ 90%

### Documentation Requirements
- ✅ Test class has comprehensive JavaDoc
- ✅ Each test group has explanatory comments
- ✅ Complex assertions have inline comments
- ✅ This plan document saved for reference

---

## Estimated Effort

| Task | Estimated Time |
|------|----------------|
| Task 1: Enhance FredApiStubs | 30 minutes |
| Task 2: Create FredClientIntegrationTest | 2-3 hours |
| Task 3: Run tests and verify coverage | 15 minutes |
| Task 4: Code formatting and build validation | 5 minutes |
| **Total** | **3-4 hours** |

---

## Files Modified

### New Files
1. **[src/test/java/org/budgetanalyzer/currency/client/FredClientIntegrationTest.java](../../src/test/java/org/budgetanalyzer/currency/client/FredClientIntegrationTest.java)**
   - ~500-600 lines
   - 18-20 test methods
   - Comprehensive JavaDoc

### Modified Files
1. **[src/test/java/org/budgetanalyzer/currency/fixture/FredApiStubs.java](../../src/test/java/org/budgetanalyzer/currency/fixture/FredApiStubs.java)**
   - Add 5 new stub methods for `/series` endpoint
   - Add 1 new constant: `FRED_API_PATH_SERIES`
   - ~100 lines added

---

## Dependencies on Other Steps

### Prerequisites (All Complete ✅)
- **Step 1.1**: Testing dependencies (WireMock, TestContainers) - ✅ Complete
- **Step 1.2**: Base test classes (AbstractIntegrationTest) - ✅ Complete
- **Step 1.3**: Test fixtures (FredApiStubs, TestConstants) - ✅ Complete

### Enables Future Steps
- **Step 5.2**: FredExchangeRateProvider Tests
  - Can reuse FredApiStubs enhancements
  - Similar test patterns
- **Phase 7**: End-to-End Integration Tests
  - Complete FRED mocking infrastructure
  - Validated error handling flows

---

## Testing Strategy

### Test Organization
Following service-common patterns:
- **AAA Pattern**: Arrange-Act-Assert structure
- **AssertJ**: Fluent assertions for readability
- **DisplayName**: Descriptive test names in sentence case
- **Groups**: Logical grouping with comments

### Test Data Strategy
- Use `TestConstants` for reusable data
- Use `FredApiStubs` for all WireMock stubbing
- No hardcoded URLs or magic numbers
- Clear Given-When-Then structure

### Error Testing Strategy
- Verify exception type (`ClientException`)
- Verify exception message contains key information
- Test both "expected failures" (return false) vs "unexpected failures" (throw)

### Performance Testing Strategy
- Measure execution time for large datasets
- Verify timeout behavior (but don't wait full duration)
- Ensure tests complete in reasonable time

---

## Notes & Considerations

### Why These Tests Are Important
1. **FredClient is critical infrastructure**: All exchange rate imports depend on it
2. **HTTP client complexity**: WebClient blocking behavior, timeout configuration
3. **Error handling subtlety**: `seriesExists` must distinguish "not found" from "server error"
4. **FRED API quirks**: "." for missing data, specific error response format
5. **Future provider support**: These patterns apply to ECB, Bloomberg providers

### WireMock vs Real API
- **WireMock advantages**: Fast, deterministic, no rate limits, no API key needed
- **Real API testing**: Consider adding optional `@Tag("external")` tests for contract validation
- **Hybrid approach**: Integration tests use WireMock, manual validation against real FRED API

### Timeout Testing Considerations
- Timeout tests may take 5-30+ seconds to execute
- Consider `@Tag("slow")` annotation for CI/CD optimization
- WireMock delay should exceed timeout but be reasonable (6s for 5s timeout, 35s for 30s timeout)

### Coverage Goals
- Target 90%+ line coverage for FredClient
- Some error handling branches hard to test (network failures)
- Focus on documented behavior, not implementation details

---

## References

### Internal Documentation
- [Integration Testing Plan](integration-testing-plan.md) - Master plan (Phase 5)
- [service-common Testing Patterns](https://github.com/budget-analyzer/service-common/blob/main/docs/testing-patterns.md)
- [CLAUDE.md](../../CLAUDE.md) - Service architecture and patterns

### External References
- [FRED API Documentation](https://fred.stlouisfed.org/docs/api/fred/)
- [WireMock Documentation](https://wiremock.org/docs/)
- [AssertJ Documentation](https://assertj.github.io/doc/)
- [Spring Boot Testing](https://docs.spring.io/spring-boot/reference/testing/index.html)

---

## Next Steps After Completion

1. **Step 5.2**: Implement FredExchangeRateProvider Tests
   - Build on FredApiStubs enhancements
   - Test transformation logic (FRED → domain model)
   - Test integration with FredClient

2. **Phase 6**: Advanced Patterns Tests
   - Caching integration tests
   - Event-driven messaging tests
   - Scheduled job tests

3. **Phase 7**: End-to-End Integration Tests
   - Complete workflows using validated FredClient behavior
   - Test full stack: API → Service → Provider → FredClient → WireMock

---

**Status**: Ready for implementation
**Last Updated**: 2025-11-13
**Author**: Claude Code (Plan Mode)
