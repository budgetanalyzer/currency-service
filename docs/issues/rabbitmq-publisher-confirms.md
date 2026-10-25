# Outbox Sends Are Marked Completed Without a Broker Confirm

**Status:** Open. Deferred until the Spring Boot 4 upgrade PR is merged.

## Problem

`MessagingEventListener` hands each import request to
`ExchangeRateImportMessagePublisher.publishExchangeRateImportRequested(…)`. The publisher calls
`streamBridge.send("exchangeRateImportRequested-out-0", message)` and ignores the `boolean` result.
Publisher confirms and returns are not configured:

- `spring.rabbitmq.publisher-confirm-type` and `spring.rabbitmq.publisher-returns` are unset.
- The `exchangeRateImportRequested-out-0` Rabbit producer sets neither `use-confirm-header` nor
  `confirm-ack-channel`.

When `send` returns, the listener returns and Spring Modulith marks the publication `COMPLETED`.
Today `COMPLETED` means "the frame was written to the channel", not "the broker accepted and routed
the message".

## Failure Modes

| Failure | What `send` does | Publication state | Recorded? |
|---------|------------------|-------------------|-----------|
| Connection unavailable | Throws | `FAILED` | Yes |
| Unroutable message, for example a deleted queue or binding | Returns normally; the broker drops the message | `COMPLETED` | No |
| Broker refuses the publish asynchronously, for example a permission error that closes the channel after the publish call returns | Returns normally | `COMPLETED` | No |
| Broker fails before persisting the message | Returns normally | `COMPLETED` | No |

## Current Impact

Recovery is not affected. The startup import and the daily 23:00 UTC import reconcile every enabled
series from its latest stored rate, whatever `event_publication` records. A silently lost import
request recovers the same way as a `FAILED` send. See the outbox recovery rationale in
[configuration.md](../configuration.md#transactional-outbox).

The impact is visibility and accuracy:

- Lost sends leave no `FAILED` row, so `event_publication` cannot be used to find them.
- The outbox audit trail overstates delivery.
- A future service that adopts the outbox without a reconciliation path would lose messages with
  no trace.

## Proposed Fix

Use the confirm mechanism that Spring Cloud Stream 5 documents as preferred
([Publisher Confirms](https://docs.spring.io/spring-cloud-stream/reference/rabbit/rabbit_overview/publisher-confirms.html)):

1. Configure the connection factory and producer:

   ```yaml
   spring:
     rabbitmq:
       publisher-confirm-type: correlated
       publisher-returns: true
     cloud:
       stream:
         rabbit:
           bindings:
             exchangeRateImportRequested-out-0:
               producer:
                 use-confirm-header: true
   ```

2. In `ExchangeRateImportMessagePublisher`, attach a `CorrelationData` with a unique id under
   `AmqpHeaders.PUBLISH_CONFIRM_CORRELATION`, wait on its future with a bounded timeout, and throw
   when the confirm is a nack, when `getReturnedMessage()` is non-null, or when the wait times out.
   The exception makes the listener fail, so the publication is recorded as `FAILED`.
3. Keep the confirm handling in the publisher. Services and listeners stay unchanged apart from
   Javadoc.

## Open Questions

- Confirm timeout value, and whether it should be a configuration property.
- Whether the shared guide in `../service-common/docs/advanced-patterns.md` should require confirms
  for every outbox publisher. That is a separate, authorized service-common change.
- Whether the platform should alert on publications with `status <> 'COMPLETED'` once their
  statuses are accurate.

## Acceptance Criteria

- An integration test against the Testcontainers RabbitMQ broker shows that a routed send completes
  the publication.
- An integration test shows that an unroutable send leaves the publication `FAILED`.
- `docs/advanced-patterns-usage.md` and `docs/configuration.md` state that `COMPLETED` means the
  broker confirmed the message, and the "not broker-confirmed" caveat added by the Spring Boot 4
  advanced-patterns plan is removed.
- `./gradlew clean spotlessApply` and `./gradlew clean build` pass.
