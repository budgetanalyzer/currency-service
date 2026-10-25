package org.budgetanalyzer.currency.domain.event;

import org.budgetanalyzer.currency.service.CurrencyService;

/**
 * Domain event published when a currency series is enabled.
 *
 * <p>This event is published within the same database transaction as the currency series update.
 * Spring Modulith stores a publication for this event in the event_publication table as part of
 * that transaction, implementing the transactional outbox pattern.
 *
 * <p><b>Event Flow:</b>
 *
 * <ol>
 *   <li>CurrencyService updates currency entity from disabled to enabled and publishes this event
 *   <li>Spring Modulith persists event to event_publication table (same transaction)
 *   <li>Database transaction commits (currency + event both saved atomically)
 *   <li>Spring Modulith asynchronously processes event via @ApplicationModuleListener
 *   <li>Event listener publishes external message to RabbitMQ to trigger exchange rate import
 *   <li>Event marked as completed in event_publication table
 *   <li>On failure, the publication is marked failed and stays incomplete; nothing resubmits it.
 *       The next startup or daily scheduled import reconciles the series from its latest stored
 *       rate
 * </ol>
 *
 * <p><b>Messaging Decision Logic:</b>
 *
 * <p>{@link CurrencyService} publishes this event only when a currency changes from disabled to
 * enabled. This avoids persisting and processing outbox events for disabled currencies that cannot
 * produce import requests.
 *
 * <p><b>Use Cases:</b>
 *
 * <ul>
 *   <li><b>Enable currency:</b> When toggled from disabled to enabled, external message is
 *       published to trigger exchange rate import
 *   <li><b>Disable currency:</b> When toggled from enabled to disabled, no event is published
 * </ul>
 *
 * @param currencySeriesId The unique identifier of the updated currency series
 * @param currencyCode The ISO 4217 currency code (e.g., "USD", "EUR", "JPY")
 * @param enabled Whether the currency is enabled for exchange rate access
 * @param correlationId The correlation ID from the originating HTTP request for distributed tracing
 *     across async boundaries. This allows tracing the entire flow from HTTP request through async
 *     event processing to external message publishing.
 * @see CurrencyService#update(Long, boolean)
 * @see org.budgetanalyzer.currency.messaging.listener
 *     .MessagingEventListener#onCurrencyUpdated(CurrencyUpdatedEvent)
 */
public record CurrencyUpdatedEvent(
    Long currencySeriesId, String currencyCode, boolean enabled, String correlationId) {}
