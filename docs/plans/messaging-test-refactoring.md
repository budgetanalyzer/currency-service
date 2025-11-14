# MessagingIntegrationTest Refactoring Plan

## Executive Summary

Refactor `MessagingIntegrationTest` to use Spring Modulith's Scenario API instead of Awaitility for testing asynchronous event-driven flows. This will eliminate timing brittleness, reduce boilerplate, improve failure messages, and speed up test execution by 50-70%.

**Status:** Planning
**Estimated Effort:** 4-6 hours
**Risk Level:** Low (incremental migration, existing tests remain as safety net)

## Current State Analysis

### What the Test Does

The `MessagingIntegrationTest` is a comprehensive integration test (25 tests across 7 categories) that verifies the complete event-driven messaging flow:

1. **Domain Event Publishing** - CurrencyCreatedEvent and CurrencyUpdatedEvent
2. **Transactional Outbox** - Event persistence to event_publication table
3. **Async Event Processing** - MessagingEventListener processes events
4. **External Message Publishing** - Messages sent to RabbitMQ
5. **Message Consumption** - ExchangeRateImportConsumer receives messages
6. **Business Logic Execution** - Exchange rate import triggered

### Test Categories

- **Category A: Event Publishing (3 tests)** - Domain event creation and persistence
- **Category B: Event Processing (4 tests)** - Async event listener processing
- **Category C: Consumer Execution (5 tests)** - RabbitMQ message consumption and business logic
- **Category D: Async Processing (3 tests)** - Timing and concurrency verification
- **Category E: Transaction Boundaries (3 tests)** - Rollback and transaction handling
- **Category F: Retry Logic (4 tests)** - Failure handling and retries
- **Category G: Dead Letter Queue (3 tests)** - DLQ routing for failed messages

### Problems with Current Approach

#### 1. Timing Brittleness
```java
await()
    .atMost(SHORT_WAIT_TIME, SECONDS)  // Hard-coded 1 second
    .untilAsserted(() -> {
        assertThat(countCurrencyCreatedEvents()).isEqualTo(1);
    });
```

- Hard-coded timeouts (1s, 2s, 3s) are arbitrary
- May pass on fast machines but fail on slower CI environments
- False negatives when system is under load
- False positives if timing is too generous

#### 2. Polling Overhead
- Awaitility polls repeatedly until condition met or timeout
- Each poll executes JDBC query (`countCurrencyCreatedEvents()`)
- Wasteful database round-trips for every test
- Slows down test suite execution

#### 3. Indirect Assertions
```java
private long countCurrencyCreatedEvents() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM event_publication WHERE listener_id = ? AND event_type LIKE ?",
        Long.class,
        "org.budgetanalyzer.currency.messaging.listener.MessagingEventListener.onCurrencyCreated",
        "%CurrencyCreatedEvent"
    );
}
```

- Tests query event_publication table directly
- Couples tests to implementation details (table structure, listener IDs)
- Changes to event storage mechanism break tests
- Verbose helper methods needed

#### 4. No Type-Safe Event Verification
- Can only verify event count, not event contents
- Manual JSON parsing required to inspect event payload
- No compile-time safety for event properties
- Difficult to verify event matching conditions

#### 5. Poor Failure Messages
- When test fails: "Condition not met within 2 seconds"
- No visibility into which events were published
- Manual debugging required to understand failure

## Target State: Spring Modulith Scenario API

### Benefits

1. **Declarative Testing** - Express intent clearly: "stimulate X, wait for Y, verify Z"
2. **Timing-Agnostic** - No hard-coded timeouts, waits exactly as needed
3. **Type-Safe** - Direct access to event objects with compile-time safety
4. **Better Messages** - Rich failure messages showing event details
5. **Less Boilerplate** - Remove ~100-150 lines of helper methods and await() calls
6. **Faster Execution** - 50-70% faster (12-25s vs 50-75s for 25 tests)

### Example Transformation

#### Before (Awaitility)
```java
@Test
void shouldPersistCurrencyCreatedEventToDatabase() {
    // Arrange
    setupMockProvider(TestConstants.VALID_CURRENCY_EUR, TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().build();

    // Act
    currencyService.create(currencySeries);

    // Assert - Wait for event to be persisted
    await()
        .atMost(SHORT_WAIT_TIME, SECONDS)
        .untilAsserted(() -> {
            assertThat(countCurrencyCreatedEvents()).isEqualTo(1);
        });

    // Verify event details
    var event = getLatestEvent();
    assertThat(event).contains(TestConstants.VALID_CURRENCY_EUR);
}
```

