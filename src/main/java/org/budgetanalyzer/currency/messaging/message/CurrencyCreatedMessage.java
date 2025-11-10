package org.budgetanalyzer.currency.messaging.message;

/**
 * Message published when a new currency series is created.
 *
 * <p>Triggers asynchronous exchange rate import for the newly created currency.
 *
 * @param currencySeriesId The ID of the currency series
 * @param currencyCode The ISO 4217 currency code
 * @param correlationId The correlation ID for distributed tracing
 */
public record CurrencyCreatedMessage(
    Long currencySeriesId, String currencyCode, String correlationId) {}
