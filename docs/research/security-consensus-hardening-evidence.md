# Security Consensus Hardening Evidence

## 2026-11-07: Currency-service safe runtime defaults

Security hardening plan 03, Phase 10. The configuration facts live in
[configuration.md](../configuration.md#logging); this section records the decisions and the
validation results.

### Logging boundary

`application.yml` logs `org.budgetanalyzer` at `INFO` instead of `TRACE` and enables the
`budgetanalyzer.audit` logger at `INFO` above the root `WARN` threshold. It no longer sets
`include-request-body: true` or `include-response-body: false`, so service-common's `false`
defaults apply to both. The opt-in `debug-logging` profile restores `TRACE` and both body flags,
for synthetic local data only.

The `org.springframework.web.reactive.function.client: DEBUG` logger was left unchanged. Spring
Framework 7.0.10 builds that log line with `WebClientUtils.getRequestDescription`, which drops the
query string, so the FRED `api_key` query parameter is not logged.

### Readiness decision

Readiness is `readinessState,db,redis`. Liveness keeps only `livenessState`.

- PostgreSQL is required for every request.
- Redis is included deliberately. The plan's rule was to exclude it if a cache miss can fall back
  to the database. It cannot: neither this repository nor service-common configures a
  `CacheErrorHandler`, so Spring's default rethrows Redis errors, and `GET /v1/exchange-rates`
  fails during a Redis outage instead of reading PostgreSQL. Adding that fallback would be a code
  change outside this phase; if it is made, `redis` should leave the readiness group.
- RabbitMQ is excluded. Only the Spring Modulith outbox listener calls `StreamBridge`; request
  handlers write publications to PostgreSQL. The FRED API is a downstream service and is excluded.

### Authorization coverage

`RuntimeSecurityIntegrationTest` enumerates `RequestMappingHandlerMapping` handlers from
`org.budgetanalyzer` packages and fails for any handler without method- or class-level
`@PreAuthorize`. The exception list is empty; all six handlers are annotated. Removing the
annotation from `ExchangeRateController#importLatestExchangeRates` temporarily made the test fail
with that handler named. The annotation was then restored.

### Validation

- `./gradlew test` for the three new test classes: `RuntimeConfigurationIntegrationTest` 1/1,
  `DebugLoggingProfileIntegrationTest` 1/1, `RuntimeSecurityIntegrationTest` 2/2.
  `RuntimeConfigurationIntegrationTest` is one sequential runtime-contract test. It needs no
  method ordering, and it checks the following in this order:
  - With the main `application.yml` and the `kubernetes` profile, the effective configuration has
    `org.budgetanalyzer` and `budgetanalyzer.audit` at `INFO`, and both body flags are `false`.
  - With all stores up, readiness returns `200`, with `readinessState`, `db`, and `redis` components
    and no `rabbit` component. Liveness returns `200` with no `db` or `redis` component.
  - After Redis stops, readiness returns `503` with `redis` `DOWN` and `db` `UP`. Liveness stays
    `200`.
  - After PostgreSQL also stops, readiness returns `503` with `db` `DOWN`. Redis stays stopped,
    so the test asserts the named `db` component only. Liveness stays `200`.
- `RuntimeSecurityIntegrationTest`: one `PUT /v1/currencies/{id}` emits exactly one audit event.
  The event has the actor, the route pattern, the path-variable ID, and outcome `200`, and contains
  no request-body field.
- `./gradlew clean spotlessApply` passed.
- `./gradlew clean build` passed: 278 tests with no failures, errors, or skips, no Checkstyle
  findings, and the JaCoCo gate met.

### Limitations

- No live cluster run. The Kubernetes probes still target `/actuator/health`. Plan 03, Phase 12
  switches them to the probe groups.
- The `KEYS`-based cache eviction is unchanged. Plan 03, Phase 11 owns the `SCAN` change.

## 2026-11-07: Currency-service cache clears with SCAN

Security hardening plan 03, Phase 11, applying contested issue #2. The configuration facts live in
[configuration.md](../configuration.md#caching).

### Change

`CacheConfig` adds `BatchStrategies.scan(CACHE_CLEAR_BATCH_SIZE)` to the cache-writer configurer
next to `immediateWrites()`, with `CACHE_CLEAR_BATCH_SIZE = 1000`. Spring Data Redis 4.1.2's `SCAN`
strategy issues `SCAN MATCH <pattern> COUNT 1000` and deletes each batch of up to 1000 keys with
`DEL`. Before this change, every `@CacheEvict(allEntries = true)` ran one `KEYS` pass, then `DEL`.
`@CacheEvict(allEntries = true)`, the transaction-aware after-commit eviction, and the one-hour TTL
are unchanged.

A batch size of 1000 keeps each `SCAN` and `DEL` call short, so ext-authz session reads on the same
Redis instance run between batches.

### Validation

- `ExchangeRateCacheEvictionAclIntegrationTest` runs the application against its own Redis
  container. The application connects as the `currency-service` ACL user, with the orchestration
  `start-redis.sh` rules minus `+keys`:
  `~currency-service:* +get +set +del +scan +ping +auth +hello +info +ttl +pttl +expire +exists
  +type +object`. The default user is disabled.
  - `KEYS` from the service user fails with `NOPERM`.
  - 2001 `exchangeRates` entries (three `SCAN`/`DEL` batches) plus one unrelated
    `currency-service:` key are written. A committed `POST /v1/exchange-rates/import` returns `200`
    and leaves zero `exchangeRates` keys, by a `SCAN` count. The unrelated key survives.
  - Negative control: with the `batchStrategy` line removed, both tests failed with
    `NOPERM User currency-service has no permissions to run the 'keys' command`. The line was
    then restored.
- `ExchangeRateImportCacheIntegrationTest` still passes 5/5.
- `./gradlew clean spotlessApply` passed.
- `./gradlew clean build` passed: 280 tests with no failures, errors, or skips, no Checkstyle
  findings, and the JaCoCo gate met.

### Limitations and handoff

- Plan 03, Phase 13 removes `+keys` from the orchestration grant. This repository no longer needs
  it.
- A clear is not a point-in-time delete. A stale entry written by a read that races the scan
  remains, bounded by the one-hour TTL. This is the race accepted in contested issue #2.
- No live cluster run.

## 2026-11-08: Currency-service runtime configuration test simplified

Security hardening plan 03 review remediation, Phase 4.

### Change

`RuntimeConfigurationIntegrationTest` no longer uses `@TestMethodOrder` and `@Order`. Its four
stateful methods are now one sequential runtime-contract test,
`kubernetesRuntimeKeepsLoggingSafeAndReadinessDependentOnDataStores`. The test covers the
Phase 10 validation above in this order: logging defaults, initial readiness and liveness
membership, the Redis outage, then the PostgreSQL outage. Stopped containers are not restarted.

The `spring.config.additional-location=file:./src/main/resources/` override stays, with a comment
explaining why. `src/test/resources/application.yml` shadows the main `application.yml` on the
test classpath, so without the override only `application-kubernetes.yml` would load from main
resources. The test-owned PostgreSQL, Redis, and RabbitMQ containers, `@DirtiesContext`, the FRED
test key, the disabled startup import, and `show-components=always` are unchanged. No production
configuration or application code changed.

### Validation

- `./gradlew test --tests 'org.budgetanalyzer.currency.RuntimeConfigurationIntegrationTest'`
  passed: 1 test with no failures, errors, or skips.
- `./gradlew clean spotlessApply` passed.
- `./gradlew clean build` passed: 277 tests with no failures, errors, or skips, no Checkstyle
  findings in main or test, and the JaCoCo gate met. The count is three lower than the 280 above
  because four methods became one.
- `git diff --check` is clean.
