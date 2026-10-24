# Spring Boot 4 Migration Review Remediation

Fix the defects, dead configuration, deprecated calls, and documentation drift found while
reviewing the Spring Boot 4.1.2 migration of this repository against `main`. When this plan is
done:

- Empty exchange-rate results are safe to cache.
- The import catch-all error path has deterministic test coverage.
- Duplicate and unused build and config items are gone.
- A `U8` rollback script documents how to undo `V8`, following the existing `U*` convention.
- Completed event publications are kept permanently as an audit trail. Code comments,
  configuration, and documentation all say so.
- Compiling main and test code produces no deprecation or removal warnings.
- Every document in `README.md` and `docs/` describes the repository as it is now.

Plan-wide constraints:

- Follow `AGENTS.md`. Do not run Git write commands. Write only inside this repository. Do not use
  subagents for exploration.
- Before writing Java, read
  [code-quality-standards.md](../../../service-common/docs/code-quality-standards.md).
- Before changing tests, read the relevant sections of
  [testing-patterns.md](../../../service-common/docs/testing-patterns.md). In particular:
  - Do not mock or spy application-owned beans.
  - Do not weaken, delete, or disable existing tests.
  - Keep reusable values in `src/test/java/org/budgetanalyzer/currency/fixture/`.
- Prerequisites for every phase that runs Gradle:
  - Docker must be running.
  - `org.budgetanalyzer:*:0.0.18-SNAPSHOT` must be resolvable from Maven Local.

  If either is missing, stop and report it. Do not publish from `../service-common/`.
- Do not edit any already-applied Flyway migration (`V1`–`V8`), not even comments. Editing one
  changes its checksum, and `validate-on-migrate: true` would then fail startup on existing
  databases. This includes the inaccurate "polls" comment in
  `V5__add_event_publication_table.sql`.
- Keep the current API behavior where `CurrencySeriesCreateRequest.enabled` is required (a
  missing or null value returns 400). Document it; do not change it.
- Retention decision: keep every completed event publication forever as an audit trail. Rely on
  Spring Modulith's default `completion-mode` (`update`). Do not set it explicitly, and do not add
  any cleanup, archiving, or time-based deletion. Remove anything that claims a retention window.

Evidence gathered during the review. Rely on it, but re-verify anything a phase changes.

- **Empty results poison the cache.** `ExchangeRateService.getExchangeRates` returns `List.of()`
  when a date range has no rates.
  - `List.of()` returns a final JDK class, so `DefaultTyping.NON_FINAL` writes `[ ]` with no type
    id.
  - The next cache read throws `SerializationException`. This fails on both `main` and this
    branch.
  - `Collections.emptyList()` and `ArrayList` round-trip correctly. Non-empty results are built
    as `ArrayList` and round-trip correctly.
  - Entries written by `main`'s Jackson 2 serializer are byte-compatible with the Jackson 3
    serializer and read back as equal values.
- **The type allowlist in `CacheConfig.redisSerializer` does nothing.**
  `allowIfBaseType(Object.class)` accepts every type, so the per-package rules never apply.
  - With that rule removed and `allowIfSubType("java.math")` added (`BigDecimal` is non-final and
    is written with a type id), all cached shapes still round-trip.
  - Unknown types such as `java.lang.ProcessBuilder` are then rejected by the validator itself.
- **`spring.cache.redis.*` is ignored.** Boot's `RedisCacheConfiguration` auto-configuration
  calls `ObjectProvider.getIfAvailable(...)`, so the application's `RedisCacheConfiguration` bean
  replaces those properties entirely. Only `enable-statistics` and `cache-names` are read either
  way, and this repository sets neither.
- **Some Rabbit consumer retry keys are ignored.** `spring.cloud.stream.rabbit.bindings.*.consumer`
  has no `max-attempts`, `initial-interval`, `max-interval`, or `multiplier` fields. Binding-level
  `max-attempts` and `back-off-*` keys are also ignored; those belong under `.consumer.`. The
  effective policy is the Spring Cloud Stream `ConsumerProperties` default: 3 attempts, 1000 ms
  initial back-off, 2.0 multiplier, 10000 ms maximum. That is the same as the values the ignored
  keys intended.
- **`spring.data.redis.lettuce.pool.*` does nothing.** `commons-pool2` is not on the runtime
  classpath.
- **`spring.modulith.events.delete-completion-after` is not a Spring Modulith 2.1.2 property.**
  It has never bound, on `main` or this branch, so the "30 days" retention it describes never
  happened. `completion-mode` defaults to `update` (the other values are `delete` and `archive`),
  so a completed publication gets `completion_date` and `status = 'COMPLETED'` and is kept
  indefinitely. That matches the retention decision. No staleness monitoring is configured.
- **`V8` has no rollback script.** `U1`–`U5` and `U7` exist. `V6` is a data-only seed with no
  rollback script, and this plan leaves it that way. Per
  [service-common advanced patterns](../../../service-common/docs/advanced-patterns.md), `U*`
  scripts are documentation only and Flyway Community never runs them. None of the existing
  scripts remove their `flyway_schema_history` row.
- **`src/test/resources/testcontainers.properties` does nothing.** Testcontainers 2.0.5 reads
  `docker.client.strategy` only from the environment and `~/.testcontainers.properties`, not from
  the classpath. The reuse property in the file is already commented out, and the listed
  `DockerMachineClientProviderStrategy` is deprecated.
- **Javac with `-Xlint:deprecation` reports exactly nine warnings:**
  - two uses of `org.springframework.lang.NonNull` in `SchedulingConfig`;
  - seven uses of `StatusResultMatchers.isUnprocessableEntity()`, in
    `CurrencySeriesControllerIntegrationTest` and `ExchangeRateControllerIntegrationTest`.

  `isUnprocessableContent()` and `org.jspecify.annotations.NonNull` are available.
