package org.budgetanalyzer.currency.messaging.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import org.budgetanalyzer.currency.domain.event.CurrencyCreatedEvent;
import org.budgetanalyzer.currency.domain.event.CurrencyUpdatedEvent;
import org.budgetanalyzer.currency.messaging.message.ExchangeRateImportRequestedMessage;
import org.budgetanalyzer.currency.messaging.publisher.ExchangeRateImportMessagePublisher;
import org.budgetanalyzer.service.servlet.http.CorrelationIdFilter;

/**
 * Event listener that bridges domain events to external messaging (RabbitMQ).
 *
 * <p>This component listens for internal domain events published by the service layer and
 * translates them into external messages for inter-service communication. It uses Spring Modulith's
 * {@code @ApplicationModuleListener} annotation to enable the transactional outbox pattern.
 *
 * <p><b>Transactional Outbox Pattern:</b>
 *
 * <p>Spring Modulith's event publication registry tracks each delivery to an
 * {@code @ApplicationModuleListener} as follows:
 *
 * <ol>
 *   <li><b>Event Persistence:</b> When a domain event is published within a transaction, Spring
 *       Modulith persists a publication to the {@code event_publication} table in the same database
 *       transaction as the business data (e.g., currency series creation).
 *   <li><b>Atomic Commit:</b> Both the business entity and the publication are committed
 *       atomically: either both succeed or both fail. This eliminates the "dual-write" problem
 *       where data is saved but the import request is never recorded.
 *   <li><b>Async Processing:</b> After the transaction commits, Spring Modulith invokes the
 *       listener method asynchronously on a background thread.
 *   <li><b>External Publishing:</b> The listener method publishes the message to RabbitMQ.
 *   <li><b>Completion:</b> When the listener returns normally, the publication is marked completed
 *       ({@code status = 'COMPLETED'} with a {@code completion_date}). With the default {@code
 *       completion-mode} of {@code update}, the completed row is kept permanently as an intentional
 *       audit trail. Publisher confirms are not configured, so {@code COMPLETED} means {@code
 *       StreamBridge.send} returned, not that the broker accepted and routed the message.
 *   <li><b>Failure:</b> Spring Modulith invokes the listener once per publication. When the
 *       listener throws, the publication is marked {@code FAILED} and stays incomplete (no {@code
 *       completion_date}). Nothing resubmits it.
 *   <li><b>Interruption:</b> A crash after commit but before the listener completes leaves the
 *       publication {@code PUBLISHED} or {@code PROCESSING}. Nothing resubmits it either.
 *   <li><b>Recovery:</b> The startup import and the daily scheduled import each load every enabled
 *       series from the day after its latest stored rate, or full history when none is stored. A
 *       currency whose import request was lost catches up at the next of those runs.
 * </ol>
 *
 * <p><b>Benefits Over Direct Message Publishing:</b>
 *
 * <ul>
 *   <li><b>Durable Intent:</b> Publications survive application crashes since they're persisted in
 *       the database before the transaction commits
 *   <li><b>Atomic Intent:</b> The outbox records the import request atomically with the currency
 *       change and never sends for a rolled-back change. Delivery of a failed send relies on import
 *       reconciliation, not on outbox resubmission.
 *   <li><b>Decoupled Services:</b> Service layer publishes domain events without knowledge of
 *       external messaging infrastructure
 *   <li><b>Async HTTP Responses:</b> HTTP requests return immediately after database commit without
 *       waiting for RabbitMQ publishing
 *   <li><b>Audit Trail:</b> Completed publications remain in the {@code event_publication} table
 * </ul>
 *
 * <p><b>Thread Safety:</b>
 *
 * <p>Listener methods are invoked on background threads managed by Spring Modulith's event
 * publication executor. MDC (Mapped Diagnostic Context) is used to propagate correlation IDs across
 * thread boundaries for distributed tracing.
 *
 * <p><b>Error Handling:</b>
 *
 * <p>Exceptions thrown from listener methods mark the publication {@code FAILED}. The incomplete
 * publication remains in the {@code event_publication} table as a record of the failed send. The
 * service intentionally does not republish outstanding publications on restart and adds no
 * resubmission code; the startup and daily imports reconcile every enabled series instead.
 *
 * @see CurrencyCreatedEvent
 * @see ExchangeRateImportMessagePublisher
 * @see org.springframework.modulith.events.ApplicationModuleListener
 */
@Component
public class MessagingEventListener {

  private static final Logger log = LoggerFactory.getLogger(MessagingEventListener.class);

  private final ExchangeRateImportMessagePublisher exchangeRateImportMessagePublisher;

  /**
   * Constructs a new MessagingEventListener.
   *
   * @param exchangeRateImportMessagePublisher The publisher for exchange rate import messages
   */
  public MessagingEventListener(
      ExchangeRateImportMessagePublisher exchangeRateImportMessagePublisher) {
    this.exchangeRateImportMessagePublisher = exchangeRateImportMessagePublisher;
  }

