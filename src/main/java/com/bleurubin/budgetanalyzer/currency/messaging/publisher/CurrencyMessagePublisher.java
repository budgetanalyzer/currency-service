package com.bleurubin.budgetanalyzer.currency.messaging.publisher;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

import com.bleurubin.budgetanalyzer.currency.messaging.message.CurrencyCreatedMessage;

/**
 * Publisher for currency-related messages.
 *
 * <p>Encapsulates Spring Cloud Stream message publishing, keeping service layer decoupled from
 * messaging infrastructure.
 */
@Component
public class CurrencyMessagePublisher {

  private static final String CURRENCY_CREATED_BINDING = "currencyCreated-out-0";

  private final StreamBridge streamBridge;

  public CurrencyMessagePublisher(StreamBridge streamBridge) {
    this.streamBridge = streamBridge;
  }

  /**
   * Publish a message indicating a new currency series was created.
   *
   * @param message The currency created message
   */
  public void publishCurrencyCreated(CurrencyCreatedMessage message) {
    streamBridge.send(CURRENCY_CREATED_BINDING, message);
  }
}
