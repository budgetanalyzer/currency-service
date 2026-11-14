package org.budgetanalyzer.currency.fixture;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.budgetanalyzer.currency.config.FailOpenCache;

/**
 * Captures the warnings that {@link FailOpenCache} logs for absorbed cache failures.
 *
 * <p>Start the capture before the operation under test and close it afterwards, for example in
 * {@code @BeforeEach} and {@code @AfterEach}.
 */
public final class FailOpenCacheLogCapture implements AutoCloseable {

  private final Logger failOpenCacheLogger = (Logger) LoggerFactory.getLogger(FailOpenCache.class);

  private final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();

  private FailOpenCacheLogCapture() {
    listAppender.start();
    failOpenCacheLogger.addAppender(listAppender);
  }

  /**
   * Starts capturing fail-open cache warnings.
   *
   * @return the running capture
   */
  public static FailOpenCacheLogCapture start() {
    return new FailOpenCacheLogCapture();
  }

  /**
   * Returns the formatted messages of the captured {@code WARN} events, in logging order.
   *
   * @return captured warning messages
   */
  public List<String> warnings() {
    return List.copyOf(listAppender.list).stream()
        .filter(loggingEvent -> loggingEvent.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  /** Stops capturing and detaches the appender. */
  @Override
  public void close() {
    failOpenCacheLogger.detachAppender(listAppender);
    listAppender.stop();
  }
}