  /**
   * Handles currency created events by publishing external messages to RabbitMQ.
   *
   * <p>This method is invoked asynchronously by Spring Modulith after the database transaction that
   * persisted the currency entity has committed. The event itself was stored in the {@code
   * event_publication} table within the same transaction.
   *
   * <p><b>Import Eligibility:</b>
   *
   * <p>{@link org.budgetanalyzer.currency.service.CurrencyService} publishes this event only for
   * enabled currencies, so this listener can translate every received event into an external import
   * request.
   *
   * <p><b>Execution Flow:</b>
   *
   * <ol>
   *   <li>After the transaction commits, Spring Modulith invokes this method on a background thread
   *       with the event payload
   *   <li>Correlation ID is set in MDC for distributed tracing
   *   <li>An import request message is published to RabbitMQ via {@link
   *       ExchangeRateImportMessagePublisher}
   *   <li>On success, Spring Modulith marks the publication completed and keeps it as an audit
   *       record
   *   <li>On failure, Spring Modulith marks the publication failed and does not resubmit it; the
   *       next startup or scheduled import reconciles the series
   * </ol>
   *
   * <p><b>Distributed Tracing:</b>
   *
   * <p>The correlation ID from the original HTTP request is propagated through the domain event and
   * set in MDC before publishing the external message. This allows the entire flow (HTTP request →
   * service → domain event → external message → downstream consumer) to be traced with a single
   * correlation ID.
   *
   * <p><b>Error Handling:</b>
   *
   * <p>Exceptions are logged and rethrown so Spring Modulith marks the publication failed. The
   * incomplete publication remains in the {@code event_publication} table and is not resubmitted;
   * the next startup or scheduled import reconciles the series. MDC is always cleared in the
   * finally block to prevent memory leaks in the thread pool.
   *
   * <p><b>Thread Safety:</b>
   *
   * <p>This method is thread-safe as each invocation operates on independent event data. MDC is
   * thread-local and cleared after processing.
   *
   * @param event The currency created domain event containing currency series details and
   *     correlation ID
   * @see CurrencyCreatedEvent
   * @see ExchangeRateImportMessagePublisher
   */
  @ApplicationModuleListener
  void onCurrencyCreated(CurrencyCreatedEvent event) {
    // Set correlation ID in MDC for distributed tracing across async boundaries
    MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, event.correlationId());
    MDC.put("eventType", "currency_created");

    try {
      log.info(
          "Processing currency created event: currencySeriesId={}, currencyCode={}, enabled={}",
          event.currencySeriesId(),
          event.currencyCode(),
          event.enabled());

      // Publish import request message to RabbitMQ.
      exchangeRateImportMessagePublisher.publishExchangeRateImportRequested(
          new ExchangeRateImportRequestedMessage(
              event.currencySeriesId(), event.currencyCode(), event.correlationId()));

      log.info(
          "Successfully published exchange rate import requested message: currencyCode={}",
          event.currencyCode());

    } catch (Exception e) {
      // Log error - the publication stays incomplete; import reconciliation recovers it
      log.error(
          "Failed to publish exchange rate import requested message: currencyCode={}",
          event.currencyCode(),
          e);
      // Re-throw to signal Spring Modulith that event processing failed
      throw e;

    } finally {
      // Always clear MDC to prevent memory leaks in thread pool
      MDC.clear();
    }
  }

  /**
   * Handles currency updated events by publishing external messages to RabbitMQ when appropriate.
   *
   * <p>This method is invoked asynchronously by Spring Modulith after the database transaction that
   * updated the currency entity has committed. The event itself was stored in the {@code
   * event_publication} table within the same transaction.
   *
   * <p><b>Import Eligibility:</b>
   *
   * <p>{@link org.budgetanalyzer.currency.service.CurrencyService} publishes this event only when a
   * currency changes from disabled to enabled, so this listener can translate every received event
   * into an external import request.
   *
   * <p><b>Execution Flow:</b>
   *
   * <ol>
   *   <li>After the transaction commits, Spring Modulith invokes this method on a background thread
   *       with the event payload
   *   <li>Correlation ID is set in MDC for distributed tracing
   *   <li>An import request message is published to RabbitMQ via {@link
   *       ExchangeRateImportMessagePublisher}
   *   <li>On success, Spring Modulith marks the publication completed and keeps it as an audit
   *       record
   *   <li>On failure, Spring Modulith marks the publication failed and does not resubmit it; the
   *       next startup or scheduled import reconciles the series
   * </ol>
   *
   * <p><b>Note:</b>
   *
   * <p>The external message uses {@link ExchangeRateImportRequestedMessage} because from the
   * perspective of the exchange rate import service, both creating an enabled currency and enabling
   * an existing currency mean the same thing: import exchange rates for that currency series.
   *
   * @param event The currency updated domain event containing currency series details and
   *     correlation ID
   * @see CurrencyUpdatedEvent
   * @see ExchangeRateImportMessagePublisher
   */
  @ApplicationModuleListener
  void onCurrencyUpdated(CurrencyUpdatedEvent event) {
    // Set correlation ID in MDC for distributed tracing across async boundaries
    MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, event.correlationId());
    MDC.put("eventType", "currency_updated");

    try {
      log.info(
          "Processing currency updated event: currencySeriesId={}, currencyCode={}, enabled={}",
          event.currencySeriesId(),
          event.currencyCode(),
          event.enabled());

      // Publish import request message to RabbitMQ.
      exchangeRateImportMessagePublisher.publishExchangeRateImportRequested(
          new ExchangeRateImportRequestedMessage(
              event.currencySeriesId(), event.currencyCode(), event.correlationId()));

      log.info(
          "Successfully published exchange rate import requested message: currencyCode={}",
          event.currencyCode());

    } catch (Exception e) {
      // Log error - the publication stays incomplete; import reconciliation recovers it
      log.error(
          "Failed to publish exchange rate import requested message: currencyCode={}",
          event.currencyCode(),
          e);
      // Re-throw to signal Spring Modulith that event processing failed
      throw e;

    } finally {
      // Always clear MDC to prevent memory leaks in thread pool
      MDC.clear();
    }
  }
}
