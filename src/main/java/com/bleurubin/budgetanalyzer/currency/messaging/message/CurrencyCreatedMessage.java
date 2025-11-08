package com.bleurubin.budgetanalyzer.currency.messaging.message;

/**
 * Message published when a new currency series is created.
 *
 * <p>Triggers asynchronous exchange rate import for the newly created currency.
 */
public record CurrencyCreatedMessage(Long currencySeriesId, String currencyCode) {}
