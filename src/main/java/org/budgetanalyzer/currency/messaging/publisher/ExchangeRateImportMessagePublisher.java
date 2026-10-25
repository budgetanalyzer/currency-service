package org.budgetanalyzer.currency.messaging.publisher;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import org.budgetanalyzer.currency.config.CurrencyServiceProperties;
import org.budgetanalyzer.currency.messaging.message.ExchangeRateImportRequestedMessage;

/**
 * Publisher for exchange rate import messages.
 *
 * <p>Encapsulates Spring Cloud Stream message publishing, keeping service layer decoupled from
 * messaging infrastructure.
 *
 * <p><b>Publisher Confirms:</b>
 *
 * <p>Each message carries a unique {@link CorrelationData} under {@link
 * AmqpHeaders#PUBLISH_CONFIRM_CORRELATION}. The producer binding uses {@code use-confirm-header},
 * so the binder publishes the message as mandatory and completes the correlation when the broker
 * confirms it. After sending, the publisher waits at most {@code
 * currency-service.messaging.publish-confirm-timeout-seconds} for the confirm. It returns normally
 * only when the broker acked and routed the message, and throws {@link MessagePublishException}
 * when the binding rejects the send, the broker nacks or returns the message, the wait times out,
 * or the confirm future fails. The calling outbox listener then fails, so Spring Modulith marks the
 * publication {@code FAILED} instead of {@code COMPLETED}.
 *
 * <p>The wait blocks the calling thread. The only caller is {@code MessagingEventListener}, which
 * Spring Modulith runs on its task-executor thread after the business transaction commits, never on
 * an HTTP request thread.
 */
@Component
public class ExchangeRateImportMessagePublisher {

  private static final Logger logger =
      LoggerFactory.getLogger(ExchangeRateImportMessagePublisher.class);
  private static final String EXCHANGE_RATE_IMPORT_REQUESTED_BINDING =
      "exchangeRateImportRequested-out-0";

  private final StreamBridge streamBridge;
  private final Duration publishConfirmTimeout;

  /**
   * Constructs a new ExchangeRateImportMessagePublisher.
   *
   * @param streamBridge The Spring Cloud Stream bridge used to send messages
   * @param currencyServiceProperties The service properties holding the confirm timeout
   */
  public ExchangeRateImportMessagePublisher(
      StreamBridge streamBridge, CurrencyServiceProperties currencyServiceProperties) {
    this.streamBridge = streamBridge;
    this.publishConfirmTimeout =
        Duration.ofSeconds(
            currencyServiceProperties.getMessaging().getPublishConfirmTimeoutSeconds());
  }

  /**
   * Publish a message requesting exchange rate import for an enabled currency series.
   *
   * <p>Returns only after the broker confirms and routes the message.
   *
   * @param message The exchange rate import requested message
   * @throws MessagePublishException if the binding rejects the send, or the broker nacks, returns,
   *     or does not confirm the message within the configured timeout
   */
  public void publishExchangeRateImportRequested(ExchangeRateImportRequestedMessage message) {
    logger.info(
        "Publishing exchange rate import requested message: binding={}, currencySeriesId={}, "
            + "currencyCode={}",
        EXCHANGE_RATE_IMPORT_REQUESTED_BINDING,
        message.currencySeriesId(),
        message.currencyCode());

    var correlationData = new CorrelationData(UUID.randomUUID().toString());
    var outboundMessage =
        MessageBuilder.withPayload(message)
            .setHeader(AmqpHeaders.PUBLISH_CONFIRM_CORRELATION, correlationData)
            .build();

    if (!streamBridge.send(EXCHANGE_RATE_IMPORT_REQUESTED_BINDING, outboundMessage)) {
      throw new MessagePublishException(
          "Binding rejected message: binding="
              + EXCHANGE_RATE_IMPORT_REQUESTED_BINDING
              + ", correlationId="
              + correlationData.getId());
    }

    awaitBrokerConfirm(correlationData, publishConfirmTimeout);

    logger.debug(
        "Broker confirmed exchange rate import requested message: correlationId={}, "
            + "currencyCode={}",
        correlationData.getId(),
        message.currencyCode());
  }

  /**
   * Waits for the broker confirm of a correlated publish and verifies that it was acked and routed.
   *
   * <p>The returned message is checked after the confirm completes because the broker returns an
   * unroutable mandatory message and then acks it.
   *
   * @param correlationData The correlation attached to the published message
   * @param timeout The maximum time to wait for the confirm
   * @throws MessagePublishException if the message was returned or nacked, the wait timed out or
   *     was interrupted, or the confirm future completed exceptionally
   */
  static void awaitBrokerConfirm(CorrelationData correlationData, Duration timeout) {
    var confirm = waitForConfirm(correlationData, timeout);

    var returnedMessage = correlationData.getReturned();
    if (returnedMessage != null) {
      throw new MessagePublishException(
          "Broker returned unroutable message: correlationId="
              + correlationData.getId()
              + ", replyCode="
              + returnedMessage.getReplyCode()
              + ", replyText="
              + returnedMessage.getReplyText()
              + ", exchange="
              + returnedMessage.getExchange()
              + ", routingKey="
              + returnedMessage.getRoutingKey());
    }

    if (!confirm.ack()) {
      throw new MessagePublishException(
          "Broker nacked message: correlationId="
              + correlationData.getId()
              + ", reason="
              + confirm.reason());
    }
  }

  private static CorrelationData.Confirm waitForConfirm(
      CorrelationData correlationData, Duration timeout) {
    try {
      return correlationData.getFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MessagePublishException(
          "Interrupted while waiting for broker confirm: correlationId=" + correlationData.getId(),
          e);
    } catch (TimeoutException e) {
      throw new MessagePublishException(
          "Broker did not confirm message within "
              + timeout
              + ": correlationId="
              + correlationData.getId(),
          e);
    } catch (ExecutionException e) {
      throw new MessagePublishException(
          "Broker confirm failed: correlationId=" + correlationData.getId(), e.getCause());
    }
  }
}