- **Why coverage of the import catch-all moves between runs.** The catch-all is the
  `catch (Exception e)` block in `ExchangeRateImportService.importExchangeRates(CurrencySeries,
  LocalDate)`. Each cached Spring test context binds its own `test-${random.uuid}` consumer group
  to the same exchange. One published import request is therefore consumed once per live context.
  On `main`, one EUR request (one correlation ID) was imported concurrently by two contexts'
  consumers, and late consumers raced test cleanup. Spring Framework 7 also changed test-context
  pausing (`spring.test.context.cache.pause` defaults to `ON_CONTEXT_SWITCH`), which changes how
  long those consumers stay active. No test targets the catch-all on purpose.

Non-goals for the whole plan:

- Changing the test messaging topology so contexts stop consuming each other's messages.
- Adding a `U6` rollback script, or editing the existing `U*` scripts.
- Adding cleanup, archiving, or staleness monitoring for event publications.
- Publishing service-common.
- Changing default-valued properties that do bind (for example `spring.flyway.enabled`).

## Phase 1: Cache Correctness and Cache Configuration Cleanup

### Workspace

.

### Goal

Make every cacheable exchange-rate result survive a Redis round trip, cover the cached read path
with API-level tests, and remove the duplicate and ineffective cache configuration without changing
the Redis key namespace.

### Scope

- `src/main/java/org/budgetanalyzer/currency/service/ExchangeRateService.java`
- `src/main/java/org/budgetanalyzer/currency/config/CacheConfig.java`
- The `spring.cache.redis` block in `src/main/resources/application.yml`
- `src/test/java/org/budgetanalyzer/currency/api/ExchangeRateImportCacheIntegrationTest.java`
- Test fixtures, only if new reusable values are needed

### Non-goals

- Do not add a `CacheErrorHandler`, a TTL, or new cached methods.
- Do not change cache eviction behavior or the transaction-aware or synchronous cache writer
  settings.
- Do not rename the test class.

### Required context

- `AGENTS.md`
- `../service-common/docs/code-quality-standards.md`
- `../service-common/docs/testing-patterns.md`: the integration and Testcontainers sections, and
  "Keep Application-Owned Spring Beans Real"
- `docs/advanced-patterns-usage.md`: the Redis caching section, for current intent only (its
  facts are corrected in Phase 6)
- `src/main/java/org/budgetanalyzer/currency/api/ExchangeRateController.java`: the request
  parameters for `GET /v1/exchange-rates`
- `src/test/java/org/budgetanalyzer/currency/service/ExchangeRateServiceIntegrationTest.java`:
  the `queryForDateRangeWithNoDataReturnsEmptyList` arrangement

### Execution steps

1. In `ExchangeRateImportCacheIntegrationTest`, which already runs with
   `spring.cache.type=redis`, add API-level tests through `performGet` (or the equivalent helper
   in `AbstractControllerTest`):
   - **Empty result, repeated.** Arrange EUR rates for 2024-01-01 through 2024-01-05. Request
     `GET /v1/exchange-rates` for EUR over a later range with no rates (for example
     `TestConstants.DATE_2024_JUN_15` through `+10` days) twice. Assert both responses are 200
     with an empty JSON array. Assert the `exchangeRates` cache holds the key
     `EUR:<start>:<end>` after the first request.
   - **Non-empty result, repeated.** Arrange rates and request a range that contains them, twice.
     Assert both responses are 200 and have identical bodies. Assert the cache holds the entry
     after the first request.
   - **Key namespace.** Use the auto-configured `StringRedisTemplate` to assert that the Redis key
     `currency-service:exchangeRates::EUR:<start>:<end>` exists after a cached request. The
     namespace matters because the Redis instance is shared with other services.
2. Run the new tests before changing production code. Confirm the empty-result test fails on its
   second request and the others pass. Record that failure in your completion notes.
3. In `ExchangeRateService.getExchangeRates`, replace `return List.of();` with
   `return Collections.emptyList();`. Keep the result immutable and make it round-trip through the
   typed serializer.
4. In `CacheConfig`:
   - In `redisSerializer`, remove `.allowIfBaseType(Object.class)` and add
     `.allowIfSubType("java.math")`. Keep the existing `org.budgetanalyzer`, `java.util`, and
     `java.time` rules.
   - Remove the duplicated per-cache configuration:
     - Move `.prefixCacheNameWith("currency-service:")` into the `cacheConfiguration` default
       bean.
     - Delete the `withCacheConfiguration(EXCHANGE_RATES_CACHE, ...)` block from
       `redisCacheManagerBuilderCustomizer`.
     - Keep `cacheWriter(... immediateWrites())` and `transactionAware()`.
     - Drop the serializer parameter from the customizer if it is no longer used.
   - Update the class and method Javadoc to describe the actual behavior:
     - no TTL;
     - eviction of all entries on import, after commit;
     - synchronous writes;
     - key structure;
     - the type allowlist;
     - cached values must not be JDK immutable collections such as `List.of()` or
       `Stream.toList()`, because final types are written without a type id.

     Do not keep the claim that the class sets an "appropriate TTL".
5. Remove the whole `spring.cache.redis` block from `src/main/resources/application.yml`
   (`time-to-live`, `cache-null-values`, `use-key-prefix`, `key-prefix`, and its comments). Keep
   `spring.cache.type: redis`.
6. Run `./gradlew clean spotlessApply`, then the focused tests in Validation.

