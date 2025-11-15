# Step 6.3: Scheduled Jobs Tests (ShedLock) - Implementation Plan

## Overview
Implement comprehensive testing for `ExchangeRateImportScheduler` with ShedLock distributed coordination. Currently **ZERO test coverage** exists for the scheduler.

## Current State Analysis

### Scheduler Implementation
**File**: `src/main/java/org/budgetanalyzer/currency/scheduler/ExchangeRateImportScheduler.java`

**Configuration**:
- **Cron**: `0 0 23 * * ?` (11 PM UTC daily) - configurable via `currency-service.exchange-rate-import.cron`
- **ShedLock**:
  - Lock name: "exchangeRateImport"
  - `lockAtMostFor`: "15m" (safety timeout)
  - `lockAtLeastFor`: "1m" (prevents rapid re-execution)

**Dependencies**:
- `ExchangeRateImportService` - performs actual import
- `TaskScheduler` - schedules retry attempts
- `MeterRegistry` - tracks metrics
- `CurrencyServiceProperties` - provides retry configuration

### ShedLock Configuration
**File**: `src/main/java/org/budgetanalyzer/currency/config/ShedLockConfig.java`

**Provider**: JDBC-based (PostgreSQL)
- Uses `JdbcTemplateLockProvider`
- Default lock duration: 10 minutes
- Database table: `shedlock` (created by Flyway migration V2)

**Database Schema**:
```sql
CREATE TABLE shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
```

### Retry Mechanism
**Configuration** (`application.yml`):
```yaml
currency-service:
  exchange-rate-import:
    retry:
      max-attempts: 3        # Total attempts (1 initial + 2 retries)
      delay-minutes: 1       # Fixed delay between retries
```

**Implementation**:
- Uses `TaskScheduler.schedule()` for delayed retry execution
- Fixed delay strategy (not exponential backoff)
- Metrics tracked per attempt

### Metrics Tracked
1. **Duration Timer**: `exchange.rate.import.duration`
   - Tags: `status`, `attempt`, `error` (on failure)
2. **Execution Counter**: `exchange.rate.import.executions`
   - Tags: `status`, `attempt`, `error` (on failure)
3. **Retry Counter**: `exchange.rate.import.retry.scheduled`
   - Tags: `attempt`
4. **Exhaustion Counter**: `exchange.rate.import.exhausted`
   - Incremented when all retries fail

### TestContainers Infrastructure
**Base Class**: `AbstractIntegrationTest`

**Available**:
- PostgreSQL 15 (with Flyway migrations including shedlock table)
- Redis 7 (cache disabled by default)
- RabbitMQ 3 (for messaging)

**Features**:
- Container reuse enabled
- `@BeforeEach` resets database
- Automatic property binding via `@ServiceConnection`

---

## Test Structure

### 1. Unit Tests: `ExchangeRateImportSchedulerTest.java`
**Location**: `src/test/java/org/budgetanalyzer/currency/scheduler/ExchangeRateImportSchedulerTest.java`

**Purpose**: Fast unit tests with mocked dependencies

**Test Cases**:

#### 1.1 Successful Import
```java
@Test
void scheduledImport_WhenSuccessful_RecordsMetricsAndDoesNotRetry()
```
- Mock service to succeed
- Verify service called exactly once
- Verify success metrics recorded (status=success, attempt=1)
- Verify NO retry scheduled (TaskScheduler not called)

#### 1.2 Failed Import with Retry
```java
@Test
void scheduledImport_WhenFails_SchedulesRetry()
```
- Mock service to throw exception
- Verify failure metrics recorded (status=failure, attempt=1)
- Verify retry scheduled via TaskScheduler with correct delay
- Verify retry attempt counter incremented

#### 1.3 Retry Success
```java
@Test
void retryImport_WhenSuccessful_RecordsMetricsAndStops()
```
- Simulate retry execution (attempt=2)
- Mock service to succeed
- Verify success metrics with attempt=2 tag
- Verify no further retries scheduled

#### 1.4 Retry Exhaustion
```java
@Test
void retryImport_WhenAllAttemptsFail_RecordsExhaustion()
```
- Configure max-attempts=3
- Mock service to fail all attempts
- Verify exhaustion metric recorded
- Verify NO retry scheduled after final attempt

#### 1.5 Metrics Recording
```java
@Test
void scheduledImport_RecordsAllMetrics()
```
- Verify duration timer started and stopped
- Verify execution counter incremented
- Verify tags correctly set (status, attempt, error)

