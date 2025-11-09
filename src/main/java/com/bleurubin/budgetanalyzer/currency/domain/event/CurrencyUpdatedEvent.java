package com.bleurubin.budgetanalyzer.currency.domain.event;

/**
 * Domain event published when a currency series is successfully updated.
 *
 * <p>This event is published within the same database transaction as the currency series update.
 * Spring Modulith automatically stores this event in the event_publication table for guaranteed
 * delivery via the transactional outbox pattern.
 *
 * <p><b>Event Flow:</b>
 *
 * <ol>
 *   <li>CurrencyService updates currency entity and publishes this event
 *   <li>Spring Modulith persists event to event_publication table (same transaction)
 *   <li>Database transaction commits (currency + event both saved atomically)
 *   <li>Spring Modulith asynchronously processes event via @ApplicationModuleListener
 *   <li>Event listener checks if currency is enabled before publishing external message
 *   <li>If enabled, external message published to RabbitMQ to trigger exchange rate import
 *   <li>Event marked as completed in event_publication table
 * </ol>
 *
 * <p><b>Messaging Decision Logic:</b>
 *
 * <p>The {@code enabled} field allows the messaging layer to decide whether to publish external
 * messages. This separation keeps the domain layer truthful (always publishes events for actual
 * state changes) while allowing the messaging layer to implement business rules (only notify
 * external systems when currency is enabled).
 *
 * <p><b>Use Cases:</b>
 *
 * <ul>
 *   <li><b>Enable currency:</b> When toggled from disabled to enabled, external message is
 *       published to trigger exchange rate import
 *   <li><b>Disable currency:</b> When toggled from enabled to disabled, event is recorded but no
 *       external message is published (no need to import rates for disabled currency)
 * </ul>
 *
 * @param currencySeriesId The unique identifier of the updated currency series
 * @param currencyCode The ISO 4217 currency code (e.g., "USD", "EUR", "JPY")
 * @param enabled Whether the currency is enabled for exchange rate access
 * @param correlationId The correlation ID from the originating HTTP request for distributed tracing
 *     across async boundaries. This allows tracing the entire flow from HTTP request through async
 *     event processing to external message publishing.
 * @see com.bleurubin.budgetanalyzer.currency.service.CurrencyService#update(Long, boolean)
 * @see com.bleurubin.budgetanalyzer.currency.messaging.listener.MessagingEventListener#
 *     onCurrencyUpdated(CurrencyUpdatedEvent)
 */
public record CurrencyUpdatedEvent(
    Long currencySeriesId, String currencyCode, boolean enabled, String correlationId) {}
