# RabbitMQ Publisher Confirms for the Outbox Publisher Plan

Make a `COMPLETED` row in `event_publication` mean that RabbitMQ confirmed and routed the import
request message, not only that `StreamBridge.send` returned. The fix resolves
[docs/issues/rabbitmq-publisher-confirms.md](../issues/rabbitmq-publisher-confirms.md). That issue
was deferred until the Spring Boot 4 upgrade merged, and it has (`d1d76b9`, PR #105). The fix
follows the Spring Cloud Stream Rabbit binder's preferred mechanism
([Publisher Confirms](https://docs.spring.io/spring-cloud-stream/reference/rabbit/rabbit_overview/publisher-confirms.html)):
correlated confirms and returns on the connection factory, `use-confirm-header` on the producer
binding, and a bounded wait on a per-message `CorrelationData` in the publisher.

Decisions that resolve the issue's open questions:

- **Confirm timeout:** add a property, `currency-service.messaging.publish-confirm-timeout-seconds`
  (default `10`, validated `@Min(1)`/`@Max(60)`). It follows the existing `fred.timeout-seconds`
  style. Do not use Hibernate Validator duration constraints, because `org.hibernate.*` imports are
  forbidden.
- **Shared guidance:** the service-common advanced-patterns guide will *require* every RabbitMQ
  outbox publisher to use publisher confirms and to fail the listener on a nack, a returned
  message, or a confirm timeout. It recommends waiting on each message only for low-volume
  publishers. A high-volume publisher should batch confirms or handle them asynchronously, as
  RabbitMQ's confirms guidance advises, but must still fail the publication when the broker does
  not confirm. currency-service is the only outbox publisher in the ecosystem today. Check with
  `rg -l 'StreamBridge|ApplicationModuleListener' ../*-service/src/main`.
- **Alerting on `status <> 'COMPLETED'`:** this is out of scope. It is a platform or orchestration
  concern. The docs only state that statuses are now accurate and keep the existing query.

Threading and blocking, and why waiting on each confirm is acceptable here:

- The confirm wait never runs on an HTTP request thread. `@ApplicationModuleListener` is
  meta-annotated with `@Async`, `@TransactionalEventListener` (after commit), and
  `@Transactional(REQUIRES_NEW)`. The request thread commits the currency change and its
  `event_publication` row, then returns. The listener runs later on the task executor
  (`async-task-*`, core 5, max 10, queue 100), and only that thread waits.
- A healthy broker confirms within milliseconds. A persistent message on a durable queue can take
  up to tens of milliseconds, because the broker writes it to disk first. The timeout bounds the
  wait only when the broker is unhealthy. `StreamBridge.send` already blocks the same executor
  thread while it connects to a broker that is down, so the wait adds no new kind of blocking.
- Volume is low: one message each time a currency is created enabled or is enabled, which is an
  admin action. RabbitMQ's confirms tutorial says waiting on each message limits throughput to
  hundreds of messages per second. That limit is irrelevant here.
- Returning a `CompletableFuture` from the listener would not free the thread. Spring Modulith's
  `CompletionRegisteringAdvisor` does complete or fail a publication from a returned future. But
  for an `@Async` method that returns a `Future`, Spring's `AsyncExecutionInterceptor` calls
  `get()` on the executor thread. Making the send itself non-blocking would mean dropping `@Async`
  and sending from the request thread's after-commit callback, which is worse. Use the bounded
  wait.
- Spring Modulith 2.1.2 takes the same approach where it aims for outbox delivery. Its default
  AMQP externalization sends without confirms and marks the publication complete immediately,
  which is the same gap this plan closes. Its `outbox` externalization mode (Namastack or JobRunr)
  refuses to start unless `spring.rabbitmq.publisher-confirm-type=correlated` is set, fails on a
  nack, and waits for the confirm on a background worker with an unbounded
  `CompletableFuture.get()`. This plan bounds the wait.

Facts verified against the resolved dependencies (Spring Boot 4.1.2, Spring AMQP 4.1.2, Spring
Cloud Stream Rabbit binder 5.0.4):

- `spring.rabbitmq.publisher-confirm-type` and `spring.rabbitmq.publisher-returns` exist in Boot
  4.1.2 metadata.
- `RabbitProducerProperties` has `useConfirmHeader` and `confirmAckChannel`. The two are mutually
  exclusive.
- The binder builds its producer `RabbitTemplate` with `mandatory = errorChannel != null ||
  useConfirmHeader`.
- `CorrelationData` exposes `getFuture()` (a `CompletableFuture<CorrelationData.Confirm>`) and
  `getReturned()` (a `ReturnedMessage`). **`getReturnedMessage()` does not exist**, so ignore that
  name in the issue doc.
- `PublisherCallbackChannelImpl` attaches a returned message to its `CorrelationData`. Spring AMQP
  sets the returned message before it completes the confirm future.

Plan-wide constraints:

- Keep all confirm handling in `ExchangeRateImportMessagePublisher`. Services stay unchanged. The
  listener changes only in its Javadoc.
- Do not add outbox resubmission, `republish-outstanding-events-on-restart`, a producer error
  channel handler, or `required-groups`. The startup and daily imports remain the recovery path.
- Follow the testing rules in `../service-common/docs/testing-patterns.md`. Use the real broker
  through Testcontainers and no Mockito replacement of `StreamBridge`, publishers, or listeners. Use
  real `CorrelationData` and `ReturnedMessage` value objects in pure-logic tests.
- Phase 4 edits `../service-common/`. The user explicitly authorized service-common documentation
  updates for this work. That phase runs under service-common's own `AGENTS.md` and touches
  documentation only. Do not publish artifacts.
- Out of scope: `../orchestration/docs/architecture/deployment-architecture-gcp.md` already lists
  "Publisher confirms enabled" as a target. This plan does not modify orchestration.

## Phase 1: Configure Confirms and Wait for Them in the Publisher

### Workspace

.

### Goal

Configure correlated publisher confirms and returns, and make
`ExchangeRateImportMessagePublisher` throw when the broker nacks, returns, or does not confirm a
message within the configured timeout. A failed confirm then marks the Spring Modulith publication
`FAILED`.

### Scope

- `src/main/resources/application.yml`: add `spring.rabbitmq.publisher-confirm-type: correlated`,
  `spring.rabbitmq.publisher-returns: true`, and
  `spring.cloud.stream.rabbit.bindings.exchangeRateImportRequested-out-0.producer.use-confirm-header: true`.
  Add `currency-service.messaging.publish-confirm-timeout-seconds: 10`.
- `src/test/resources/application.yml`: mirror the producer `use-confirm-header` setting under the
  existing `rabbit.bindings` block. The test file redefines the stream bindings, so it does not
  inherit them. Confirm whether the main `spring.rabbitmq.publisher-*` keys reach the test context.
  If they do not, mirror them too.
- `CurrencyServiceProperties`: add a nested `Messaging` class holding
  `publishConfirmTimeoutSeconds`, with `@Min(1)`, `@Max(60)`, and a default of `10`.
- `ExchangeRateImportMessagePublisher`: implement the confirmed send.
- New `MessagePublishException` (unchecked) in `messaging/publisher/`.
- A new pure-logic unit test for the confirm evaluation.
- Javadoc in `ExchangeRateImportMessagePublisher` and `MessagingEventListener` that describes
  `COMPLETED` semantics.

### Non-goals

- Integration tests for unroutable and nacked sends (Phase 2).
- Markdown documentation (Phases 3 and 4).
- Producer `error-channel-enabled`, `confirm-ack-channel`, or a shared service-common publisher
  helper.

### Required context

- [docs/issues/rabbitmq-publisher-confirms.md](../issues/rabbitmq-publisher-confirms.md)
- [docs/advanced-patterns-usage.md](../advanced-patterns-usage.md), section "Event-Driven
  Messaging".
- [docs/configuration.md](../configuration.md), sections "Messaging" and "Transactional Outbox".
- `../service-common/docs/code-quality-standards.md`. Read it before writing Java.
- `../service-common/docs/testing-patterns.md`, sections "Testing Layers", "Keep
  Application-Owned Spring Beans Real", and "Unit Tests".
- [Spring Cloud Stream Rabbit publisher confirms](https://docs.spring.io/spring-cloud-stream/reference/rabbit/rabbit_overview/publisher-confirms.html)
- `src/main/java/org/budgetanalyzer/currency/messaging/publisher/ExchangeRateImportMessagePublisher.java`
- `src/main/java/org/budgetanalyzer/currency/messaging/listener/MessagingEventListener.java`
- `src/main/java/org/budgetanalyzer/currency/config/CurrencyServiceProperties.java`

### Execution steps

1. Confirm the prerequisites: Docker is available for Testcontainers, and
   `org.budgetanalyzer:*:0.1.0-SNAPSHOT` resolves, for example with
   `./gradlew dependencies --configuration compileClasspath`. If either is missing, stop and
   report it. Follow `../orchestration/docs/development/service-common-artifact-resolution.md` for
   artifact problems. Do not work around them.
2. Add the YAML properties listed in Scope. Keep the existing comment style in `application.yml`.
3. Add `Messaging` to `CurrencyServiceProperties` with a getter and setter, wired like
   `ExchangeRateImport`, with `@Valid` on the field.
4. Add `MessagePublishException extends RuntimeException` with message and message-plus-cause
   constructors.
5. Rewrite `publishExchangeRateImportRequested`:
   - Create `new CorrelationData(UUID.randomUUID().toString())`. The id must be unique and
     non-null, because Spring AMQP uses it to correlate returns.
   - Build a `Message` with `MessageBuilder.withPayload(message)` and set the correlation under
     `AmqpHeaders.PUBLISH_CONFIRM_CORRELATION`. Use the `AmqpHeaders` class that the Spring Cloud
     Stream reference example uses, and verify the constant on the classpath.
   - Throw `MessagePublishException` if `streamBridge.send(...)` returns `false`.
   - Call a package-private `static void awaitBrokerConfirm(CorrelationData correlationData,
     Duration timeout)`. It waits on `getFuture().get(timeout)` and throws
     `MessagePublishException` in these cases:
     - `getReturned()` is non-null. Include the reply code, reply text, exchange, and routing key.
     - The confirm is a nack. Include `getReason()`.
     - The wait times out.
     - The future completes exceptionally.

     On `InterruptedException`, restore the interrupt flag before throwing. Check
     `getReturned()` after the future completes: an unroutable mandatory message is returned and
     *then* acked.
   - Keep the existing info log. Add a debug log with the correlation id after the confirm.
   - Inject `CurrencyServiceProperties` through the constructor and convert the seconds value to a
     `Duration` once.
6. Update the publisher Javadoc and the `MessagingEventListener` class and method Javadoc.
   `COMPLETED` now means that the broker confirmed and routed the message. A nack, a return, or a
   confirm timeout makes the listener throw, so the publication becomes `FAILED`. Remove the
   "Publisher confirms are not configured" sentences. State that the wait runs on the Modulith
   task-executor thread after commit, never on the HTTP request thread, and lasts at most the
   timeout. Do not change the listener's return type: keep `void` and the synchronous wait, for the
   reasons in the plan preamble.
7. Add `src/test/java/org/budgetanalyzer/currency/messaging/publisher/ExchangeRateImportMessagePublisherTest.java`.
   It is a pure unit test with no Spring context and no Mockito, and it calls
   `awaitBrokerConfirm` directly. Cover these cases:
   - An ack returns normally.
   - An ack with a `ReturnedMessage` set throws, and the message names the reply text.
   - A nack throws, and the message names the reason.
   - A future that never completes throws after a short timeout, such as 50 ms.
   - An exceptionally completed future throws, with the cause preserved.

   Put reusable values, such as the reply code `312` and the reply text `NO_ROUTE`, in
   `TestConstants`.

### Implementation notes

- `StreamBridge.send` sends synchronously on the caller's thread through the binding's direct
  channel, so the correlation header travels with the message on the listener thread.
- Enabling correlated confirms also makes the Rabbit binder's consumer-side `republish-to-dlq`
  wait for broker confirms, and it fails a DLQ republish whose message is returned. This side
  effect is acceptable. Phase 3 documents it.
- Do not set `spring.rabbitmq.template.mandatory`. The binder builds its own template and sets
  `mandatory` from `use-confirm-header`.

### Validation

```bash
./gradlew clean spotlessApply
./gradlew test --tests '*ExchangeRateImportMessagePublisherTest' \
  --tests '*EventListenerIntegrationTest' \
  --tests '*MessageConsumerIntegrationTest' \
  --tests '*EndToEndMessagingFlowIntegrationTest' \
  --tests '*CurrencyServiceApplicationIntegrationTest'
```

The existing messaging tests must pass unchanged. They now show that a routed send under confirms
still completes the publication and drives the import.

### Completion criteria

- The confirm and returns properties are active in the main and test configurations.
- The publisher waits for a confirm and throws `MessagePublishException` on a nack, a return, a
  timeout, a failed future, or a `false` send result.
- The unit tests cover every `awaitBrokerConfirm` branch, and the focused messaging integration
  tests pass.
- No service or listener logic changes, apart from Javadoc.

## Phase 2: Prove Broker Outcomes Against the Testcontainers RabbitMQ Broker

### Workspace

.

### Goal

Add integration tests that drive real currency creates through the outbox against the
Testcontainers broker. A routed send must leave the publication `COMPLETED`. An unroutable send and
a broker-nacked send must each leave it `FAILED`.

### Scope

- New `src/test/java/org/budgetanalyzer/currency/messaging/PublisherConfirmIntegrationTest.java`.
- `TestDatabaseHelper`: add a status-count query, for example `countEventsByStatus(String
  status)`.
- `TestConstants`: add the constants the new test needs.
- Main configuration changes only if the fallback in the execution steps is triggered.

### Non-goals

- Changing existing tests, apart from shared helpers.
- Testing the confirm timeout against the broker. Phase 1 covers it with a pure-logic test.

### Required context

- `../service-common/docs/testing-patterns.md`, sections on integration tests and Testcontainers.
- `src/test/java/org/budgetanalyzer/currency/messaging/EventListenerIntegrationTest.java`, for the
  arrange and await patterns.
- `src/test/java/org/budgetanalyzer/currency/base/AbstractWireMockTest.java`,
  `TestDatabaseHelper.java`, and `config/TestContainersConfig.java`.
- `src/test/resources/application.yml`
- The Phase 1 publisher implementation.

### Execution steps

1. In the new test class, extend `AbstractWireMockTest`. Override the producer destination with a
   `@DynamicPropertySource`. Set
   `spring.cloud.stream.bindings.exchangeRateImportRequested-out-0.destination` to a name built
   from a static `UUID` generated once in the class, for example
   `exchange-rate.import.requested.confirm-test-<uuid>`. Do not use `${random.uuid}`, because it
   can resolve differently for the binder and the test. Use a fresh name per run so state left in
   the reused container cannot leak between runs. The binder declares the topic exchange when it
   binds the producer.
2. Use the auto-configured `AmqpAdmin` to declare queues in each test and remove them in
   `@AfterEach`:
   - **Routed:** declare a non-durable queue bound with `#` to the test destination. Create an
     enabled currency through `CurrencyService` with the FRED stubs, as in
     `EventListenerIntegrationTest`. Assert one `COMPLETED` publication and zero `FAILED`
     publications. Optionally assert that the bound queue holds one message.
   - **Unroutable:** bind no queue. Create an enabled currency. With Awaitility, assert one
     `FAILED` publication and zero completed ones.
   - **Nacked:** declare a queue with `x-max-length=0` and `x-overflow=reject-publish`, bound with
     `#`. Create an enabled currency. Assert one `FAILED` publication. RabbitMQ answers a publish
     to a full `reject-publish` queue with `basic.nack`.
3. Add `countEventsByStatus` (or `countFailedEvents`) to `TestDatabaseHelper`. Use a parameterized
   query on the `status` column added by `V8`.
4. **Fallback:** if the unroutable test shows `COMPLETED` (no return correlated), enable
   `spring.cloud.stream.bindings.exchangeRateImportRequested-out-0.producer.error-channel-enabled:
   true` in the main and test configurations, which the Spring Cloud Stream reference example
   includes. Rerun the tests. If that does not fix it either, stop and report your findings. Do
   not weaken the test. Record which configuration was required, so Phase 3 documents the real
   configuration.
5. Name the tests after their behavior, following the existing `should...` convention, and keep
   the timing short. Confirms and returns arrive within milliseconds, so a few seconds of
   Awaitility is enough.

### Implementation notes

- The application's consumer binding (`importExchangeRates-in-0`) stays bound to the default
  destination, so the test's own destination has no other consumers.
- Spring Modulith stores no exception text. The status is the observable outcome. Do not assert on
  log output.

### Validation

```bash
./gradlew clean spotlessApply
./gradlew test --tests '*PublisherConfirmIntegrationTest' \
  --tests '*ExchangeRateImportMessagePublisherTest' \
  --tests '*EventListenerIntegrationTest' \
  --tests '*EndToEndMessagingFlowIntegrationTest'
```

### Completion criteria

- The routed, unroutable, and nacked tests pass against the real broker, and they show
  `COMPLETED`, `FAILED`, and `FAILED` respectively.
- Any fallback configuration change is in place, and the reason for it is recorded in the test
  class Javadoc.
- The existing messaging tests still pass.

## Phase 3: Update currency-service Documentation and Run the Full Build

### Workspace

.

### Goal

Bring the currency-service docs, `AGENTS.md`, and the issue record in line with the confirmed
semantics, then pass the full build.

### Scope

- `docs/configuration.md`
- `docs/advanced-patterns-usage.md`
- `docs/domain-model.md`
- `AGENTS.md`
- `docs/issues/rabbitmq-publisher-confirms.md`

### Non-goals

- service-common documentation (Phase 4).
- Changing recovery behavior or the publication-retention decisions.

### Required context

- [docs/configuration.md](../configuration.md), sections "Messaging", "Transactional Outbox", and
  "Outbox Recovery".
- [docs/advanced-patterns-usage.md](../advanced-patterns-usage.md), section "Event-Driven
  Messaging", including "Delivery Guarantees", "RabbitMQ Configuration", "Discovery Commands", and
  "Event-Driven Messaging Testing".
- [docs/domain-model.md](../domain-model.md), section "Domain Events".
- `../orchestration/docs/agents-md-checkstyle.md`. Read it before editing `AGENTS.md`.
- The Phase 1 and Phase 2 code, tests, and final YAML.

### Execution steps

1. `docs/configuration.md`:
   - Add `currency-service.messaging.publish-confirm-timeout-seconds` to the properties
     documentation.
   - Document `publisher-confirm-type`, `publisher-returns`, and `use-confirm-header`, plus
     `error-channel-enabled` if Phase 2 required it, in the messaging section.
   - Say where the confirm wait runs. It runs on the task executor after commit, so HTTP responses
     never wait for RabbitMQ. A healthy broker confirms within milliseconds, and the timeout bounds
     the wait only when the broker is unhealthy. Waiting on each message suits this service's low
     message volume.
   - Rewrite the Transactional Outbox bullet that begins "A send fails the listener only when...".
     A nack, a return, a confirm timeout, or a connection failure now fails the listener and leaves
     the publication `FAILED`. `COMPLETED` means that the broker confirmed and routed the message.
   - Remove the link to the issue doc.
   - Note that `status <> 'COMPLETED'` now finds every failed send, apart from a crash during the
     confirm wait, which stays `PROCESSING`.
   - Note the DLQ republish side effect from Phase 1.
2. `docs/advanced-patterns-usage.md`:
   - Remove the "Publisher confirms are not configured..." paragraph in "Delivery Guarantees".
   - Add a "Broker rejects or cannot route the message" scenario that ends `FAILED`.
   - Update step 6 of "Event Flow".
   - Show the abridged confirm wait in the publisher description.
   - Add the producer and connection-factory keys to "RabbitMQ Configuration".
   - Under "Adding New Events", require new publishers to use the same confirm pattern.
   - Fix the `rg -n -A 19 '^  cloud:'` discovery command so it still covers the whole `cloud:`
     block after the YAML growth.
   - Mention `PublisherConfirmIntegrationTest` in the testing section.
   - Add "wait for a publisher confirm" to the Messaging **Do** list.
3. `docs/domain-model.md`: update the publication-lifecycle bullets with the new meaning of
   `COMPLETED`.
4. `AGENTS.md`: following the checkstyle, add one guardrail under "Architecture and Security
   Rules". RabbitMQ outbox publishers must wait for a publisher confirm and fail on a nack, a
   return, or a timeout. Link to `docs/advanced-patterns-usage.md` for the details instead of
   copying them.
5. Delete `docs/issues/rabbitmq-publisher-confirms.md`. `docs/configuration.md` now owns the
   behavior. Remove every inbound link, which you can find with `rg -n 'rabbitmq-publisher-confirms'
   -g '*.md' .`. Keep this plan's own link, because it records history. If `docs/issues/` is left
   empty, remove the directory.
6. Check the changed docs for stale wording with `rg -n -i 'not broker-confirmed|confirms are not
   configured|confirms and returns are not|StreamBridge.send. returned' docs AGENTS.md README.md
   src/main/java`.
7. Run the full validation, then verify that every relative link changed in this phase resolves.

### Implementation notes

- Keep each fact in one owner document. `configuration.md` owns property values and outbox
  semantics. `advanced-patterns-usage.md` owns code excerpts and flow, and links back.

### Validation

```bash
./gradlew clean spotlessApply
./gradlew clean build
rg -n 'rabbitmq-publisher-confirms' -g '*.md' .
```

The final `rg` should match only this plan. Check each relative link added or changed in the
edited Markdown files.

### Completion criteria

- No currency-service doc or Javadoc still says that confirms are unconfigured or that `COMPLETED`
  means only "send returned".
- The issue doc is removed, and no dangling links remain.
- `AGENTS.md` carries the new guardrail and conforms to the checkstyle.
- `./gradlew clean spotlessApply` and `./gradlew clean build` pass, including the JaCoCo gates. If
  a verifier cannot run, report that explicitly.

## Phase 4: Update the service-common Outbox Guidance

### Workspace

../service-common

### Goal

Change the shared transactional outbox guidance so that RabbitMQ outbox publishers must use
publisher confirms and fail the publication on a nack, a return, or a confirm timeout. Waiting on
each message is the recommended approach only for low-volume publishers. With confirms in place, `COMPLETED` means "broker confirmed and routed", and
the "platform publishers do not configure confirms yet" caveat goes away. currency-service is the
reference implementation.

### Scope

- `docs/advanced-patterns.md`, section "Event-Driven Messaging with Transactional Outbox".
- `AGENTS.md`, the "Advanced Patterns" quick reference.

### Non-goals

- Library code, a shared publisher helper, or artifact publishing.
- Editing currency-service or orchestration.
- Release-specific migration notes. Keep the canonical docs version-agnostic, as service-common's
  `AGENTS.md` requires.

### Required context

- `AGENTS.md` (service-common), especially "Documentation Discipline" and "Documentation
  Strategy".
- `docs/advanced-patterns.md`, subsections "Architecture", "Pattern Components" (items 3 and 4),
  "Publication Lifecycle", "Configuration", "Outbox Recovery", and "Benefits".
- `../orchestration/docs/agents-md-checkstyle.md`. Read it before editing `AGENTS.md`.
- The currency-service reference: `../currency-service/src/main/java/org/budgetanalyzer/currency/messaging/publisher/ExchangeRateImportMessagePublisher.java`,
  `../currency-service/src/main/resources/application.yml`, and
  `../currency-service/docs/configuration.md`.
- [Spring Cloud Stream Rabbit publisher confirms](https://docs.spring.io/spring-cloud-stream/reference/rabbit/rabbit_overview/publisher-confirms.html)

### Execution steps

1. In "Architecture", show that the listener sends through `StreamBridge` and waits for the broker
   confirm. Make clear that a nack, a return, or a timeout leaves the publication `FAILED`.
2. In "Pattern Components":
   - Item 3: fix the listener comment to say `COMPLETED` after a confirmed send.
   - Item 4: replace the bare `streamBridge.send` example with the confirmed publisher pattern. The
     example sets a unique `CorrelationData` under `AmqpHeaders.PUBLISH_CONFIRM_CORRELATION`,
     treats a `false` send result as a failure, and uses a bounded `getFuture().get(timeout)`. It
     checks `getReturned()`, not `getReturnedMessage()`, after the confirm, throws on a nack, a
     return, or a timeout, and restores the interrupt flag. Keep the example abridged, and point
     to currency-service's publisher as the full reference.
3. In "Publication Lifecycle", rewrite **What `COMPLETED` means**. With the required confirm
   pattern, `COMPLETED` means the broker confirmed and routed the message. Without it, `COMPLETED`
   means only that the listener returned. Drop "The platform's outbox publishers do not configure
   publisher confirms or returns yet". Update the `COMPLETED` row in the status table if it needs
   to change.
4. In "Configuration", add `spring.rabbitmq.publisher-confirm-type: correlated`,
   `spring.rabbitmq.publisher-returns: true`, and the producer binding's `use-confirm-header:
   true` to the YAML example, using the example's `currencyCreated-out-0` binding. Add
   `error-channel-enabled` only if currency-service needed it in its Phase 2. Then add these notes:
   - `use-confirm-header` makes the binder publish with `mandatory`, so unroutable messages are
     returned.
   - `use-confirm-header` and `confirm-ack-channel` are mutually exclusive.
   - Correlated confirms also make `republish-to-dlq` wait for confirms.
5. Add a short "Confirm Handling and Throughput" subsection next to "Configuration". Keep it
   version-agnostic and cover these points:
   - The wait runs on the task-executor thread that runs the `@ApplicationModuleListener` after
     commit. HTTP request threads never wait for RabbitMQ.
   - Waiting on each message with a bounded timeout is the recommended default for low-volume
     publishers, where a few messages per second at most is typical. A healthy broker confirms
     within milliseconds, and the timeout matters only when the broker is unhealthy. Each waiting
     message holds one executor thread, so size the timeout and the executor pool together.
   - High-volume publishers must still confirm, but should not wait on each message. RabbitMQ's
     confirms guidance recommends batching or handling confirms asynchronously for throughput. Link
     to the [RabbitMQ publisher confirms tutorial](https://www.rabbitmq.com/tutorials/tutorial-seven-java).
     A publisher that needs this must design how a confirm failure still marks the publication
     `FAILED` before it adopts the outbox, and must document the design in its own repository.
   - Returning a `CompletableFuture` from an `@ApplicationModuleListener` does not free the thread.
     Spring Modulith completes the publication from the future, but Spring's `@Async` support
     calls `get()` on the executor thread. Do not present it as a non-blocking alternative.
   - Spring Modulith's own RabbitMQ `outbox` externalization mode also requires
     `spring.rabbitmq.publisher-confirm-type=correlated` and waits for the confirm on a background
     worker. Its default externalization mode does not wait for confirms. Cite this as
     corroboration and do not pin a version.
6. In "Benefits" item 4, update the audit-trail wording. In "Outbox Recovery", keep the
   reconciliation requirement. Confirms make failures visible as `FAILED`, but nothing resubmits
   those publications.
7. In `AGENTS.md`, following the checkstyle and service-common's own `AGENTS.md` rules, update the
   Transactional Outbox quick-reference line. Replace "(guaranteed messaging)" and "delivery is not
   guaranteed" with accurate wording, for example: publishers use broker confirms, a failed send
   leaves the publication `FAILED`, and nothing retries it. Do not use GitHub-web links in
   `AGENTS.md`.
8. Search for leftover contradictions with `rg -n -i 'confirm|guaranteed messaging|COMPLETED'
   docs/advanced-patterns.md AGENTS.md`, and reconcile every hit.

### Implementation notes

- currency-service's docs link to this guide by file URL, not by anchor. Keep the existing heading
  text anyway, so service-common's own anchors, such as
  `#publication-lifecycle-spring-modulith-2x` and `#outbox-recovery`, stay valid.
- This phase is documentation-only. If service-common's `AGENTS.md` requires a build for doc
  changes, run it. Otherwise, check the links.

### Validation

- Every relative link and in-document anchor touched in `docs/advanced-patterns.md` and `AGENTS.md`
  resolves. Check the anchors against the actual headings.
- The YAML and Java snippets are syntactically plausible, and the property names match those in
  `../currency-service/src/main/resources/application.yml`.
- `git status --short` shows changes only in `docs/advanced-patterns.md` and `AGENTS.md`.

### Completion criteria

- The shared guide requires confirmed publishing for RabbitMQ outbox publishers and shows the
  pattern and its configuration. It limits waiting on each message to low-volume publishers, says
  the wait runs off the request thread, and points high-volume publishers to batched or
  asynchronous confirm handling.
- No service-common text claims that platform publishers lack confirms, or that the outbox
  guarantees delivery.
- Only service-common documentation changed. Nothing was published.