### Implementation notes

- The only cacheable method is `ExchangeRateService.getExchangeRates`. Its non-empty path returns
  the `ArrayList` built in `buildDenseExchangeRates` and needs no change.
- After step 4, `exchangeRates` uses the default configuration, which disables caching null
  values. The method never returns null, so behavior does not change.
- Existing tests put and read `String` cache values. `String` is final and written without a type
  id, so the tightened validator still accepts them.
- If the tightened validator rejects a shape the application really caches, stop and report the
  type. Do not restore `allowIfBaseType(Object.class)`.

### Validation

```bash
./gradlew clean spotlessApply
./gradlew test --tests '*ExchangeRateImportCacheIntegrationTest' \
  --tests '*ExchangeRateImportServiceIntegrationTest' \
  --tests '*ExchangeRateServiceIntegrationTest' \
  --tests '*ExchangeRateControllerIntegrationTest'
```

Also confirm that `rg -n 'allowIfBaseType|List\.of\(\)' src/main/java` shows no `allowIfBaseType`
and no `List.of()` returned from a cacheable path.

### Completion criteria

- The empty-result test failed before the fix and passes after it.
- All focused tests pass.
- `CacheConfig` has a single cache configuration that carries the `currency-service:` prefix, an
  effective allowlist, and accurate Javadoc.
- `application.yml` no longer contains `spring.cache.redis`.

## Phase 2: Deterministic Coverage of the Import Catch-All

### Workspace

.

### Goal

Exercise the generic `catch (Exception e)` wrapper in
`ExchangeRateImportService.importExchangeRates(CurrencySeries, LocalDate)` with a synchronous test
whose result does not depend on background messaging timing.

### Scope

- `src/test/java/org/budgetanalyzer/currency/service/ExchangeRateImportServiceIntegrationTest.java`
- `src/test/java/org/budgetanalyzer/currency/fixture/` (only if a new stub helper or constant is
  needed)

### Non-goals

- Do not change production code.
- Do not change the test messaging topology, consumer groups, or context-caching settings.
- Do not rewrite, rename, or delete the existing `transactionRollbackOnRepositorySaveFailure`
  test.

### Required context

- `AGENTS.md`
- `../service-common/docs/testing-patterns.md`: "Testing External HTTP Boundaries" and "Keep
  Application-Owned Spring Beans Real"
- `src/main/java/org/budgetanalyzer/currency/service/ExchangeRateImportService.java`
- `src/main/java/org/budgetanalyzer/currency/service/provider/FredExchangeRateProvider.java`
- `src/test/java/org/budgetanalyzer/currency/fixture/FredApiStubs.java`

### Execution steps

1. Confirm the trigger. `FredExchangeRateProvider.getExchangeRates` collects observations with
   `Collectors.toMap` and no merge function. A FRED response with two valued observations on the
   same date therefore throws `IllegalStateException`, which is not a `ServiceException`. It runs
   inside the catch-all's `try` block.
2. In the "Edge Cases & Error Scenarios" section of `ExchangeRateImportServiceIntegrationTest`,
   add a test named after the repository's existing convention, for example
   `importWrapsUnexpectedProviderFailureInServiceException`.
   - **Arrange:** save the default EUR series. Call `FredApiStubs.stubSuccessWithObservations`
     with two observations that share `TestConstants.DATE_2024_JAN_01` and have different values.
     Record the exchange-rate row count.
   - **Act:** call `exchangeRateImportService.importExchangeRatesForSeries(eurSeries.getId())`.
   - **Assert:**
     - the thrown exception is exactly `ServiceException` (`isExactlyInstanceOf`, which excludes
       `ClientException`);
     - its message starts with `Failed to import exchange rates for EUR: `;
     - its cause is an `IllegalStateException`;
     - the exchange-rate row count is unchanged.
3. Update the section's test-count comment if it enumerates tests.
4. Run the class three times and confirm the new test passes every time:

   ```bash
   ./gradlew test --tests '*ExchangeRateImportServiceIntegrationTest' --rerun-tasks
   ```
5. Run `./gradlew test jacocoTestReport --tests '*ExchangeRateImportServiceIntegrationTest'`.
   In `build/reports/jacoco/test/jacocoTestReport.xml`, confirm that every line of the catch-all
   block has `mi="0"`. The block is the `throw new ServiceException("Failed to import exchange
   rates for " ...)` statement. Its line numbers are about 304–309 unless earlier phases shifted
   them.

### Implementation notes

- Treat duplicate observation dates as malformed provider data. Failing the import, rolling back,
  and wrapping the error is the current contract, so assert it. If you conclude that the provider
  should merge duplicates instead, stop and report. Do not change provider behavior in this plan.
- The test runs on the test thread through the real service, provider, client, and WireMock. It
  does not depend on asynchronous consumers.

### Validation

```bash
./gradlew clean spotlessApply
./gradlew test jacocoTestReport --tests '*ExchangeRateImportServiceIntegrationTest'
```

### Completion criteria

- The new test passes on three consecutive runs.
- JaCoCo reports the catch-all lines as covered by this class alone.
- No production code changed.

## Phase 3: Build, Runtime Configuration, and Migration Rollback Cleanup

### Workspace

.

### Goal

Remove duplicate dependencies, unused catalog entries, and configuration keys that no longer bind,
leaving effective runtime and test behavior unchanged. Add the missing `V8` rollback script.

### Scope

- `gradle/libs.versions.toml`
- `build.gradle.kts`
- `src/main/resources/application.yml`
- `src/test/resources/application.yml`
- `src/test/resources/testcontainers.properties` (delete)
- `src/main/resources/db/migration/U8__rollback_modulith_2_event_publication_columns.sql` (new)