#### 1.6 Exception Handling
```java
@Test
void scheduledImport_WhenRuntimeException_HandlesGracefully()
```
- Mock service to throw `RuntimeException`
- Verify exception caught and logged
- Verify metrics recorded with error tag
- Verify scheduler doesn't crash

**Mocks Required**:
- `ExchangeRateImportService` (mock)
- `TaskScheduler` (mock)
- `MeterRegistry` (mock or SimpleMeterRegistry)
- `CurrencyServiceProperties` (mock or real)

**Estimated Lines**: ~300

---

### 2. Integration Tests: `ExchangeRateImportSchedulerIntegrationTest.java`
**Location**: `src/test/java/org/budgetanalyzer/currency/scheduler/ExchangeRateImportSchedulerIntegrationTest.java`

**Purpose**: Test scheduler with real ShedLock coordination using PostgreSQL

**Setup**:
```java
@SpringBootTest
@Testcontainers
class ExchangeRateImportSchedulerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ExchangeRateImportScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExchangeRateImportService importService; // Real service

    @Autowired
    private MeterRegistry meterRegistry;

    @MockBean
    private ExchangeRateProvider provider; // Mock external provider

    private SchedulerTestHelper lockHelper;

    @BeforeEach
    void setUp() {
        super.setUp();
        lockHelper = new SchedulerTestHelper(jdbcTemplate);
        lockHelper.clearShedLock();
    }
}
```

**Test Cases**:

#### 2.1 Basic Scheduled Execution
```java
@Test
void scheduledImport_ExecutesSuccessfully()
```
- Mock provider to return sample exchange rates
- Call scheduler method directly (manual trigger)
- Verify exchange rates persisted in database
- Verify cache evicted (if cache enabled)
- Verify success metrics recorded

#### 2.2 Lock Acquisition
```java
@Test
void scheduledImport_AcquiresShedLock()
```
- Mock provider to return data
- Execute scheduler in separate thread
- Use Awaitility to wait for lock acquisition
- Verify `shedlock` table has row with:
  - `name` = "exchangeRateImport"
  - `locked_by` contains hostname/thread
  - `lock_until` is 15 minutes in future
  - `locked_at` is current timestamp

#### 2.3 Lock Release
```java
@Test
void scheduledImport_ReleasesLockAfterCompletion()
```
- Execute scheduler
- Wait for completion
- Verify `lock_until` updated to past (lock released)
- Verify subsequent execution can acquire lock

#### 2.4 Lock Duration Validation
```java
@Test
void scheduledImport_RespectsLockDuration()
```
- Execute scheduler
- Query `shedlock` table during execution
- Verify `lock_until` - `locked_at` ≈ 15 minutes (lockAtMostFor)
- Verify execution doesn't complete before 1 minute (lockAtLeastFor)

#### 2.5 Concurrent Execution Prevention
```java
@Test
void scheduledImport_PreventsConcurrentExecution()
```
- Create 2 threads, each calling scheduler
- Use `CountDownLatch` to trigger simultaneously
- Verify only 1 execution completes (check import count)
- Verify `shedlock` table shows single `locked_by` value
- Verify second thread blocks or skips execution

#### 2.6 Multi-Instance Simulation
```java
@Test
void scheduledImport_OnlyOneInstanceExecutes()
```
- Create 2 `ExchangeRateImportScheduler` beans with different configs
- Trigger both simultaneously
- Assert only 1 execution via service call counter
- Verify `shedlock` table shows single lock holder

#### 2.7 Lock on Exception
```java
@Test
void scheduledImport_ReleasesLockOnException()
```
- Mock provider to throw exception
- Execute scheduler (will fail)
- Verify lock released (lock_until updated)
- Verify subsequent execution can acquire lock
- Verify no stuck locks

#### 2.8 Lock Expiration
```java
@Test
void scheduledImport_AcquiresExpiredLock()
```
- Manually insert expired lock in `shedlock` table
- Execute scheduler
- Verify new instance acquires lock
- Verify `locked_by` updated to current instance

#### 2.9 Retry Mechanism Integration
```java
@Test
void scheduledImport_RetriesOnFailure()
```
- Mock provider to fail first attempt, succeed on retry
- Execute scheduler
- Wait for retry execution (use Awaitility)
- Verify 2 attempts total
- Verify final success

