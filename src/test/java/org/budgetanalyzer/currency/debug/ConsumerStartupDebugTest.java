package org.budgetanalyzer.currency.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.CurrencyService;

/**
 * Diagnostic test to verify RabbitMQ consumer startup behavior with and without
 * {@code @TestPropertySource} configuration.
 *
 * <p><b>Purpose:</b> Determine if {@code @TestPropertySource(properties =
 * "spring.cloud.stream.bindings.importExchangeRates-in-0.consumer.auto-startup=false")} actually
 * disables the consumer.
 *
 * <p><b>Expected Behavior:</b>
 *
 * <ul>
 *   <li>When consumer is enabled: Messages should be consumed and processed
 *   <li>When consumer is disabled: Messages should NOT be consumed
 * </ul>
 *
 * <p><b>How to Use:</b>
 *
 * <ol>
 *   <li>Run {@code testConsumerEnabledByDefault()} and check logs for consumer startup
 *   <li>Run {@code testConsumerDisabled()} and verify consumer does NOT start
 *   <li>Compare log outputs to confirm @TestPropertySource behavior
 * </ol>
 */
@SpringBootTest
public class ConsumerStartupDebugTest extends AbstractWireMockTest {

  private static final Logger logger = LoggerFactory.getLogger(ConsumerStartupDebugTest.class);

  @Autowired private CurrencyService currencyService;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @BeforeEach
  void setUp() {
    super.resetDatabaseAndWireMock();

    // Stub FRED API with sample data
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
  }

  // ===========================================================================================
  // Test: Consumer Enabled By Default
  // ===========================================================================================

  @Test
  @DisplayName("Consumer ENABLED by default - should process messages")
  void shouldProcessMessagesWhenConsumerEnabledByDefault() {
    logger.info("DEBUG TEST: Starting test with consumer ENABLED");

    // Arrange - Create an enabled currency via service (triggers domain event → listener → message
    // → consumer)
    var eurSeries = CurrencySeriesTestBuilder.defaultEur().enabled(true).build();

    // Act - Use service to create currency (this publishes domain event automatically)
    var created = currencyService.create(eurSeries);
    logger.info("DEBUG TEST: Created enabled currency with ID: {}", created.getId());

    // Assert - Wait for consumer to process message and import exchange rates
    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              long count = exchangeRateRepository.countByCurrencySeries(created);
              logger.info("DEBUG TEST: Exchange rates count: {}", count);
              assertThat(count)
                  .as(
                      "Consumer should be ENABLED and process the message, "
                          + "creating exchange rates from FRED")
                  .isEqualTo(8); // FredApiStubs.stubSuccessWithSampleData = 8 rates
            });
  }

  // ===========================================================================================
  // Test: Consumer Disabled via @TestPropertySource
  // ===========================================================================================

  /**
   * Nested class to test consumer with auto-startup disabled.
   *
   * <p>Uses {@code @TestPropertySource} to disable consumer at startup. If this property works, the
   * consumer should NOT start and messages should NOT be processed.
   */
  @Nested
  @TestPropertySource(
      properties = {
        "spring.cloud.stream.bindings.importExchangeRates-in-0.consumer.auto-startup=false"
      })
  class ConsumerDisabledTests {

    @Test
    @DisplayName("Consumer DISABLED via @TestPropertySource - should NOT process messages")
    void shouldNotProcessMessagesWhenConsumerDisabled() {
      logger.info("DEBUG TEST: Starting test with consumer DISABLED");

      // Arrange - Create an enabled currency via service
      var eurSeries = CurrencySeriesTestBuilder.defaultEur().enabled(true).build();

      // Act - Use service to create currency (publishes domain event → listener → message)
      var created = currencyService.create(eurSeries);
      logger.info("DEBUG TEST: Created enabled currency with ID: {}", created.getId());

      // Wait for potential consumer processing (should NOT happen because consumer is disabled)
      try {
        Thread.sleep(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      // Assert - Exchange rates should NOT be created because consumer is disabled
      long exchangeRateCount = exchangeRateRepository.countByCurrencySeries(created);
      logger.info("DEBUG TEST: Exchange rates count after wait: {}", exchangeRateCount);

      assertThat(exchangeRateCount)
          .as(
              "Consumer should be DISABLED via @TestPropertySource, "
                  + "so no exchange rates should be created")
          .isEqualTo(0);
    }
  }
}
