package org.budgetanalyzer.currency.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.service.CurrencyService;

/**
 * Integration tests for RabbitMQ publisher confirms on the exchange rate import outbox publisher.
 *
 * <p><b>Focus:</b> Verifies that the Spring Modulith publication status reflects the broker outcome
 * of the import request message, driven by real currency creates against the Testcontainers
 * RabbitMQ broker.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>A routed and acked message leaves the publication {@code COMPLETED}
 *   <li>An unroutable message, returned by the broker, leaves the publication {@code FAILED}
 *   <li>A message nacked by a full {@code reject-publish} queue leaves the publication {@code
 *       FAILED}
 * </ul>
 *
 * <p>The producer binding publishes to a per-run destination, so only the queues this class
 * declares are bound to it. The application's consumer binding stays on the default destination.
 *
 * <p>The main configuration is sufficient: {@code publisher-confirm-type: correlated}, {@code
 * publisher-returns: true}, and the producer's {@code use-confirm-header: true} correlate both the
 * return and the confirm. The producer {@code error-channel-enabled} fallback was not required.
 */
class PublisherConfirmIntegrationTest extends AbstractWireMockTest {

  private static final String TEST_DESTINATION =
      TestConstants.PUBLISH_CONFIRM_TEST_DESTINATION_PREFIX + UUID.randomUUID();

  @Autowired private CurrencyService currencyService;

  @Autowired private AmqpAdmin amqpAdmin;

  private TopicExchange testExchange;

  private String testQueueName;

  /**
   * Points the producer binding at the per-run test destination.
   *
   * @param registry Spring dynamic property registry
   */
  @DynamicPropertySource
  static void configureProducerDestination(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.cloud.stream.bindings.exchangeRateImportRequested-out-0.destination",
        () -> TEST_DESTINATION);
  }

  /**
   * Removes the per-run test exchange from the reused broker container.
   *
   * @param amqpAdmin the auto-configured AMQP admin
   */
  @AfterAll
  static void deleteTestExchange(@Autowired AmqpAdmin amqpAdmin) {
    amqpAdmin.deleteExchange(TEST_DESTINATION);
  }

  /**
   * Declares the test exchange the way the binder does, so queues can bind to it before the
   * producer binding is created on the first send.
   */
  @BeforeEach
  void declareTestExchange() {
    testExchange = new TopicExchange(TEST_DESTINATION, true, false);
    amqpAdmin.declareExchange(testExchange);
    testQueueName = TestConstants.PUBLISH_CONFIRM_TEST_QUEUE_PREFIX + UUID.randomUUID();
  }

  /** Removes the queue declared by the test, if any. */
  @AfterEach
  void deleteTestQueue() {
    amqpAdmin.deleteQueue(testQueueName);
  }

  @Test
  void shouldCompletePublicationWhenBrokerConfirmsRoutedMessage() {
    // Arrange
    var queue = new Queue(testQueueName, false);
    declareQueueBoundToTestExchange(queue);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().enabled(true).build();

    // Act
    currencyService.create(currencySeries);

    // Assert
    await()
        .atMost(TestConstants.PUBLISH_OUTCOME_WAIT)
        .untilAsserted(
            () -> {
              assertThat(countCompletedPublications())
                  .as("Routed and acked message should complete the publication")
                  .isEqualTo(1L);
              assertThat(countFailedPublications())
                  .as("Routed and acked message should not fail the publication")
                  .isZero();
              assertThat(amqpAdmin.getQueueInfo(testQueueName).getMessageCount())
                  .as("Bound queue should hold the import request message")
                  .isEqualTo(1);
            });
  }

  @Test
  void shouldFailPublicationWhenBrokerReturnsUnroutableMessage() {
    // Arrange - no queue is bound to the test exchange
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().enabled(true).build();

    // Act
    currencyService.create(currencySeries);

    // Assert
    await()
        .atMost(TestConstants.PUBLISH_OUTCOME_WAIT)
        .untilAsserted(
            () -> {
              assertThat(countFailedPublications())
                  .as("Returned message should fail the publication")
                  .isEqualTo(1L);
              assertThat(countCompletedPublications())
                  .as("Returned message should not complete the publication")
                  .isZero();
            });
  }

  @Test
  void shouldFailPublicationWhenBrokerNacksMessage() {
    // Arrange - a full reject-publish queue makes the broker answer with basic.nack
    var queue =
        new Queue(
            testQueueName,
            false,
            false,
            false,
            Map.of(
                TestConstants.RABBIT_ARG_MAX_LENGTH,
                TestConstants.RABBIT_MAX_LENGTH_ZERO,
                TestConstants.RABBIT_ARG_OVERFLOW,
                TestConstants.RABBIT_OVERFLOW_REJECT_PUBLISH));
    declareQueueBoundToTestExchange(queue);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().enabled(true).build();

    // Act
    currencyService.create(currencySeries);

    // Assert
    await()
        .atMost(TestConstants.PUBLISH_OUTCOME_WAIT)
        .untilAsserted(
            () -> {
              assertThat(countFailedPublications())
                  .as("Nacked message should fail the publication")
                  .isEqualTo(1L);
              assertThat(countCompletedPublications())
                  .as("Nacked message should not complete the publication")
                  .isZero();
            });
  }

  // ===========================================================================================
  // Helper Methods
  // ===========================================================================================

  private void declareQueueBoundToTestExchange(Queue queue) {
    amqpAdmin.declareQueue(queue);
    amqpAdmin.declareBinding(
        BindingBuilder.bind(queue).to(testExchange).with(TestConstants.TOPIC_ROUTING_KEY_ALL));
  }

  private Long countCompletedPublications() {
    return testDatabaseHelper.countEventsByStatus(TestConstants.EVENT_PUBLICATION_STATUS_COMPLETED);
  }

  private Long countFailedPublications() {
    return testDatabaseHelper.countEventsByStatus(TestConstants.EVENT_PUBLICATION_STATUS_FAILED);
  }
}