### Non-goals

- Do not remove `testcontainers` (the base module). `TestContainersConfig` uses
  `org.testcontainers.containers.GenericContainer` directly.
- Do not change dependency versions, and do not remove `mavenLocal()`.
- Do not remove keys that do bind, even when they restate defaults.
- Do not set explicit consumer retry properties. The effective defaults already match the intended
  policy, and Phase 6 documents them.
- Do not add `spring.modulith.events.completion-mode`. The default (`update`) is the intended
  permanent retention.
- Do not edit any `V*` migration or any existing `U*` script.

### Required context

- `AGENTS.md`
- `docs/dependency-automation.md`: confirm that no Renovate rule references the removed aliases
- `build.gradle.kts` and `gradle/libs.versions.toml`
- Both `application.yml` files
- `src/main/resources/db/migration/V5__add_event_publication_table.sql`,
  `V8__add_modulith_2_event_publication_columns.sql`, and the existing `U3`–`U5` and `U7` scripts,
  for the header style
- The "Database Migrations" section of
  [service-common advanced patterns](../../../service-common/docs/advanced-patterns.md)

### Execution steps

1. In `gradle/libs.versions.toml`, remove these libraries:
   - `spring-security-test`, which `spring-boot-starter-security-test` already provides through
     `spring-boot-security-test`;
   - `service-core`, which is not referenced;
   - `spring-boot-starter-actuator`, which is not referenced (actuator arrives through
     `service-web`);
   - `h2`, which no test uses.

   Then remove any version entry that is no longer referenced.
2. In `build.gradle.kts`, remove `testImplementation(libs.spring.security.test)` and
   `testRuntimeOnly(libs.h2)`.
3. In `src/main/resources/application.yml`, remove:
   - `spring.jackson.datatype.datetime.write-dates-as-timestamps` (false is Jackson 3's
     default), along with the now-empty `datatype.datetime` parents;
   - `spring.jackson.date-format` (no `java.util.Date` is used in this repository or in
     service-common);
   - `spring.data.redis.lettuce.pool.*`, along with the now-empty `lettuce` parent;
   - `spring.modulith.events.delete-completion-after` and its "Retain completed events for audit
     trail (30 days)" comment. Keep `republish-outstanding-events-on-restart: true` and its
     comment;
   - `max-attempts`, `initial-interval`, `max-interval`, and `multiplier` under
     `spring.cloud.stream.rabbit.bindings.importExchangeRates-in-0.consumer` and their comments.

   Keep `auto-bind-dlq` and `republish-to-dlq`.
4. In `src/test/resources/application.yml`, remove:
   - `max-attempts`, `back-off-initial-interval`, `back-off-multiplier`, and
     `back-off-max-interval` directly under
     `spring.cloud.stream.bindings.importExchangeRates-in-0`;
   - `max-attempts`, `initial-interval`, `max-interval`, and `multiplier` under the Rabbit
     consumer binding;
   - any comments describing them.

   Keep the remaining Rabbit consumer keys.
5. Delete `src/test/resources/testcontainers.properties`.
6. Create `src/main/resources/db/migration/U8__rollback_modulith_2_event_publication_columns.sql`.
   - Use the same header as `U3`/`U4`: which migration it reverses, the documentation-only warning
     (Flyway Community does not run undo migrations), and a "Rollback Impact" list.
   - Say in the header that the script is only valid together with a downgrade to Spring Modulith
     1.x (Spring Boot 3). Modulith 2 cannot load rows without `completion_attempts`.
   - Say what is lost: `status`, `completion_attempts`, and `last_resubmission_date` for every row.
     No publication rows are deleted. Modulith 1 still tells completed rows from incomplete ones by
     `completion_date`, so the audit trail stays intact.
   - In one transaction (`BEGIN;` … `COMMIT;`):
     - `ALTER TABLE event_publication DROP COLUMN IF EXISTS` for the three V8 columns;
     - `DELETE FROM flyway_schema_history WHERE version = '8';`, so that a later upgrade re-applies
       `V8` instead of skipping it. Explain this in a comment.
7. Confirm the cleanup:
   - `./gradlew dependencies --configuration testRuntimeClasspath | rg 'spring-security-test'`
     still shows `spring-security-test`, provided transitively.
   - `rg -n 'lettuce|delete-completion-after|date-format|write-dates-as-timestamps|initial-interval|back-off'
     src/main/resources src/test/resources` returns nothing.
   - `rg -n 'modulith' -A4 src/main/resources/application.yml` shows only
     `republish-outstanding-events-on-restart: true` under `spring.modulith.events`.
8. Verify `U8` against a throwaway Postgres container using the image the tests use. Check that
   `V1`–`V8` followed by `U8` leaves `event_publication` with the same schema as `V1`–`V7`, and
   that the history row is gone:

   ```bash
   set -euo pipefail
   name="u8-check-$$"
   docker run -d --rm --name "$name" -e POSTGRES_PASSWORD=check postgres:15-alpine >/dev/null
   trap 'docker stop "$name" >/dev/null' EXIT
   until docker exec "$name" pg_isready -U postgres -q; do sleep 1; done
   sql() { docker exec -i "$name" psql -U postgres -v ON_ERROR_STOP=1 -q "$@"; }
   dump() {
     docker exec "$name" pg_dump -U postgres --schema-only -t event_publication postgres \
       | sed -E '/^\\(un)?restrict /d'
   }
   sql -c "CREATE TABLE flyway_schema_history (version VARCHAR(50));"
   for version in 1 2 3 4 5 6 7; do
     sql < src/main/resources/db/migration/V${version}__*.sql
   done
   dump > build/u8-before.sql
   sql < src/main/resources/db/migration/V8__*.sql
   sql -c "INSERT INTO flyway_schema_history VALUES ('8');"
   sql < src/main/resources/db/migration/U8__*.sql
   dump > build/u8-after.sql
   diff build/u8-before.sql build/u8-after.sql
   test "$(sql -tAc "SELECT count(*) FROM flyway_schema_history WHERE version = '8';")" = 0
   ```

   The `diff` must print nothing and the script must exit 0. The `sed` filter drops the random
   `\restrict` token that recent `pg_dump` versions write into every dump. Run `mkdir -p build` first if
   `clean` removed it. The `flyway_schema_history` stub only exists so the `DELETE` has a table
   to act on.