#### 2.10 Retry Max Attempts
```java
@Test
@TestPropertySource(properties = "currency-service.exchange-rate-import.retry.max-attempts=3")
void scheduledImport_RespectsMaxRetries()
```
- Mock provider to always fail
- Execute scheduler
- Wait for all retries
- Verify exactly 3 attempts
- Verify exhaustion metric incremented

#### 2.11 Configurable Retry Delay
```java
@Test
@TestPropertySource(properties = "currency-service.exchange-rate-import.retry.delay-minutes=2")
void scheduledImport_UsesConfiguredDelay()
```
- Mock provider to fail
- Execute scheduler
- Capture retry scheduled time
- Verify delay ≈ 2 minutes

#### 2.12 Failure Recovery
```java
@Test
void scheduledImport_RecoversFromServiceException()
```
- Mock provider to throw `RuntimeException` on first call
- Mock provider to succeed on second call
- Execute scheduler twice
- Verify first execution fails gracefully
- Verify second execution succeeds
- Verify no data corruption

**Configuration**:
```java
@TestConfiguration
static class TestConfig {
    @Bean
    @Primary
    TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.initialize();
        return scheduler;
    }
}
```

**Estimated Lines**: ~500

---

### 3. Test Utilities: `SchedulerTestHelper.java`
**Location**: `src/test/java/org/budgetanalyzer/currency/scheduler/SchedulerTestHelper.java`

**Purpose**: Utility methods for ShedLock testing

**Methods**:

```java
public class SchedulerTestHelper {

    private final JdbcTemplate jdbcTemplate;

    public SchedulerTestHelper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Verify lock acquired for given lock name
     */
    public void verifyLockAcquired(String lockName) {
        LockInfo lock = getLockInfo(lockName);
        assertThat(lock).isNotNull();
        assertThat(lock.lockUntil()).isAfter(Instant.now());
    }

    /**
     * Verify lock released (lock_until in past)
     */
    public void verifyLockReleased(String lockName) {
        LockInfo lock = getLockInfo(lockName);
        if (lock != null) {
            assertThat(lock.lockUntil()).isBefore(Instant.now());
        }
    }

    /**
     * Simulate lock expiration by updating lock_until to past
     */
    public void simulateLockExpiration(String lockName) {
        jdbcTemplate.update(
            "UPDATE shedlock SET lock_until = ? WHERE name = ?",
            Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
            lockName
        );
    }

    /**
     * Get lock information from database
     */
    public LockInfo getLockInfo(String lockName) {
        return jdbcTemplate.query(
            "SELECT name, lock_until, locked_at, locked_by FROM shedlock WHERE name = ?",
            (rs, rowNum) -> new LockInfo(
                rs.getString("name"),
                rs.getTimestamp("lock_until").toInstant(),
                rs.getTimestamp("locked_at").toInstant(),
                rs.getString("locked_by")
            ),
            lockName
        ).stream().findFirst().orElse(null);
    }

    /**
     * Insert expired lock for testing
     */
    public void insertExpiredLock(String lockName, String lockedBy) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES (?, ?, ?, ?)",
            lockName,
            Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
            Timestamp.from(now.minus(2, ChronoUnit.HOURS)),
            lockedBy
        );
    }

    /**
     * Clear all locks from shedlock table
     */
    public void clearShedLock() {
        jdbcTemplate.update("DELETE FROM shedlock");
    }

    /**
     * Wait for lock acquisition with timeout
     */
    public void waitForLockAcquisition(String lockName, Duration timeout) {
        await()
            .atMost(timeout)
            .pollInterval(Duration.ofMillis(100))
            .until(() -> getLockInfo(lockName) != null);
    }

    /**
     * Wait for lock release with timeout
     */
    public void waitForLockRelease(String lockName, Duration timeout) {
        await()
            .atMost(timeout)
            .pollInterval(Duration.ofMillis(100))
            .until(() -> {
                LockInfo lock = getLockInfo(lockName);
                return lock == null || lock.lockUntil().isBefore(Instant.now());
            });
    }

    /**
     * Record class for lock information
     */
    public record LockInfo(
        String name,
        Instant lockUntil,
        Instant lockedAt,
        String lockedBy
    ) {}
}
```

**Estimated Lines**: ~150

---

### 4. Optional: Cron Expression Test
**Location**: `src/test/java/org/budgetanalyzer/currency/scheduler/ExchangeRateImportSchedulerCronTest.java`

