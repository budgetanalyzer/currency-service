# Advanced Patterns Best-Practice Fixes

Apply three findings from the Spring Boot 4 advanced-patterns review, then bring every affected
document and Javadoc in line:

1. Remove `spring.modulith.events.republish-outstanding-events-on-restart`. Spring Modulith's
   property reference says it is "usually not recommended in multi-instance deployments as other
   instances might still be processing events". The 2.1.x startup path resubmits every incomplete
   publication, including ones another pod is processing, and one publication that cannot be
   deserialized crash-loops startup (#1904, open). Upstream issue #1916 (milestone 2.2.0-RC1)
   proposes deprecating it in favor of application-written resubmission code. Spring Modulith
   provides no automatic retry of failed publications in 2.1 or 2.2. In this service the flag is
   also redundant: `CurrencyServiceStartupConfig` runs
   `importLatestExchangeRates()` for every enabled series at startup, and
   `ExchangeRateImportScheduler` does the same daily at 23:00 UTC. Each import starts after the
   latest stored rate, or loads full history when none is stored. That reconciliation already
   covers every import request a resubmitted publication would send.
2. Configure ShedLock's `JdbcTemplateLockProvider` with `usingDbTime()`. The ShedLock README
   strongly recommends it: lock times come from the PostgreSQL clock in UTC instead of each pod's
   clock.
3. Give the `exchangeRates` Redis cache a one-hour TTL. Without a TTL, a cache-aside read whose
   query started before an import committed can write its stale result after that import's
   after-commit eviction, and the stale entry stays until the next import (up to 24 hours). The
   TTL also bounds Redis memory for arbitrary date-range keys. Imports still evict all entries.

Phase 5 then updates the shared pattern guide in `../service-common/` so it matches Spring Boot 4,
Spring Modulith 2.x, Spring Data Redis 4, and ShedLock 7. The user has authorized that one
cross-repository phase.

Constraints for every phase:

- Phases 1–4 work only in this repository. Phase 5 works only in `../service-common/`, and only on
  the files it names. No phase modifies `../orchestration/`.
- Do not run Git write commands in any repository. Leave any existing uncommitted changes you did
  not make untouched.
- Integration tests need Docker for Testcontainers and the `org.budgetanalyzer` artifacts at
  `serviceCommon` `0.1.0-SNAPSHOT`. If either is unavailable, stop and report it. Do not work
  around it.
- Keep `immediateWrites()` and `transactionAware()` in `CacheConfig`. The orchestration Redis ACL
  for `currency-service` grants `+set +del +keys +scan` but not `+unlink`. Spring Data Redis's
  asynchronous `clear()` path issues `UNLINK`. The synchronous TTL write is a single `SET … PX`,
  which `+set` covers.
- Out of scope for currency-service code: `KEYS`→`SCAN` batch strategy, ShedLock Micrometer
  metrics (Phase 5 only documents them as an option), the staleness monitor, and any
  application-written resubmission code (a `CommandLineRunner`, a scheduled or ShedLock-guarded
  job, or other calls to `FailedEventPublications.resubmit(…)` or
  `IncompleteEventPublications`). Outbox recovery in this service is reconciliation by the startup
  and daily imports.
- The outbox send (stage 1: listener → RabbitMQ) and the consumer (stage 2: RabbitMQ → import) are
  separate stages. Spring Cloud Stream consumer retries (core defaults: 3 attempts, 1000 ms initial
  backoff, multiplier 2.0, 10000 ms maximum, then the DLQ) apply only to stage 2 and are unchanged
  by this plan. They never retry a failed stage-1 send.
- RabbitMQ publisher confirms and returns are not configured. A publication is marked `COMPLETED`
  when `StreamBridge.send` returns, which does not prove the broker accepted and routed the
  message. Document this as current behavior; do not implement confirms. The fix is tracked in
  `docs/issues/rabbitmq-publisher-confirms.md` for after this PR merges.

## Phase 1: Remove Restart Republication of Event Publications

### Workspace

.

### Goal

The service no longer sets `republish-outstanding-events-on-restart`. Every Javadoc and document
describes recovery as reconciliation by the startup and daily imports, not by outbox resubmission.

### Scope

- `src/main/resources/application.yml`: remove the `republish-outstanding-events-on-restart`
  entry and its comment. If `spring.modulith` is then empty, remove the whole `modulith:` block.
- `src/test/resources/application.yml`: remove the `republish-outstanding-events-on-restart:
  false` line, which now repeats the default. Keep `completion-mode: update`.
- Javadoc and comments in `MessagingEventListener`, `CurrencyCreatedEvent`,
  `CurrencyUpdatedEvent`, and `CurrencyService` that mention resubmission at startup,
  `republish-outstanding-events-on-restart`, or at-least-once delivery through resubmission.
  Include the `catch` block comments in `MessagingEventListener`.
- `docs/advanced-patterns-usage.md` (Event Flow, Delivery Guarantees, Publication Retention,
  Discovery Commands, Event-Driven Messaging Testing), `docs/configuration.md` (Transactional
  Outbox), and `docs/domain-model.md` (domain events publication lifecycle).

### Non-goals

- Do not change listener, publisher, consumer, or import behavior. Do not add publisher confirms;
  `docs/issues/rabbitmq-publisher-confirms.md` tracks that work.
- Do not change `completion-mode`, the V8/U8 migrations, or the `event_publication` schema.
- Do not add scheduled resubmission or staleness configuration.

### Required context

- `AGENTS.md` and `../service-common/docs/code-quality-standards.md`.
- `src/main/java/org/budgetanalyzer/currency/config/CurrencyServiceStartupConfig.java`,
  `src/main/java/org/budgetanalyzer/currency/scheduler/ExchangeRateImportScheduler.java`, and
  `ExchangeRateImportService.determineStartDate`. These show the reconciliation path.
- `docs/advanced-patterns-usage.md`, `docs/configuration.md`, `docs/domain-model.md`.

### Execution steps

1. Find every reference:
   `rg -n -i 'republish-outstanding|resubmi|at.least.once|next startup|no lost events|guarantee' src docs README.md`.
   Ignore `republish-to-dlq`, the V8/U8 migration column names, and cache or ShedLock wording
   (Phases 2–3 own those). Replace "No lost events" in `CurrencyService` with the accurate
   statement: the event row survives a crash after commit, but the resulting message can still be
   lost, and import reconciliation recovers it.
2. Remove the property from both `application.yml` files as scoped above.
3. Rewrite the affected Javadoc and documentation so they state these facts consistently:
   - Spring Modulith invokes the listener once per publication. A listener that throws leaves the
     publication `FAILED` and incomplete. Nothing resubmits it.
   - A crash after commit but before the listener completes leaves the publication `PUBLISHED` or
     `PROCESSING`. Nothing resubmits that either.
   - Recovery: the startup import (`import-on-startup`, default `true`) and the daily scheduled
     import both import every enabled series from the day after its latest stored rate, or full
     history when none is stored. A currency whose import request was lost catches up at the next
     startup or 23:00 UTC run. With `import-on-startup: false`, it waits for the scheduled run.
   - Incomplete publications stay in `event_publication` as a record of failed or interrupted
     sends. Query them with `status <> 'COMPLETED'` or `completion_date IS NULL`.
   - Overlapping import triggers are still possible: a message-driven import and a scheduled or
     startup import can target the same series. Keep the existing duplicate and concurrency
     guidance in `docs/domain-model.md`, and reword it as overlapping imports, not resubmitted
     messages.
   - Replace "at-least-once delivery" claims with the accurate statement: the outbox records the
     intent atomically and never sends for a rolled-back change. Delivery of a failed send relies
     on import reconciliation.
   - Spring Cloud Stream consumer retries and the DLQ cover only messages that reached RabbitMQ.
     They do not retry a failed outbox send. State this in the Delivery Guarantees section of
     `docs/advanced-patterns-usage.md`.
   - A send fails the listener only when `StreamBridge.send` throws, for example when the
     connection is unavailable. Publisher confirms and returns are not configured, so an
     unroutable message, an asynchronous broker refusal, or a broker failure before persistence
     still leaves the publication `COMPLETED` with no record of the loss. Import reconciliation
     recovers these the same way as a `FAILED` send. State this in `docs/configuration.md`
     (Transactional Outbox) and link to `docs/issues/rabbitmq-publisher-confirms.md`.
   - Add a short rationale for not using restart republication. Cite the Spring Modulith property
     reference (https://docs.spring.io/spring-modulith/reference/appendix.html), the startup
     crash-loop defect (https://github.com/spring-projects/spring-modulith/issues/1904), the
     planned deprecation (https://github.com/spring-projects/spring-modulith/issues/1916), and the
     reconciliation imports. State that the service intentionally adds no application-written
     resubmission code. Put it once in `docs/configuration.md` and link to it elsewhere.
4. Update the `^  modulith:` discovery command in `docs/advanced-patterns-usage.md` if the block
   was removed. Update the sentence about the test configuration turning off resubmission on
   restart.
5. Run `./gradlew spotlessApply` and fix anything it reports.

### Implementation notes

- The 2.1.x restart path calls `resubmitIncompletePublications(__ -> true)`. Do not describe
  resubmission as targeting only failed publications.
- Keep Javadoc first sentences ending with a period. Keep the Javadoc changes to text; change no
  code.

### Validation

- `./gradlew test --tests '*EventListenerIntegrationTest' --tests '*MessageConsumerIntegrationTest' --tests '*EndToEndMessagingFlowIntegrationTest' --tests '*CurrencyServiceIntegrationTest'`
- `rg -n -i 'republish-outstanding|resubmitted at the next|at.least.once' src docs` returns
  nothing, apart from the rationale text added in step 3.
- Every relative link added or changed in this phase resolves.

### Completion criteria

The property is absent from both `application.yml` files. The focused tests pass. No source
comment or document claims startup resubmission or at-least-once outbox delivery. The
reconciliation rationale is documented once and linked from the other docs.

## Phase 2: Use Database Time for ShedLock

### Workspace

.

### Goal

`ShedLockConfig` builds its `JdbcTemplateLockProvider` with `usingDbTime()`. An integration test
proves the configured provider locks the real scheduled task against the Flyway-managed
`shedlock` table in PostgreSQL.

### Scope

- `src/main/java/org/budgetanalyzer/currency/config/ShedLockConfig.java`.
- One new focused integration test under `src/test/java/org/budgetanalyzer/currency/scheduler/`.
- ShedLock wording in `docs/advanced-patterns-usage.md` and `docs/configuration.md`.

### Non-goals

- No change to lock names, `lockAtMostFor`, `lockAtLeastFor`, `defaultLockAtMostFor`, the cron,
  retry handling, or the `shedlock` table schema.
- No `shedlock-micrometer` dependency.
- Do not change `ExchangeRateImportSchedulerIntegrationTest`. It constructs the scheduler directly,
  without the ShedLock proxy, and that is intended.

### Required context

- `AGENTS.md`, `../service-common/docs/code-quality-standards.md`, and the Integration Tests,
  Testcontainers, and "What We Don't Test" sections of `../service-common/docs/testing-patterns.md`.
- `ShedLockConfig.java`, `ExchangeRateImportScheduler.java`,
  `ExchangeRateImportSchedulerIntegrationTest.java`, `AbstractWireMockTest.java`,
  `TestDatabaseHelper.java`, `FredApiStubs.java`, `TestConstants.java`.
- `src/main/resources/db/migration/V2__add_shedlock_table.sql`.
- ShedLock README, JdbcTemplateLockProvider section:
  https://github.com/lukas-krecan/ShedLock/blob/master/README.md

### Execution steps

1. Replace the provider bean with:
   `new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
   .withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build())`.
2. Update the `ShedLockConfig` Javadoc. Lock timestamps come from the PostgreSQL server clock in
   UTC, so pod clock skew does not affect lock expiry. The `locked_at` description should match
   that.
3. Add an integration test that uses the application's proxied `ExchangeRateImportScheduler` bean.
   Extend the existing WireMock and Testcontainers base, and stub FRED for one enabled series
   using the existing fixtures. Assert observable outcomes:
   - After `importDailyRates()` returns, the `shedlock` row `exchangeRateImport` exists. Its
     `locked_at` is within a few seconds of `now() AT TIME ZONE 'utc'` read from the same
     database, and `lock_until` is about one minute after `locked_at`, because `lockAtLeastFor` is
     `1m`.
   - A second immediate `importDailyRates()` call is skipped while the lock is held. Show this
     through an observable outcome, such as the WireMock request count for the series or the
     `exchange.rate.import.executions` counter staying unchanged.
   Clean the `shedlock` table through `TestDatabaseHelper` so other tests are unaffected.
4. Update the docs:
   - `docs/advanced-patterns-usage.md`: in "ShedLock Distributed Locking", say the provider uses
     database time. In Lock Duration Guidelines, replace the "pod clocks differ slightly"
     rationale for `lockAtLeastFor`: the database clock is now authoritative, so the remaining
     purpose is stopping other pods from re-running a quickly finished trigger. In "ShedLock
     Testing", describe the new test instead of saying only that the context starts.
   - `docs/configuration.md`, Scheduled Tasks: mention database time in the ShedLock bullet.

### Implementation notes

- The test checks the application's lock configuration (provider plus annotation) against the
  real schema, not ShedLock internals. Do not start multiple scheduler instances or count service
  calls. Keep application beans real; no Mockito bean overrides.
- Put reusable values, such as the lock name, in `TestConstants` if more than one test uses them.

### Validation

- `./gradlew test --tests '*ExchangeRateImportScheduler*'`
- Both `ExchangeRateImportSchedulerIntegrationTest` and the new test pass.

### Completion criteria

`ShedLockConfig` uses `usingDbTime()`. The new test passes against Testcontainers PostgreSQL.
`docs/advanced-patterns-usage.md` and `docs/configuration.md` describe database time and the new
test, and no longer cite pod clock skew as a reason for `lockAtLeastFor`.

## Phase 3: Add a One-Hour TTL to the Exchange Rates Cache

### Workspace

.

### Goal

`exchangeRates` entries expire one hour after they are written. Imports still evict all entries
after commit. Tests and documentation reflect the TTL and why it exists.

### Scope

- `src/main/java/org/budgetanalyzer/currency/config/CacheConfig.java`: add
  `public static final Duration EXCHANGE_RATES_TTL = Duration.ofHours(1);` and use it in
  `entryTtl(...)` in place of `Duration.ZERO`. Update the class and method Javadoc.
- `src/test/java/org/budgetanalyzer/currency/api/ExchangeRateImportCacheIntegrationTest.java`: add
  TTL coverage.
- Cache wording in `ExchangeRateService` and `ExchangeRateImportService` Javadoc,
  `docs/advanced-patterns-usage.md`, `docs/configuration.md`, `docs/domain-model.md`, and
  `docs/api/README.md`.

### Non-goals

- Do not make the TTL a configuration property. `CacheConfig` owns the only
  `RedisCacheConfiguration`, and `spring.cache.redis.time-to-live` stays ineffective.
- No change to keys, prefix, serializer, `immediateWrites()`, `transactionAware()`, the batch
  strategy, or the eviction annotations.
- No time-to-idle. It needs `GETEX`, which the Redis ACL does not grant.

### Required context

- `AGENTS.md`, `../service-common/docs/code-quality-standards.md`, and the Integration Tests and
  Redis Container sections of `../service-common/docs/testing-patterns.md`.
- `CacheConfig.java`, `ExchangeRateService.java`, `ExchangeRateImportService.java`,
  `ExchangeRateImportCacheIntegrationTest.java`.
- Caching sections of `docs/advanced-patterns-usage.md`, `docs/configuration.md`,
  `docs/domain-model.md`, `docs/api/README.md`.

### Execution steps

1. Add the constant and use it in `cacheConfiguration`. Update the `CacheConfig` Javadoc: replace
   the "No TTL" bullet with a one-hour TTL bullet that gives both reasons (bounding a stale entry
   from the read/import race, and bounding memory for date-range keys), and state that imports
   still evict everything after commit.
2. In `ExchangeRateImportCacheIntegrationTest`, add a test that populates the cache through
   `GET /v1/exchange-rates`. Read the stored key's expiry with `StringRedisTemplate.getExpire(key,
   TimeUnit.SECONDS)` and assert it is greater than 0 and at most
   `CacheConfig.EXCHANGE_RATES_TTL.toSeconds()`. Reuse the class's existing key-prefix constant
   and fixtures.
3. Update every "no TTL" statement:
   `rg -n -i 'no ttl|ttl:\*\* none|never expire|Duration.ZERO|entries do not expire|stay until' src docs`.
   Update the `CacheConfig` excerpt in `docs/advanced-patterns-usage.md` to match the code. Keep the
   detailed rationale in `docs/configuration.md#caching` and link to it from the other docs.
4. Check `ExchangeRateService` and `ExchangeRateImportService` Javadoc for claims that entries live
   until the next import, and correct them. Replace each "Eliminates any possibility of stale data"
   bullet in `ExchangeRateImportService` (`rg -n 'possibility of stale' src/main/java`): eviction
   after commit removes entries cached before the import, and the TTL bounds a stale entry
   re-cached by a read that raced the import.

### Implementation notes

- Spring Data Redis 4.1 writes a positive TTL with a single `SET … PX`, so the orchestration
  Redis ACL needs no change.
- Use `java.util.concurrent.TimeUnit`, not a magic number, in the expiry read.

### Validation

- `./gradlew test --tests '*ExchangeRateImportCacheIntegrationTest' --tests '*ExchangeRateImportServiceIntegrationTest' --tests '*ExchangeRateServiceIntegrationTest'`
  (skip any pattern that matches no class, and say which were skipped).
- The no-TTL `rg` from step 3 returns nothing stale.

### Completion criteria

`CacheConfig` applies a one-hour TTL through `EXCHANGE_RATES_TTL`. The new TTL test and the
existing cache and eviction tests pass. All caching docs and Javadoc describe the TTL
consistently, with the rationale owned by `docs/configuration.md`.

## Phase 4: Full Validation and Documentation Consistency

### Workspace

.

### Goal

The whole repository passes its required formatting and build gates. The documentation set
consistently reflects all three changes.

### Scope

Repository-wide formatting, the full build, and fixes to inconsistencies left by Phases 1–3. Do
not add new features.

### Non-goals

- No new behavior beyond Phases 1–3.
- No edits to sibling repositories.

### Required context

- `AGENTS.md` (Validation and Documentation Discipline).
- `README.md`, `docs/advanced-patterns-usage.md`, `docs/configuration.md`, `docs/domain-model.md`,
  `docs/api/README.md`, `docs/fred-integration.md`.

### Execution steps

1. Run `./gradlew clean spotlessApply`, then `./gradlew clean build`. Fix failures at their root
   cause without weakening or disabling tests. Coverage gates are 90% line and 85% branch.
2. Cross-check the docs for consistency:
   `rg -n -i 'republish-outstanding|at.least.once|no ttl|clocks? differ|usingDbTime|EXCHANGE_RATES_TTL|one.hour|publisher confirm|guaranteed delivery' docs src README.md`.
   No doc or Javadoc may claim that a `COMPLETED` publication was delivered to or confirmed by
   the broker. `docs/configuration.md` links to `docs/issues/rabbitmq-publisher-confirms.md`.
   Each fact should appear in its owner document and be linked, not contradicted, elsewhere.
3. Confirm that every relative link in the changed docs resolves. Check Markdown links whose
   target starts with `../`, `./`, or a bare filename, plus heading anchors. Confirm that every
   discovery command changed in `docs/advanced-patterns-usage.md` runs from the repository root.
4. Check whether `README.md` or `AGENTS.md` repeats any changed fact. Update it if so; otherwise
   leave it unchanged.

### Implementation notes

If the build fails because of Docker or `org.budgetanalyzer` artifact resolution, stop and report
which prerequisite is missing. Do not claim full verification.

### Validation

- `./gradlew clean spotlessApply` succeeds.
- `./gradlew clean build` succeeds, including tests, Checkstyle, Spotless check, and JaCoCo
  verification.
- The link and consistency checks in steps 2–3 find no problems.

### Completion criteria

`./gradlew clean build` passes. The documentation is internally consistent about restart
republication (removed, with the reconciliation rationale), ShedLock database time, and the
one-hour cache TTL. The final report lists the changed files and any verification that could not
run.

## Phase 5: Update the Shared Advanced Patterns Guide in service-common

### Workspace

../service-common

### Goal

`docs/advanced-patterns.md` and the outbox quick-reference line in `AGENTS.md` describe current
behavior and best practice for Spring Boot 4.1, Spring Modulith 2.1, Spring Data Redis 4.1,
ShedLock 7, and Spring Cloud Stream 5. No claim of exactly-once delivery, guaranteed delivery, or
automatic outbox retries remains.

### Scope

- `docs/advanced-patterns.md`: the Overview, the Event-Driven Messaging with Transactional Outbox,
  Redis Distributed Caching, Distributed Locking with ShedLock, and Database Migrations with Flyway
  (dependencies only) sections, and the Pattern Decision Matrix.
- `AGENTS.md`: only the "Transactional Outbox" quick-reference bullet, which currently claims
  "100% guaranteed delivery to RabbitMQ".

### Non-goals

- No Java, Gradle, or published-artifact changes, and no `publishToMavenLocal`.
- No edits to other service-common docs, or to the currency-service repository (Phases 1–4 own
  that).
- Do not restructure the guide or change the Provider Abstraction section, the Flyway workflow and
  temporal-column guidance, or Related Documentation.
- Do not recommend application-written resubmission code (a `CommandLineRunner`, a scheduled or
  ShedLock-guarded job, or other calls to the resubmission APIs), and do not present Spring
  Modulith 2.2 as adding automatic retries. It does not.

### Required context

- `AGENTS.md`, especially Documentation Discipline.
- `../orchestration/docs/agents-md-checkstyle.md`, read before editing `AGENTS.md`.
- `docs/advanced-patterns.md`.
- These sources, which back every factual statement in this phase:
  - Spring Modulith events reference: https://docs.spring.io/spring-modulith/reference/events.html
  - Spring Modulith appendix (property defaults and schema):
    https://docs.spring.io/spring-modulith/reference/appendix.html
  - Spring Modulith issues, open as of this plan: #1916 (deprecate restart republication,
    milestone 2.2.0-RC1), #1904 (an undeserializable publication crash-loops startup and stops a
    resubmission batch), and #1971 (concurrent resubmission can run a listener twice):
    https://github.com/spring-projects/spring-modulith/issues
  - Spring Modulith 2.1.2 release notes, which back-port the fixes for the minimum-age query
    (#1942), claiming completed publications (#1935), and zero staleness timeouts (#1918):
    https://github.com/spring-projects/spring-modulith/releases/tag/2.1.2
  - Spring Data Redis migration guide (asynchronous cache writes in 4.x):
    https://docs.spring.io/spring-data/redis/reference/upgrading.html
  - Spring Data Redis cache reference:
    https://docs.spring.io/spring-data/redis/reference/redis/redis-cache.html
  - ShedLock README: https://github.com/lukas-krecan/ShedLock/blob/master/README.md
  - Spring Cloud Stream binding properties:
    https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream/binding-properties.html
- For a concrete example, the currency-service files changed in Phases 1–3: `CacheConfig.java`,
  `ShedLockConfig.java`, `application.yml`, and `docs/configuration.md` in the plan repository
  (`../currency-service` relative to this workspace, or the plan repository path the runner
  reports). Read them only; do not edit them.

### Execution steps

1. **Overview and decision matrix.** Replace "guaranteed delivery" with accurate wording, for
   example "durable, atomic recording of the intent to send", in the Overview bullet and in the
   Transactional Outbox row of the Pattern Decision Matrix.
2. **Outbox architecture diagram.** Replace "Spring Modulith polls event_publication" with: after
   commit, Spring Modulith invokes the `@ApplicationModuleListener` asynchronously on the task
   executor, and marks the publication completed when the listener returns.
3. **Listener example Javadoc.** Remove "Automatically retries on failure". State that a listener
   that throws leaves the publication `FAILED` and incomplete, and nothing retries it
   automatically.
4. **Add a "Publication Lifecycle (Spring Modulith 2.x)" subsection** after Pattern Components.
   Cover:
   - The statuses `PUBLISHED`, `PROCESSING`, `COMPLETED`, `FAILED`, `RESUBMITTED`, and
     `completion_attempts`.
   - Completion modes `update` (default; the table grows, so purge or accept the growth
     deliberately), `delete`, and `archive`.
   - The staleness monitor (`spring.modulith.events.staleness.*`) is off by default. It only marks
     stuck publications `FAILED`; it never re-invokes a listener. Before 2.1.2, a zero per-state
     timeout was treated as immediately stale; 2.1.2 skips states without a timeout (#1918).
   - The resubmission APIs `FailedEventPublications.resubmit(ResubmissionOptions)` and
     `IncompleteEventPublications` exist, but only application code calls them. Spring Modulith
     does not retry failed publications automatically, and #1916 keeps resubmission in
     application code for 2.2. 2.1.2 fixed the minimum-age query (#1942) and the claiming of
     completed publications (#1935). Still open: an undeserializable publication stops a
     resubmission batch (#1904), and concurrent resubmissions can run a listener twice even under a
     distributed lock (#1971).
   - `COMPLETED` means the listener returned. For a RabbitMQ publisher, it proves the broker
     accepted and routed the message only when the publisher waits for a publisher confirm and
     checks for a return. The platform's outbox publishers do not configure confirms yet, so an
     unroutable message or a broker-side failure after `StreamBridge.send` returns is recorded as
     `COMPLETED`. State this as current behavior. Do not prescribe confirms in this phase.
5. **Recovery guidance.** Replace `republish-outstanding-events-on-restart: true` in the
   Application Configuration example. Explain:
   - The appendix says the property is "usually not recommended in multi-instance deployments as
     other instances might still be processing events".
   - In 1.4 and 2.1 alike, it resubmits every incomplete publication at startup, including ones
     another pod is processing. One undeserializable publication crash-loops startup (#1904).
     #1916 proposes deprecating it in 2.2 in favor of application-written resubmission code.
   - Recommended pattern: recover lost triggers by reconciliation from database state, and leave
     the property at its default `false`. Cite currency-service's startup and daily imports as the
     example.
   - A service that cannot reconcile has no automatic recovery for a failed outbox send. Before
     adopting the outbox, it must add a reconciliation path or document the gap as an accepted
     risk in its own repository. The guide offers no resubmission job.
6. **Consumer retry configuration.** In the same example, `max-attempts`, `initial-interval`, and
   `multiplier` sit under `spring.cloud.stream.rabbit.bindings.<binding>.consumer`. The Rabbit
   consumer properties have none of these fields (in Spring Cloud Stream 4.3 or 5.0), so the keys
   were ignored before and after the upgrade. Remove them. State that the core binder defaults apply:
   3 attempts, `back-off-initial-interval` 1000 ms, `back-off-multiplier` 2.0,
   `back-off-max-interval` 10000 ms. Overrides belong under
   `spring.cloud.stream.bindings.<binding>.consumer.*`. Keep `auto-bind-dlq` and
   `republish-to-dlq` under the Rabbit binding. State that these retries and the DLQ cover only
   messages that reached RabbitMQ, and never retry a failed outbox send. Put this in the Consumer
   Error Handling section too.
7. **Benefits list.** Rewrite it:
   - Keep: no dual-write problem; durable intent that survives a crash after commit; asynchronous
     HTTP responses; audit trail (with `completion-mode: update`), qualified by the step 4 note
     that `COMPLETED` is not broker-confirmed.
   - Remove "100% Guaranteed Delivery", "Exactly-Once Semantics", and "Automatic Retries".
   - Remove "Event Replay". Incomplete publications stay queryable in `event_publication` for
     diagnosis, but the guide does not recommend replaying them.
   - Add that consumers must tolerate duplicate or overlapping triggers whenever a reconciliation
     path exists.
8. **Event publication schema.** Change `completion_attempts INT` to
   `completion_attempts INT NOT NULL DEFAULT 0`. Add a note: the JPA registry
   (`spring-modulith-starter-jpa`) maps it to a primitive `int` and cannot load `NULL`, and the
   default backfills existing rows when migrating a Spring Modulith 1.x table. Keep the existing
   pointer to the appendix schema.
9. **Redis cache configuration.** Replace the hand-built `CacheManager` example with the Boot 4
   pattern used by currency-service:
   - A `RedisCacheConfiguration` bean with `entryTtl`, `disableCachingNullValues()`, a string key
     serializer, a `GenericJacksonJsonRedisSerializer` (Jackson 3) built from a rebuilt copy of
     the application `JsonMapper` with an allowlisting `BasicPolymorphicTypeValidator`, and
     `prefixCacheNameWith("<service-name>:")`.
   - A `RedisCacheManagerBuilderCustomizer` that sets
     `RedisCacheWriter.create(connectionFactory, c -> c.immediateWrites())` and `.transactionAware()`.

   Explain the following, citing the Spring Data Redis migration guide:
   - With Lettuce, Spring Data Redis 4 performs `put`, `evict`, and `clear` asynchronously by
     default. `immediateWrites()` makes them complete before the call returns.
   - The asynchronous `clear()` path issues `UNLINK`, which the platform's per-service Redis ACLs
     do not grant.
   - `transactionAware()` defers `put`, `evict`, and `clear` until after commit. `putIfAbsent`,
     `evictIfPresent`, and `invalidate` are not deferred.
   - Final `java.*` types are written without a type id, so cacheable methods must not return
     `List.of()` or `Stream.toList()` results.
   - `spring.cache.redis.*` properties are ignored when the service defines its own
     `RedisCacheConfiguration`.
10. **Redis TTL guidance.** Recommend a TTL even when every write path evicts. A read that started
    before a write committed can repopulate a stale entry after the after-commit eviction, and the
    TTL bounds how long it lasts. Mention that time-to-idle needs `GETEX`, which the service ACLs
    do not grant. Qualify the "Immediate consistency" bullets in When to Use and Why Redis: they
    hold only with `immediateWrites()` and `transactionAware()`, and within the TTL bound above.
11. **ShedLock configuration.** Change the `JdbcTemplateLockProvider` example to
    `JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build()`.
    Explain that lock times then come from the database clock in UTC, as the ShedLock README
    strongly recommends. Under Monitoring, add the optional `shedlock-micrometer` integration
    (ShedLock 7.7+, `MicrometerLockingTaskExecutorListener` bean, metrics such as
    `shedlock.lock.acquired`).
12. **Flyway dependencies.** Replace `implementation("org.flywaydb:flyway-core")` with
    `implementation("org.springframework.boot:spring-boot-starter-flyway")`. Spring Boot 4 moved
    Flyway auto-configuration into its own module. Keep
    `org.flywaydb:flyway-database-postgresql`.
13. **AGENTS.md.** Apply the AGENTS.md checkstyle. Then change only the Transactional Outbox
    quick-reference bullet so it says publications are stored atomically with business data and
    sent after commit, without claiming guaranteed delivery.

### Implementation notes

- Keep property names, defaults, and API names exactly as the cited sources give them. If a source
  contradicts this phase, follow the source and report the discrepancy.
- Use relative links for in-repository targets and full URLs for upstream references, matching the
  guide's existing style.
- This phase changes documentation only, so service-common's Gradle build is not required.

### Validation

- `rg -n -i 'exactly.once|guaranteed delivery|100%|automatic(ally)? retr|polls event_publication|republish-outstanding-events-on-restart: true|initial-interval|flyway-core|new JdbcTemplateLockProvider\(dataSource\)' docs/advanced-patterns.md AGENTS.md`
  returns nothing, apart from any sentence that explicitly warns against a quoted claim.
- `rg -n -i 'event replay|lock-guarded|resubmission job|2\.2\+|#1908|#1917|#1925' docs/advanced-patterns.md`
  returns nothing, apart from the sentence stating that the guide offers no resubmission job.
- Every relative link in the changed sections resolves, and every heading anchor that `AGENTS.md`
  links into `docs/advanced-patterns.md` still exists.
- YAML and Java snippets are syntactically plausible, with property keys at their correct nesting.

### Completion criteria

`docs/advanced-patterns.md` reflects steps 1–12 and `AGENTS.md` reflects step 13. The validation
`rg` is clean, all links and anchors resolve, and the final report lists the changed files and
states that no build was run because the change is documentation only.