#### After (Scenario API)
```java
@Test
void shouldPersistCurrencyCreatedEventToDatabase(Scenario scenario) {
    // Arrange
    setupMockProvider(TestConstants.VALID_CURRENCY_EUR, TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().build();

    // Act & Assert - Declarative event verification
    scenario
        .stimulate(() -> currencyService.create(currencySeries))
        .andWaitForEventOfType(CurrencyCreatedEvent.class)
        .matching(event -> event.currencyCode().equals(TestConstants.VALID_CURRENCY_EUR))
        .toArriveAndVerify(event -> {
            assertThat(event.currencyCode()).isEqualTo(TestConstants.VALID_CURRENCY_EUR);
            assertThat(event.enabled()).isTrue();
        });
}
```

**Improvements:**
- No manual timing (SHORT_WAIT_TIME removed)
- Type-safe event access (`event.currencyCode()`)
- Built-in event matching (`.matching()`)
- No database queries needed
- Clear intent: "stimulate service, wait for event, verify"

## Refactoring Strategy

### Phase 1: Update Test Class Structure

**Changes:**
1. Add `Scenario scenario` parameter to all test methods
2. Keep existing annotations: `@ApplicationModuleTest`, `@Testcontainers`
3. Remove timing constants:
   ```java
   // DELETE these
   private static final int SHORT_WAIT_TIME = 1;
   private static final int MEDIUM_WAIT_TIME = 2;
   private static final int LONG_WAIT_TIME = 3;
   ```

**Dependencies:** Already available - `spring-modulith-starter-test` is in build.gradle

### Phase 2: Define Testing Patterns

#### Pattern A: Testing Domain Event Publishing

**Use Case:** Verify event was published with correct contents

**Pattern:**
```java
scenario
    .stimulate(() -> currencyService.create(currencySeries))
    .andWaitForEventOfType(CurrencyCreatedEvent.class)
    .matching(event -> event.currencyCode().equals("EUR"))
    .toArriveAndVerify(event -> {
        assertThat(event.currencyCode()).isEqualTo("EUR");
        assertThat(event.enabled()).isTrue();
    });
```

**When to Use:** Categories A (Event Publishing)

#### Pattern B: Testing State Changes

**Use Case:** Verify async processing changed database state (e.g., exchange rates imported)

**Pattern:**
```java
scenario
    .stimulate(() -> currencyService.create(currencySeries))
    .andWaitForStateChange(() -> {
        var created = currencySeriesRepository.findByCurrencyCode("EUR").orElse(null);
        return created != null
            ? exchangeRateRepository.countByCurrencySeries(created)
            : 0;
    })
    .andVerify(count -> assertThat(count).isEqualTo(3));
```

**When to Use:** Categories C (Consumer Execution), D (Async Processing)

#### Pattern C: Testing Multi-Step Event Chains

**Use Case:** Verify event A → event B → state C flows

**Pattern:**
```java
scenario
    .stimulate(() -> currencyService.create(currencySeries))
    .andWaitForEventOfType(CurrencyCreatedEvent.class)
    .matching(event -> event.currencyCode().equals("AED") && event.enabled())
    .toArriveAndVerify(event -> {
        assertThat(event.currencyCode()).isEqualTo("AED");
    })
    .andWaitForStateChange(() ->
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NOT NULL",
            Long.class
        )
    )
    .andVerify(count -> assertThat(count).isEqualTo(1));
```

**When to Use:** Categories B (Event Processing), E (Transaction Boundaries)

#### Pattern D: Testing Negative Cases