### Implementation notes

- Spring Boot binds `BindingProperties` and `RabbitConsumerProperties` with unknown fields
  ignored. The deleted keys never bound, so effective retry behavior stays at the
  `ConsumerProperties` defaults: 3 attempts, 1000 ms initial back-off, 2.0 multiplier,
  10000 ms maximum.
- Controller tests that assert ISO date strings act as the regression check for removing
  `write-dates-as-timestamps`.
- Flyway's default `sqlMigrationPrefix` is `V`, so it ignores the new `U8` file just as it ignores
  `U1`–`U7`. The build's Flyway-backed integration tests confirm that.
- If a `V*` script cannot be applied in plain `psql` during the `U8` check (for example because of
  Flyway placeholders), stop and report it. Do not edit the migration.

### Validation

```bash
./gradlew clean spotlessApply
./gradlew clean build --warning-mode all
```

The build must pass with no Gradle deprecation warnings.

### Completion criteria

- The full build passes.
- The removed catalog aliases, dependency lines, configuration keys, and the
  `testcontainers.properties` file are gone.
- `U8` exists, follows the `U*` header convention, and passes the container check.
- `spring-security-test` is still on the test runtime classpath through the starter.

## Phase 4: Deprecated API Replacement and Stale Code Comments

### Workspace

.

### Goal

Remove every compiler deprecation and removal warning by switching to the non-deprecated
equivalent. Correct Javadoc and comments in Java code that describe pre-migration versions or wrong
Spring Modulith semantics.

### Scope

- `src/main/java/org/budgetanalyzer/currency/config/SchedulingConfig.java`
- `src/test/java/org/budgetanalyzer/currency/api/CurrencySeriesControllerIntegrationTest.java`
- `src/test/java/org/budgetanalyzer/currency/api/ExchangeRateControllerIntegrationTest.java`
- `src/main/java/org/budgetanalyzer/currency/config/CurrencyServiceConfig.java` (Javadoc)
- `src/test/java/org/budgetanalyzer/currency/service/CurrencyServiceIntegrationTest.java` (Javadoc)
- `src/test/java/org/budgetanalyzer/currency/config/TestContainersConfig.java` (Javadoc)
- `src/main/java/org/budgetanalyzer/currency/messaging/listener/MessagingEventListener.java`
  (Javadoc)
- `src/main/java/org/budgetanalyzer/currency/domain/event/CurrencyCreatedEvent.java` (Javadoc)

### Non-goals

- Do not edit Flyway migrations, including the V5 comment.
- Do not change listener, event, or scheduling behavior.
- Do not change the asserted HTTP statuses.

### Required context

- `AGENTS.md`
- `../service-common/docs/code-quality-standards.md`
- The Spring Modulith 2.1.2 sources or reference documentation for event publication completion,
  failure, and resubmission. The `spring-modulith-events-core` and `spring-modulith-events-jpa`
  jars are in the Gradle cache. Verify behavior there before writing it into Javadoc.

### Execution steps

1. In `SchedulingConfig`, replace `import org.springframework.lang.NonNull;` with
   `import org.jspecify.annotations.NonNull;` and keep both annotation sites. Fix import order with
   Spotless.
2. Replace all seven `status().isUnprocessableEntity()` calls with
   `status().isUnprocessableContent()`. Both assert HTTP 422.
3. Rewrite the `CurrencyServiceConfig` Javadoc note. Boot auto-configures `JsonMapper` (Jackson 3)
   from `spring.jackson.*`, and Jackson 3 supports `java.time` without
   `jackson-datatype-jsr310`. Verify the CSV-parsing remark against service-common and remove it
   if it is no longer true.
4. Update the version wording in the `CurrencyServiceIntegrationTest` Javadoc ("Spring Boot 3.5.7",
   "Spring Boot 3.1+") and the `TestContainersConfig` Javadoc ("Spring Boot 3.1+"). Describe the
   patterns without version claims, or name Spring Boot 4.
5. Correct the event-delivery Javadoc in `MessagingEventListener` and `CurrencyCreatedEvent`.
   - Remove claims that Spring Modulith "polls" the `event_publication` table, "automatically
     retries", provides "exactly-once" or "100% guaranteed" delivery, or retries "according to its
     retry policy".
   - Describe the verified behavior:
     - the publication is persisted in the business transaction;
     - `@ApplicationModuleListener` runs asynchronously after commit;
     - a successful listener completes the publication. The default `completion-mode: update`
       keeps the row permanently, and that permanent audit trail is intentional;
     - a failed listener leaves it incomplete or failed, as Modulith 2.1.2 defines;
     - `republish-outstanding-events-on-restart: true` resubmits outstanding publications at
       startup;
     - delivery to RabbitMQ is at least once, so consumers must tolerate duplicates.
   - In the `MessagingEventListener` configuration snippet, remove the `delete-completion-after`
     line. Keep `republish-outstanding-events-on-restart: true`.
   - Remove the "replay" claims ("allowing audit trail and replay", "Event Replay") unless the
     Modulith 2.1.2 API supports resubmitting completed publications. `IncompleteEventPublications`
     resubmits only incomplete ones. Keep the audit-trail wording.
