package org.budgetanalyzer.currency.messaging.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;

import org.budgetanalyzer.currency.fixture.TestConstants;

/**
 * Unit tests for {@link ExchangeRateImportMessagePublisher#awaitBrokerConfirm}.
 *
 * <p>Uses real {@link CorrelationData} and {@link ReturnedMessage} values to cover each outcome of
 * the publisher-confirm wait without a broker.
 */
class ExchangeRateImportMessagePublisherTest {

  @Test
  void shouldReturnNormallyWhenBrokerAcks() {
    var correlationData = new CorrelationData(TestConstants.PUBLISH_CORRELATION_ID);
    correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));

    assertThatCode(
            () ->
                ExchangeRateImportMessagePublisher.awaitBrokerConfirm(
                    correlationData, TestConstants.PUBLISH_CONFIRM_TIMEOUT))
        .doesNotThrowAnyException();
  }

  @Test
  void shouldThrowWhenAckedMessageWasReturned() {
    var correlationData = new CorrelationData(TestConstants.PUBLISH_CORRELATION_ID);
    correlationData.setReturned(
        new ReturnedMessage(
            new Message(new byte[0]),
            TestConstants.AMQP_REPLY_CODE_NO_ROUTE,
            TestConstants.AMQP_REPLY_TEXT_NO_ROUTE,
            TestConstants.EXCHANGE_RATE_IMPORT_REQUESTED_EXCHANGE,
            TestConstants.PUBLISH_ROUTING_KEY));
    correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));

    assertThatThrownBy(
            () ->
                ExchangeRateImportMessagePublisher.awaitBrokerConfirm(
                    correlationData, TestConstants.PUBLISH_CONFIRM_TIMEOUT))
        .isInstanceOf(MessagePublishException.class)
        .hasMessageContaining(TestConstants.AMQP_REPLY_TEXT_NO_ROUTE)
        .hasMessageContaining(String.valueOf(TestConstants.AMQP_REPLY_CODE_NO_ROUTE))
        .hasMessageContaining(TestConstants.EXCHANGE_RATE_IMPORT_REQUESTED_EXCHANGE)
        .hasMessageContaining(TestConstants.PUBLISH_ROUTING_KEY);
  }

  @Test
  void shouldThrowWhenBrokerNacks() {
    var correlationData = new CorrelationData(TestConstants.PUBLISH_CORRELATION_ID);
    correlationData
        .getFuture()
        .complete(new CorrelationData.Confirm(false, TestConstants.PUBLISH_NACK_REASON));

    assertThatThrownBy(
            () ->
                ExchangeRateImportMessagePublisher.awaitBrokerConfirm(
                    correlationData, TestConstants.PUBLISH_CONFIRM_TIMEOUT))
        .isInstanceOf(MessagePublishException.class)
        .hasMessageContaining(TestConstants.PUBLISH_NACK_REASON);
  }

  @Test
  void shouldThrowWhenConfirmDoesNotArriveWithinTimeout() {
    var correlationData = new CorrelationData(TestConstants.PUBLISH_CORRELATION_ID);

    assertThatThrownBy(
            () ->
                ExchangeRateImportMessagePublisher.awaitBrokerConfirm(
                    correlationData, TestConstants.PUBLISH_CONFIRM_TIMEOUT_SHORT))
        .isInstanceOf(MessagePublishException.class)
        .hasMessageContaining(TestConstants.PUBLISH_CORRELATION_ID)
        .hasCauseInstanceOf(TimeoutException.class);
  }

  @Test
  void shouldThrowWithCauseWhenConfirmFutureFails() {
    var correlationData = new CorrelationData(TestConstants.PUBLISH_CORRELATION_ID);
    var confirmFailure = new IllegalStateException(TestConstants.PUBLISH_NACK_REASON);
    correlationData.getFuture().completeExceptionally(confirmFailure);

    assertThatThrownBy(
            () ->
                ExchangeRateImportMessagePublisher.awaitBrokerConfirm(
                    correlationData, TestConstants.PUBLISH_CONFIRM_TIMEOUT))
        .isInstanceOf(MessagePublishException.class)
        .extracting(Throwable::getCause)
        .isSameAs(confirmFailure);
  }

  @Test
  void shouldRestoreInterruptFlagWhenInterruptedWhileWaiting() {
    var correlationData = new CorrelationData(TestConstants.PUBLISH_CORRELATION_ID);
    Thread.currentThread().interrupt();

    try {
      assertThatThrownBy(
              () ->
                  ExchangeRateImportMessagePublisher.awaitBrokerConfirm(
                      correlationData, TestConstants.PUBLISH_CONFIRM_TIMEOUT))
          .isInstanceOf(MessagePublishException.class)
          .hasCauseInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
