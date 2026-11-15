package org.budgetanalyzer.currency.fixture;

import java.util.List;
import java.util.Objects;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

import org.budgetanalyzer.currency.config.FailOpenCache;

/**
 * Captures what {@link FailOpenCache} logs for absorbed cache failures.
 *
 * <p>While the capture runs, the {@link FailOpenCache} logger is set to {@code DEBUG}, so the stack
 * traces it logs at that level are captured too. Start the capture before the operation under test
 * and close it afterwards, for example in {@code @BeforeEach} and {@code @AfterEach}.
 */
public final class FailOpenCacheLogCapture implements AutoCloseable {

  private final Logger failOpenCacheLogger = (Logger) LoggerFactory.getLogger(FailOpenCache.class);

  private final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();

  private final Level previousLevel = failOpenCacheLogger.getLevel();

  private FailOpenCacheLogCapture() {
    listAppender.start();
    failOpenCacheLogger.addAppender(listAppender);
    failOpenCacheLogger.setLevel(Level.DEBUG);
  }

  /**
   * Starts capturing fail-open cache log events.
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
    return messages(Level.WARN);
  }

  /**
   * Returns the formatted messages of the captured events at the given level, in logging order.
   *
   * @param level the level of the events to return
   * @return captured messages at that level
   */
  public List<String> messages(Level level) {
    return eventsAt(level).stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /**
   * Returns the class names of the throwables attached to captured events at the given level, in
   * logging order; events without a throwable are skipped.
   *
   * @param level the level of the events to inspect
   * @return class names of the attached throwables
   */
  public List<String> throwableClassNames(Level level) {
    return eventsAt(level).stream()
        .map(ILoggingEvent::getThrowableProxy)
        .filter(Objects::nonNull)
        .map(IThrowableProxy::getClassName)
        .toList();
  }

  /** Stops capturing, detaches the appender, and restores the logger level. */
  @Override
  public void close() {
    failOpenCacheLogger.setLevel(previousLevel);
    failOpenCacheLogger.detachAppender(listAppender);
    listAppender.stop();
  }

  private List<ILoggingEvent> eventsAt(Level level) {
    return List.copyOf(listAppender.list).stream()
        .filter(loggingEvent -> loggingEvent.getLevel() == level)
        .toList();
  }
}