6. Check for deprecation and removal warnings by placing a temporary Gradle init script under
   `build/`, which is ignored and removed by `clean`:

   ```bash
   mkdir -p build
   cat > build/lint.init.gradle <<'EOF'
   allprojects {
     tasks.withType(JavaCompile).configureEach {
       options.compilerArgs += ['-Xlint:deprecation', '-Xlint:removal']
     }
   }
   EOF
   ./gradlew compileJava compileTestJava --rerun-tasks --init-script build/lint.init.gradle 2>&1 \
     | rg 'warning: \[(deprecation|removal)\]'
   ```

   The `rg` output must be empty.

### Implementation notes

- `org.jspecify:jspecify` is on the compile classpath through Spring Framework 7. If the compiler
  cannot resolve it, stop and report. Do not add a dependency without checking the platform BOM.

### Validation

```bash
./gradlew clean spotlessApply
./gradlew test --tests '*CurrencySeriesControllerIntegrationTest' \
  --tests '*ExchangeRateControllerIntegrationTest' \
  --tests '*ExchangeRateImportSchedulerIntegrationTest'
```

Then run the lint check from step 6 and confirm it reports no warnings, and confirm that
`rg -n -i 'spring boot 3|objectmapper|jsr310|polls|retries automatically|exactly-once|delete-completion-after|30d' src/main/java src/test/java`
has no stale hits.

### Completion criteria

- Javac reports zero deprecation and removal warnings for main and test code.
- The focused tests pass.
- The Java Javadoc matches Spring Boot 4, Jackson 3, and the verified Spring Modulith 2.1.2
  semantics, including permanent retention of completed publications.

## Phase 5: API Reference and Domain Model Documentation

### Workspace

.

### Goal

Rewrite `docs/api/README.md` and `docs/domain-model.md` so that every endpoint, field, rule, event,
and command matches the code.

### Scope

- `docs/api/README.md`
- `docs/domain-model.md`

### Non-goals

- Do not change code.
- Do not document endpoints, events, caches, or scheduled jobs that do not exist.
- Do not copy orchestration-owned gateway details beyond a stable summary and a link.

### Required context

- Controllers, request and response records, and `@PreAuthorize` rules in
  `src/main/java/org/budgetanalyzer/currency/api/`
- Entities in `src/main/java/org/budgetanalyzer/currency/domain/`, including their audit base
  class, unique constraints, and foreign keys (see the Flyway `V1`–`V8` migrations)
- Domain events in `domain/event/` and the external message in `messaging/message/`
- Services in `service/`, the scheduler in `scheduler/`, and `CacheConfig`
- [service-common error handling](../../../service-common/docs/error-handling.md) for the error
  response shape and status mapping
- `../orchestration/docs/architecture/resource-routing-pattern.md` and
  `../orchestration/docs/development/getting-started.md` for gateway URLs
- The runtime OpenAPI document. `OpenApiContractIntegrationTest` loads `/v3/api-docs`; use it, or
  read the controller annotations, as the generated contract.

### Execution steps

1. Rewrite `docs/api/README.md` around the real routes:
   - `GET /v1/currencies` (with its `enabledOnly`-style query parameter, as named in the
     controller)
   - `POST /v1/currencies`
   - `GET /v1/currencies/{id}`
   - `PUT /v1/currencies/{id}`
   - `GET /v1/exchange-rates` (`targetCurrency` required; `startDate` and `endDate` optional, ISO
     dates)
   - `POST /v1/exchange-rates/import`

   For each route, state the required permission (`currencies:read` or `currencies:write`),
   the request and response fields from the records, and the success and error statuses. Then:
   - **Paths.** Direct paths include the `/currency-service` context path. Gateway paths are
     `https://app.budgetanalyzer.localhost/api/v1/...`; link to the orchestration document.
   - **Validation rules.** Take them from the request records. State that `enabled` is required
     on create and that a missing or null value returns 400. Confirm the exact status and error
     type from service-common's handler or an existing test.
   - **Caching.** Only `GET /v1/exchange-rates` is cached. There is no TTL. Entries are evicted
     after a successful import commit. Remove any `Cache-Control` claims unless the code sets
     those headers.
   - **Remove fictional content:** the DELETE and PATCH `/active` endpoints, `/latest/{seriesId}`,
     the admin endpoints, the `seriesCode`, `sourceCurrency`, and `active` fields, the per-endpoint
     TTLs, and the rate-limiting text. Keep a rate-limiting section only if gateway documentation
     supports it, and then as a link.
   - **Daily import.** Use the real schedule and ShedLock lock settings.
   - **Discovery commands.** Make sure each one runs from the repository root.
2. Rewrite `docs/domain-model.md` from the code:
   - **`CurrencySeries`:** `Long` id, `currencyCode`, `providerSeriesId`, `enabled`, and the
     audit fields, with their uniqueness rules.
   - **`ExchangeRate`:** base and target currencies, date, rate precision and scale, the
     `currencySeries` foreign key, any other mapped fields, and the
     `(base_currency, target_currency, date)` unique constraint.
   - **The real relationship.** There is no delete cascade, and deleting a series that has rates
     violates `fk_exchange_rate_currency_series`.
   - **The real services:** `CurrencyService`, `ExchangeRateService`, and
     `ExchangeRateImportService`.
   - **The real events:** `CurrencyCreatedEvent` and `CurrencyUpdatedEvent`, leading to
     `ExchangeRateImportRequestedMessage`.
   - **Caching:** the real cache key `currency-service:exchangeRates::{currencyCode}:{startDate}:{endDate}`,
     with no TTL.
   - **The daily import:** 23:00 UTC with ShedLock `lockAtMostFor = 15m` and
     `lockAtLeastFor = 1m`.

   Remove the fictional value objects, events, cache warmup job, 06:00 UTC schedule, and the
   `database-schema.md` reference. Fix the `fred-integration.md` reference, which is not "future".