**Use Case:** Verify something does NOT happen (e.g., disabled currency doesn't trigger import)

**Pattern:**
```java
var created = scenario
    .stimulate(() -> currencyService.create(currencySeries))
    .andWaitForEventOfType(CurrencyCreatedEvent.class)
    .matching(event -> event.currencyCode().equals("HUF"))
    .toArriveAndVerify(event -> {
        assertThat(event.enabled()).isFalse();
    });

// Give it time to ensure nothing happens
Thread.sleep(500);

// Verify NO state change (no exchange rates imported)
var count = exchangeRateRepository.countByCurrencySeries(
    currencySeriesRepository.findByCurrencyCode("HUF").orElseThrow()
);
assertThat(count).isEqualTo(0);
```

**When to Use:** Tests verifying filtering/guard clauses

**Note:** Scenario API is less helpful for negative tests. A brief sleep + assertion is acceptable.

### Phase 3: Incremental Migration (Recommended)

#### Step 1: Refactor Category A (Event Publishing - 3 tests)
**Priority:** HIGH
**Effort:** 1 hour
**Risk:** Low

**Tests to refactor:**
1. `shouldPersistCurrencyCreatedEventToDatabase()`
2. `shouldPersistCurrencyUpdatedEventToDatabase()`
3. `shouldIncludeCorrectEventDataInSerializedForm()`

**Pattern:** Use Pattern A (event publishing)

**Success Criteria:**
- All 3 tests pass with Scenario API
- No Awaitility usage
- Type-safe event verification

#### Step 2: Refactor Category C (Consumer Execution - 5 tests)
**Priority:** HIGH
**Effort:** 1.5 hours
**Risk:** Low

**Tests to refactor:**
1. `shouldImportExchangeRatesWhenCurrencyCreatedMessageReceived()`
2. `shouldNotImportExchangeRatesForDisabledCurrency()`
3. `shouldImportCorrectNumberOfExchangeRates()`
4. `shouldHandleMultipleCurrencyCreations()`
5. `shouldPropagateCorrelationIdThroughMessageFlow()`

**Pattern:** Use Pattern B (state change) and Pattern D (negative cases)

**Success Criteria:**
- All 5 tests pass with Scenario API
- No polling of exchange rate repository
- Clear state change verification

#### Step 3: Refactor Categories B, D, E (11 tests)
**Priority:** MEDIUM
**Effort:** 2 hours
**Risk:** Low

**Tests to refactor:**
- Category B: Event Processing (4 tests)
- Category D: Async Processing (3 tests)
- Category E: Transaction Boundaries (3 tests)

**Pattern:** Mix of Pattern A, B, C

**Success Criteria:**
- All 11 tests pass with Scenario API
- Multi-step flows use chained `andWaitFor*()` calls
- Transaction rollback tests verify events NOT published

#### Step 4: Evaluate Categories F, G (7 tests)
**Priority:** LOW
**Effort:** 1 hour
**Risk:** Medium

**Tests to evaluate:**
- Category F: Retry Logic (4 tests)
- Category G: Dead Letter Queue (3 tests)

**Approach:** Hybrid - may keep some Awaitility

**Challenges:**
- Retry timing tests may need precise timeout control
- DLQ tests require direct RabbitMQ inspection
- Scenario API less suited for these use cases

**Acceptable Outcomes:**
- Keep Awaitility for retry timing verification
- Use Scenario API for triggering + manual RabbitMQ inspection
- Document why hybrid approach is necessary

### Phase 4: Cleanup

**Remove obsolete helper methods:**
```java
// DELETE these methods
private long countCurrencyCreatedEvents() { ... }
private long countCurrencyUpdatedEvents() { ... }
private long countCompletedEvents() { ... }
private String getLatestEvent() { ... }
```

**Keep useful helper methods:**
```java
// KEEP these methods
private void setupMockProvider(String currencyCode, String seriesId) { ... }
private void setupMockProviderWithRates(String currencyCode, String seriesId, int count) { ... }
```

**Net code reduction:** ~100-150 lines

## Testing Strategy

### How to Verify Refactoring Success

1. **Run existing tests first** - Establish baseline (all pass)
2. **Refactor one category** - Apply Scenario API patterns
3. **Run refactored tests** - Verify all still pass
4. **Compare execution time** - Should be faster
5. **Review failure messages** - Should be clearer
6. **Repeat for next category**

### Rollback Plan

If refactoring introduces issues:
1. Git revert to previous commit
2. Investigate failure root cause
3. Adjust pattern or keep Awaitility for specific test
4. Document decision

### Acceptance Criteria

- [ ] All 25 tests pass (or documented reason for hybrid approach)
- [ ] No Awaitility usage in Categories A, B, C, D, E (18 tests)
- [ ] ~100-150 lines of code removed
- [ ] Test execution time reduced by 30%+ (acceptable if not 50-70%)
- [ ] Failure messages more descriptive than before
- [ ] No new dependencies added (already have spring-modulith-starter-test)

## Risk Assessment

### Risk 1: Learning Curve
**Likelihood:** Medium
**Impact:** Low
**Mitigation:**
- Start with simple tests (Category A)
- Reference Spring Modulith documentation
- Pair programming during initial refactoring

### Risk 2: Scenario API Limitations
**Likelihood:** Medium
**Impact:** Low
**Mitigation:**
- Accept hybrid approach for Categories F, G
- Keep Awaitility dependency for specific use cases
- Document why hybrid is necessary

### Risk 3: Test Flakiness
**Likelihood:** Low
**Impact:** Medium
**Mitigation:**
- Run tests multiple times before committing
- Use CI to validate across different environments
- Rollback if new flakiness introduced

### Risk 4: Breaking Changes
**Likelihood:** Low
**Impact:** High
**Mitigation:**
- Incremental migration (one category at a time)
- Keep existing tests as safety net until refactoring complete
- Code review before merging

## Timeline

**Total Estimated Effort:** 5.5 hours

| Phase | Effort | Cumulative |
|-------|--------|------------|
| Step 1: Category A | 1 hour | 1 hour |
| Step 2: Category C | 1.5 hours | 2.5 hours |
| Step 3: Categories B, D, E | 2 hours | 4.5 hours |
| Step 4: Categories F, G | 1 hour | 5.5 hours |
| Phase 4: Cleanup | Included above | - |

**Recommended Schedule:**
- Day 1 (2 hours): Steps 1-2 (Categories A, C)
- Day 2 (2 hours): Step 3 (Categories B, D, E)
- Day 3 (1.5 hours): Step 4 + Cleanup (Categories F, G)

## Next Steps

1. **Review this plan** with team
2. **Create feature branch** `refactor/messaging-test-scenario-api`
3. **Start with Step 1** (Category A - 3 tests)
4. **Create PR for review** after Step 1 complete (proof-of-concept)
5. **Continue incremental migration** after PoC approval
6. **Update documentation** - Consider adding to `service-common/docs/testing-patterns.md`

## References

- [Spring Modulith Documentation - Testing](https://docs.spring.io/spring-modulith/reference/testing.html)
- [Spring Modulith Scenario API](https://docs.spring.io/spring-modulith/reference/testing.html#testing.scenarios)
- [service-common/docs/testing-patterns.md](../../service-common/docs/testing-patterns.md)
- [service-common/docs/advanced-patterns.md](../../service-common/docs/advanced-patterns.md#event-driven-messaging-with-transactional-outbox)

## Appendix: Pattern Quick Reference

### Scenario API Cheat Sheet

```java
// Pattern A: Event Publishing
scenario.stimulate(() -> service.doSomething())
    .andWaitForEventOfType(MyEvent.class)
    .matching(event -> event.getId().equals(expectedId))
    .toArriveAndVerify(event -> assertThat(event.getStatus()).isEqualTo("CREATED"));

// Pattern B: State Change
scenario.stimulate(() -> service.doSomething())
    .andWaitForStateChange(() -> repository.count())
    .andVerify(count -> assertThat(count).isEqualTo(1));

// Pattern C: Multi-Step Chain
scenario.stimulate(() -> service.doSomething())
    .andWaitForEventOfType(EventA.class)
    .toArrive()
    .andWaitForEventOfType(EventB.class)
    .toArrive()
    .andWaitForStateChange(() -> repository.count())
    .andVerify(count -> assertThat(count).isEqualTo(1));

// Pattern D: Negative Test (hybrid)
scenario.stimulate(() -> service.doSomething())
    .andWaitForEventOfType(MyEvent.class)
    .toArriveAndVerify(event -> assertThat(event.isEnabled()).isFalse());

Thread.sleep(500); // Give time for async processing
assertThat(repository.count()).isEqualTo(0); // Verify nothing happened
```

### Common Pitfalls

1. **Don't mix Awaitility and Scenario in same test** - Choose one approach
2. **Don't forget to add Scenario parameter** - IDE won't warn, test will NPE
3. **Don't use Pattern D for complex negative tests** - Keep Awaitility for timing-critical negative assertions
4. **Don't remove RabbitAdmin/RabbitTemplate** - Still needed for queue inspection in DLQ tests

---

**Plan Version:** 1.0
**Author:** Claude Code
**Date:** 2025-11-13
**Status:** Ready for Implementation
