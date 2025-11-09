package com.bleurubin.budgetanalyzer.currency.messaging.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.bleurubin.budgetanalyzer.currency.domain.event.CurrencyCreatedEvent;
import com.bleurubin.budgetanalyzer.currency.domain.event.CurrencyUpdatedEvent;
import com.bleurubin.budgetanalyzer.currency.messaging.message.CurrencyCreatedMessage;
import com.bleurubin.budgetanalyzer.currency.messaging.publisher.CurrencyMessagePublisher;
import com.bleurubin.service.http.CorrelationIdFilter;

/**
 * Event listener that bridges domain events to external messaging (RabbitMQ).
 *
 * <p>This component listens for internal domain events published by the service layer and
 * translates them into external messages for inter-service communication. It uses Spring Modulith's
 * {@code @ApplicationModuleListener} annotation to enable the transactional outbox pattern.
 *
 * <p><b>Transactional Outbox Pattern:</b>
 *
 * <p>The {@code @ApplicationModuleListener} annotation provides guaranteed message delivery through
 * the following mechanism:
 *
 * <ol>
 *   <li><b>Event Persistence:</b> When a domain event is published within a transaction, Spring
 *       Modulith automatically persists it to the {@code event_publication} table in the same
 *       database transaction as the business data (e.g., currency series creation).
 *   <li><b>Atomic Commit:</b> Both the business entity and event are committed atomically - either
 *       both succeed or both fail. This eliminates the "dual-write" problem where data is saved but
 *       the message fails to publish.
 *   <li><b>Async Processing:</b> After the transaction commits, Spring Modulith asynchronously
 *       polls the {@code event_publication} table for unpublished events and invokes listener
 *       methods on a background thread.
 *   <li><b>External Publishing:</b> The listener method publishes the message to RabbitMQ. If
 *       publishing fails, Spring Modulith automatically retries until success.
 *   <li><b>Completion Tracking:</b> Once successfully published, the event is marked as completed
 *       in the database (but not deleted, allowing audit trail and replay).
 * </ol>
 *
 * <p><b>Benefits Over Direct Message Publishing:</b>
 *
 * <ul>
 *   <li><b>100% Guaranteed Delivery:</b> Events survive application crashes since they're persisted
 *       in the database before the transaction commits
 *   <li><b>Exactly-Once Semantics:</b> No duplicate or lost messages due to timing issues between
 *       database commit and message publishing
 *   <li><b>Decoupled Services:</b> Service layer publishes domain events without knowledge of
 *       external messaging infrastructure
 *   <li><b>Async HTTP Responses:</b> HTTP requests return immediately after database commit without
 *       waiting for RabbitMQ publishing
 *   <li><b>Automatic Retries:</b> Failed message publishing is retried automatically by Spring
 *       Modulith
 *   <li><b>Event Replay:</b> Events can be manually replayed from the database if needed for
 *       recovery or debugging
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
 * <p>Exceptions thrown from listener methods are caught by Spring Modulith, which will retry event
 * processing according to its retry policy. Failed events remain in the {@code event_publication}
 * table with error details for troubleshooting.
 *
 * <p><b>Configuration:</b>
 *
 * <p>Spring Modulith event processing behavior can be configured in {@code application.yml}:
 *
 * <pre>
 * spring:
 *   modulith:
 *     events:
 *       republish-outstanding-events-on-restart: true
 *       delete-completion-after: 30d  # Retain completed events for audit trail
 * </pre>
 *
 * @see CurrencyCreatedEvent
 * @see CurrencyMessagePublisher
 * @see org.springframework.modulith.events.ApplicationModuleListener
 */
@Component
public class MessagingEventListener {

  private static final Logger log = LoggerFactory.getLogger(MessagingEventListener.class);

  private final CurrencyMessagePublisher messagePublisher;

  /**
   * Constructs a new MessagingEventListener.
   *
   * @param messagePublisher The publisher for external currency-related messages
   */
  public MessagingEventListener(CurrencyMessagePublisher messagePublisher) {
    this.messagePublisher = messagePublisher;
  }