3. Replace `@service-common/...` pseudo-links with real links. Cross-repository links in `docs/`
   follow the GitHub URL convention already used in `docs/local-development.md`.
4. Run the link check in Validation and fix any broken links.

### Implementation notes

- Prefer short tables and links to the runtime OpenAPI document over field-by-field duplication
  where the generated contract is authoritative.
- Mark any claim you cannot verify from this repository, service-common, or orchestration docs as
  unverified, or leave it out.

### Validation

```bash
python3 - <<'EOF'
import pathlib, re
for md in [pathlib.Path('docs/api/README.md'), pathlib.Path('docs/domain-model.md')]:
    for target in re.findall(r'\]\(([^)#\s]+)(?:#[^)]*)?\)', md.read_text()):
        if '://' not in target and not (md.parent / target).exists():
            print(f'{md}: missing {target}')
EOF
rg -n 'seriesCode|sourceCurrency|/admin/|/latest|PATCH |DELETE |CurrencyImportedEvent|ExchangeRatesImported|6:00 AM|max-age' \
  docs/api/README.md docs/domain-model.md
```

Both commands must print nothing.

### Completion criteria

- Both documents describe only routes, fields, rules, events, caches, and schedules that exist in
  code.
- The `enabled`-required behavior is documented.
- All relative links resolve, and every documented command is plausible from the repository root.

## Phase 6: Configuration, Patterns, Integration, Local Development, and README Documentation

### Workspace

.

### Goal

Correct the remaining human documentation so that configuration, caching, messaging, scheduling,
FRED import behavior, local URLs, and the README match the cleaned-up repository.

### Scope

- `docs/configuration.md`
- `docs/advanced-patterns-usage.md`
- `docs/fred-integration.md`
- `docs/local-development.md`, except the coverage baseline numbers, which Phase 7 updates
- `README.md`

### Non-goals

- Do not change code or configuration.
- Do not edit `docs/dependency-automation.md` unless an alias it names was removed in Phase 3.
- Do not change the retention decision or add cleanup instructions for event publications.

### Required context

- `src/main/resources/application.yml` (after Phase 3) and `CacheConfig` (after Phase 1)
- `ExchangeRateImportScheduler`, `ExchangeRateImportService`, `FredClient`, and
  `FredExchangeRateProvider`
- `MessagingEventListener`, `ExchangeRateImportConsumer`, and the Phase 4 Javadoc
- `../orchestration/docs/development/getting-started.md` and
  `../orchestration/docs/architecture/resource-routing-pattern.md`
- [service-common advanced patterns](../../../service-common/docs/advanced-patterns.md)

### Execution steps

1. Update `docs/configuration.md`:
   - **Infrastructure table and environment variables.** List every `${...}` placeholder in
     `application.yml` with its real default. This includes the username variables, the legacy
     `REDIS_*` and `RABBITMQ_*` fallbacks, `SPRING_RABBITMQ_SSL_VALIDATE_SERVER_CERTIFICATE`,
     and `SPRING_RABBITMQ_SSL_VERIFY_HOSTNAME`. Redis and RabbitMQ SSL default to `false`, and the
     SSL bundles default to empty. Note that the local run in `docs/local-development.md` sets TLS
     on with the `infra-ca` bundle.
   - **Required versus optional.** `FRED_API_KEY` has no default, so startup fails without it.
     Passwords default to empty but are required by the platform infrastructure.
   - **Caching:**
     - Redis, cache-aside on `GET /v1/exchange-rates` only;
     - no TTL;
     - all entries evicted after a successful import commit (`transactionAware`);
     - synchronous writes (`immediateWrites`);
     - key namespace `currency-service:`;
     - typed JSON values with a package allowlist;
     - cached values must not be JDK immutable collections.

     Remove the 1h, 30m, and 6h TTLs, the hit-rate figure, and the admin endpoint.
   - **Scheduled tasks:** the actual cron (`0 0 23 * * ?`, UTC), the lock settings, the in-process
     retry from `currency-service.exchange-rate-import.retry.*`, and "enabled" rather than
     "active" series.
   - **Messaging:**
     - consumer retry uses the Spring Cloud Stream defaults (3 attempts, 1 s initial back-off,
       ×2, 10 s maximum), then republishes to the DLQ (`auto-bind-dlq`, `republish-to-dlq`);
     - Spring Modulith completes publications in the default `update` mode and keeps every
       completed publication permanently as an audit trail. This is deliberate: there is no
       time-based cleanup, and `event_publication` grows with each currency create, update, and
       import request;
     - outstanding publications are resubmitted on restart;
     - V8 adds the Modulith 2 `status`, `completion_attempts`, and `last_resubmission_date`
       columns. `U8` documents the manual rollback and is valid only together with a Modulith 1
       downgrade.