**Purpose**: Verify cron scheduling behavior (advanced/optional)

**Test Cases**:

```java
@Test
void cronExpression_ParsesCorrectly()
```
- Use `CronTrigger` to parse expression
- Verify next execution times
- Verify executes at 11 PM UTC

```java
@Test
void cronExpression_RespectsTimezone()
```
- Set system timezone to different zone
- Verify execution still at 11 PM UTC

```java
@Test
@TestPropertySource(properties = "currency-service.exchange-rate-import.cron=0 0 12 * * ?")
void cronExpression_CanBeOverridden()
```
- Override cron via property
- Verify new schedule used

**Estimated Lines**: ~100

---

## Test Data Strategy

### Mock Provider Responses
```java
// Success case
when(provider.getExchangeRates(any(), any(), any()))
    .thenReturn(List.of(
        new ExchangeRate(LocalDate.of(2024, 1, 1), BigDecimal.valueOf(1.10)),
        new ExchangeRate(LocalDate.of(2024, 1, 2), BigDecimal.valueOf(1.11))
    ));

// Failure case
when(provider.getExchangeRates(any(), any(), any()))
    .thenThrow(new RuntimeException("Provider unavailable"));

// Retry success case
when(provider.getExchangeRates(any(), any(), any()))
    .thenThrow(new RuntimeException("Temporary failure"))
    .thenReturn(List.of(/* data */));
```

### Test Constants
Use existing `TestConstants` class for:
- Currency codes (EUR, GBP, JPY)
- FRED series IDs
- Test dates
- Sample exchange rates

---

## Assertions Strategy

### ShedLock Verification
```java
// Direct JDBC queries
LockInfo lock = lockHelper.getLockInfo("exchangeRateImport");
assertThat(lock).isNotNull();
assertThat(lock.lockUntil()).isAfter(Instant.now());
assertThat(lock.lockedBy()).contains(InetAddress.getLocalHost().getHostName());

// Async with Awaitility
lockHelper.waitForLockAcquisition("exchangeRateImport", Duration.ofSeconds(5));
```

### Metrics Verification
```java
// Timer
Timer timer = meterRegistry.find("exchange.rate.import.duration")
    .tag("status", "success")
    .tag("attempt", "1")
    .timer();
assertThat(timer).isNotNull();
assertThat(timer.count()).isEqualTo(1);

// Counter
Counter counter = meterRegistry.find("exchange.rate.import.executions")
    .tag("status", "failure")
    .tag("attempt", "2")
    .counter();
assertThat(counter).isNotNull();
assertThat(counter.count()).isEqualTo(1);
```

### Multi-Instance Testing
```java
AtomicInteger executionCount = new AtomicInteger(0);
when(mockService.importExchangeRates()).thenAnswer(inv -> {
    executionCount.incrementAndGet();
    return null;
});

// Trigger both schedulers
CountDownLatch latch = new CountDownLatch(2);
new Thread(() -> { scheduler1.scheduledImport(); latch.countDown(); }).start();
new Thread(() -> { scheduler2.scheduledImport(); latch.countDown(); }).start();

latch.await(10, TimeUnit.SECONDS);

assertThat(executionCount.get()).isEqualTo(1); // Only one executed
```

---

## Configuration Requirements

### Test Properties
**File**: `src/test/resources/application-test.yml` (or `@TestPropertySource`)

```yaml
currency-service:
  exchange-rate-import:
    cron: "0 0 23 * * ?"
    import-on-startup: false  # Disable for tests
    retry:
      max-attempts: 3
      delay-minutes: 1
  fred:
    api-key: test-key-not-used

spring:
  task:
    scheduling:
      pool:
        size: 2
```

### Test Configuration Class
```java
@TestConfiguration
static class SchedulerTestConfig {

    @Bean
    @Primary
    ExchangeRateProvider mockProvider() {
        return Mockito.mock(ExchangeRateProvider.class);
    }

    @Bean
    TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("test-scheduler-");
        scheduler.initialize();
        return scheduler;
    }
}
```

---

## Dependencies

All dependencies already available in `build.gradle.kts`:
- ✅ `spring-boot-starter-test` (JUnit 5, Mockito, AssertJ)
- ✅ `spring-boot-testcontainers` (TestContainers integration)
- ✅ `testcontainers-postgresql` (PostgreSQL container)
- ✅ `awaitility` (async assertions)
- ✅ `shedlock-spring` + `shedlock-provider-jdbc` (production dependencies)