  /**
   * Handles currency created events by publishing external messages to RabbitMQ.
   *
   * <p>This method is invoked asynchronously by Spring Modulith after the database transaction that
   * persisted the currency entity has committed. The event itself was stored in the {@code
   * event_publication} table within the same transaction, guaranteeing delivery.
   *
   * <p><b>Enabled Check:</b>
   *
   * <p>This method only publishes external messages if the currency is enabled. This prevents
   * triggering exchange rate imports for disabled currencies. The domain event is always published
   * (truthful state representation), but the messaging layer decides whether to notify external
   * systems.
   *
   * <p><b>Execution Flow:</b>
   *
   * <ol>
   *   <li>Spring Modulith detects unpublished event in {@code event_publication} table
   *   <li>Invokes this method on background thread with event payload
   *   <li>Correlation ID is set in MDC for distributed tracing
   *   <li>If currency is enabled, external message is published to RabbitMQ via {@link
   *       CurrencyMessagePublisher}
   *   <li>On success, Spring Modulith marks event as completed in database
   *   <li>On failure, Spring Modulith retries according to retry policy
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
   * <p>Exceptions are logged and caught by Spring Modulith's retry mechanism. The event remains in
   * the {@code event_publication} table for retry. MDC is always cleared in the finally block to
   * prevent memory leaks in the thread pool.
   *
   * <p><b>Thread Safety:</b>
   *
   * <p>This method is thread-safe as each invocation operates on independent event data. MDC is
   * thread-local and cleared after processing.
   *
   * @param event The currency created domain event containing currency series details and
   *     correlation ID
   * @see CurrencyCreatedEvent
   * @see CurrencyMessagePublisher#publishCurrencyCreated(CurrencyCreatedMessage)
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

      // Only publish external message if currency is enabled
      if (!event.enabled()) {
        log.info(
            "Skipping external message publication for disabled currency: currencyCode={}",
          event.currencyCode());
        return;
      }

      // Publish external message to RabbitMQ
      messagePublisher.publishCurrencyCreated(
          new CurrencyCreatedMessage(
              event.currencySeriesId(), event.currencyCode(), event.correlationId()));

      log.info(
          "Successfully published currency created message: currencyCode={}",
       event.currencyCode());

    } catch (Exception e) {
      // Log error - Spring Modulith will retry event processing
      log.error(
          "Failed to publish currency created message: currencyCode={}",
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
   * event_publication} table within the same transaction, guaranteeing delivery.
   *
   * <p><b>Enabled Check:</b>
   *
   * <p>This method only publishes external messages if the currency is enabled. When a currency is
   * toggled from disabled to enabled, this triggers exchange rate imports. When toggled from
   * enabled to disabled, no external message is published (no need to import rates for disabled
   * currency).
   *
   * <p><b>Execution Flow:</b>
   *
   * <ol>
   *   <li>Spring Modulith detects unpublished event in {@code event_publication} table
   *   <li>Invokes this method on background thread with event payload
   *   <li>Correlation ID is set in MDC for distributed tracing
   *   <li>If currency is enabled, external message is published to RabbitMQ via {@link
   *       CurrencyMessagePublisher}
   *   <li>On success, Spring Modulith marks event as completed in database
   *   <li>On failure, Spring Modulith retries according to retry policy
   * </ol>
   *
   * <p><b>Note:</b>
   *
   * <p>The external message uses {@link CurrencyCreatedMessage} (not a separate
   * CurrencyUpdatedMessage) because from the perspective of the exchange rate import service, an
   * enabled currency is a "new" currency that needs rate import, regardless of whether it was just
   * created or toggled from disabled to enabled.
   *
   * @param event The currency updated domain event containing currency series details and
   *     correlation ID
   * @see CurrencyUpdatedEvent
   * @see CurrencyMessagePublisher#publishCurrencyCreated(CurrencyCreatedMessage)
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

      // Only publish external message if currency is enabled
      if (!event.enabled()) {
        log.info(
            "Skipping external message publication for disabled currency: currencyCode={}",
            event.currencyCode());
        return;
      }

      // Publish external message to RabbitMQ (reuse CurrencyCreatedMessage)
      messagePublisher.publishCurrencyCreated(
          new CurrencyCreatedMessage(
              event.currencySeriesId(), event.currencyCode(), event.correlationId()));

      log.info(
          "Successfully published currency updated message: currencyCode={}",
          event.currencyCode());

    } catch (Exception e) {
      // Log error - Spring Modulith will retry event processing
      log.error(
          "Failed to publish currency updated message: currencyCode={}",
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