2. Update `docs/advanced-patterns-usage.md`:
   - Replace the fictional `CurrencyServiceImpl` examples with excerpts from the real classes.
   - Fix the discovery commands. Every glob and `rg` or `grep` command must match real files, and
     ShedLock lookups must target `src/main/java`, not `application.yml`.
   - Use database `currency` in the `psql` examples.
   - Replace the TTL claims and the `time-to-live: 3600000` example with the actual `CacheConfig`
     shape.
   - Correct the Spring Modulith "polls" and "retries automatically" text to match the Phase 4
     semantics, and state that completed publications are kept permanently as an audit trail.
   - Document the immutable-collection caching rule and the synchronous cache writer.
   - Check the testing section against the real test classes.
3. Update `docs/fred-integration.md`:
   - **Import behavior.** An import starts the day after the latest stored rate, or fetches full
     history when no rate is stored. Existing dates are skipped or updated as
     `saveExchangeRates` actually does; describe that from the code. The "last 30 days" claim is
     wrong.
   - **Rate limits.** Describe what the code does: FRED errors, including 429, become
     `ClientException`, and the scheduler applies its configured retry. Do not claim client-side
     rate-limit handling unless it exists.
   - **Provider diagram.** Show both `CurrencyService` (series validation) and
     `ExchangeRateImportService` depending on `ExchangeRateProvider`.
4. Update `docs/local-development.md`:
   - **Direct URLs** include the `/currency-service` context path: Swagger UI, `/v3/api-docs`,
     and `/actuator/health`. Confirm against service-common's security configuration that these
     paths are reachable as documented.
   - **Gateway URLs:** `https://app.budgetanalyzer.localhost/api/v1/...` and the unified
     `https://app.budgetanalyzer.localhost/api-docs`, linked to orchestration instead of copied.
   - Keep the coverage baseline sentence as is; Phase 7 updates its numbers.
5. Update `README.md`:
   - Fix the Swagger UI URL (it needs the context path).
   - Change "CRUD" to create, read, and update.
   - Remove the unsupported "80-95% hit rate" figure.
   - Keep the technology stack consistent with the build; actuator arrives through service-web.
6. Run the link and stale-claim checks in Validation.

### Implementation notes

- Keep detailed recurring facts in one owner document. `docs/configuration.md` owns property and
  environment-variable facts; the other documents link to it instead of repeating values.
- `AGENTS.md` needs an update only if a discovery command it lists is now wrong. If so, read
  `../orchestration/docs/agents-md-checkstyle.md` first.

### Validation

```bash
python3 - <<'EOF'
import pathlib, re
for md in [pathlib.Path('README.md'), *sorted(pathlib.Path('docs').rglob('*.md'))]:
    for target in re.findall(r'\]\(([^)#\s]+)(?:#[^)]*)?\)', md.read_text()):
        if '://' not in target and not (md.parent / target).exists():
            print(f'{md}: missing {target}')
EOF
rg -n -i 'CurrencyServiceImpl|budget_analyzer -c|3600000|1-hour|1 hour|30 minutes|6 hours|80-95|admin endpoint|polls|retries automatically|delete-completion-after|last 30 days|localhost:8084/swagger|localhost:8084/v3|localhost:8084/actuator' \
  README.md docs --glob '!docs/plans/**'
```

Both commands must print nothing. The `local-development.md` note "uses the `currency` database
(not `budget_analyzer`)" is allowed.

### Completion criteria

- All five documents match the code and configuration.
- Every relative link resolves.
- The stale-claim check is clean.

## Phase 7: Full Validation and Coverage Baseline

### Workspace

.

### Goal

Run the repository's required gates on the combined result, record the new coverage baseline, and
confirm that the code and documentation are consistent.

### Scope

- `docs/local-development.md` (coverage baseline sentence only)
- Fixes to defects that the full gates expose in work from Phases 1–6

### Non-goals

- Do not start new cleanup, refactoring, or documentation rewrites beyond fixing gate failures.

### Required context

- `AGENTS.md` (Validation and Documentation Discipline)
- The completion notes from Phases 1–6

### Execution steps

1. Run the required gates in sequence:

   ```bash
   ./gradlew clean spotlessApply
   ./gradlew clean build --warning-mode all
   ```
2. Run the lint check from Phase 4 step 6 again and confirm it prints no warnings.
3. Compute the coverage totals:

   ```bash
   python3 - <<'EOF'
   import xml.etree.ElementTree as ET
   root = ET.parse('build/reports/jacoco/test/jacocoTestReport.xml').getroot()
   for counter in root.findall('counter'):
       if counter.get('type') in ('LINE', 'BRANCH'):
           missed, covered = int(counter.get('missed')), int(counter.get('covered'))
           print(counter.get('type'), f'{100 * covered / (missed + covered):.2f}%')
   EOF
   ```

   Update the "recorded baseline" sentence in `docs/local-development.md` to these values.
4. Confirm that the catch-all lines in `ExchangeRateImportService` are covered in the full-suite
   JaCoCo report.
5. Run the Phase 6 link and stale-claim checks across `README.md` and `docs/`, excluding
   `docs/plans/`.
6. Confirm that `git status --short` lists only files that this plan scopes. This is a read-only
   Git command.

### Implementation notes

- If a gate fails because of an earlier phase, fix it here, keeping within that phase's scope.
  Report any failure that would require leaving that scope.
- Report any verifier that cannot run, such as missing Docker or Maven Local artifacts. Do not
  claim full verification in that case.

### Validation

- `./gradlew clean build --warning-mode all` passes with no Gradle deprecation warnings.
- Javac reports zero deprecation and removal warnings.
- Coverage meets the configured gates (90% line, 85% branch).
- The link and stale-claim checks print nothing.

### Completion criteria

- All gates pass on the combined changes.
- `docs/local-development.md` records the measured baseline.
- No document in `README.md` or `docs/` contradicts the code, the build, or the configuration.