---

## Implementation Order

1. **Create `SchedulerTestHelper.java`** (~1 hour)
   - Implement all utility methods
   - Test utility methods in isolation if needed

2. **Write Unit Tests** (~2 hours)
   - `ExchangeRateImportSchedulerTest.java`
   - Start with simple success case
   - Add failure/retry cases
   - Add metrics verification

3. **Write Integration Tests - Basic** (~2 hours)
   - `ExchangeRateImportSchedulerIntegrationTest.java`
   - Basic execution test
   - Lock acquisition/release tests
   - Use `SchedulerTestHelper` for assertions

4. **Write Integration Tests - Advanced** (~2 hours)
   - Multi-instance coordination tests
   - Retry mechanism tests
   - Failure recovery tests
   - Lock expiration tests

5. **Optional Cron Tests** (~1 hour)
   - `ExchangeRateImportSchedulerCronTest.java`
   - Only if time permits

---

## Success Criteria

### Coverage Requirements
- ✅ Minimum 80% line coverage for `ExchangeRateImportScheduler`
- ✅ All public methods tested
- ✅ All error paths tested

### Functional Requirements (from Step 6.3)
- ✅ **Scheduled Execution**: Verify method runs on schedule
- ✅ **Lock Acquisition**: Verify ShedLock prevents concurrent execution
- ✅ **Lock Duration**: Verify `lockAtMostFor` and `lockAtLeastFor` settings
- ✅ **Retry Mechanism**: Verify configurable retry attempts and delays
- ✅ **Failure Recovery**: Verify lock released on exception
- ✅ **Multi-Instance**: Simulate multiple pods, verify single execution

### Quality Requirements
- ✅ All tests pass with `./gradlew test`
- ✅ No flaky tests (use Awaitility with reasonable timeouts)
- ✅ Tests follow existing patterns (see `TransactionalOutboxIntegrationTest.java`)
- ✅ Clear test names following `methodName_Scenario_ExpectedResult` convention
- ✅ Proper cleanup in `@BeforeEach` and `@AfterEach`

---

## Estimated Effort

| Task | Estimated Time |
|------|----------------|
| SchedulerTestHelper | 1 hour |
| Unit Tests | 2 hours |
| Integration Tests (Basic) | 2 hours |
| Integration Tests (Advanced) | 2 hours |
| Optional Cron Tests | 1 hour |
| **Total** | **8 hours** |

---

## Reference Files

### Existing Test Patterns to Follow
- `src/test/java/org/budgetanalyzer/currency/integration/TransactionalOutboxIntegrationTest.java` - Awaitility, async testing
- `src/test/java/org/budgetanalyzer/currency/integration/RetryAndErrorHandlingIntegrationTest.java` - Retry mechanism testing
- `src/test/java/org/budgetanalyzer/currency/base/AbstractIntegrationTest.java` - TestContainers setup

### Production Code
- `src/main/java/org/budgetanalyzer/currency/scheduler/ExchangeRateImportScheduler.java` - Scheduler implementation
- `src/main/java/org/budgetanalyzer/currency/config/ShedLockConfig.java` - Lock configuration
- `src/main/java/org/budgetanalyzer/currency/config/SchedulingConfig.java` - Scheduling configuration

### Database
- `src/main/resources/db/migration/V2__add_shedlock_table.sql` - ShedLock table schema

---

## Notes

### Key Testing Challenges
1. **Async Nature**: Scheduler runs asynchronously - use Awaitility for all timing-dependent assertions
2. **Lock Timing**: Lock acquisition/release happens quickly - need polling with reasonable intervals
3. **Multi-Instance**: Simulating multiple pods in single JVM - use separate scheduler instances with different configs
4. **Retry Delays**: Retries happen after delay - tests may be slow, consider reducing delays in test config

### Best Practices
- Always clean up `shedlock` table in `@BeforeEach`
- Use `@DirtiesContext` sparingly (slow) - prefer manual cleanup
- Mock external provider (FRED) to avoid network calls
- Use `SimpleMeterRegistry` for unit tests, real `MeterRegistry` for integration tests
- Set reasonable Awaitility timeouts (5-10 seconds max)

### Future Enhancements
- Test import-on-startup behavior
- Test graceful shutdown (scheduler stops cleanly)
- Test metrics export to monitoring system
- Test cron expression parsing edge cases (leap years, DST transitions)
