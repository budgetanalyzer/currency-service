package org.budgetanalyzer.currency.messaging.publisher;

/**
 * Thrown when the message broker does not confirm that it accepted and routed a published message.
 *
 * <p>Covers a binding that rejects the send, a broker nack, a returned (unroutable) message, a
 * confirm timeout, and a failed confirm future.
 */
public class MessagePublishException extends RuntimeException {

  /**
   * Creates an exception with a message.
   *
   * @param message The failure description
   */
  public MessagePublishException(String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and a cause.
   *
   * @param message The failure description
   * @param cause The underlying cause
   */
  public MessagePublishException(String message, Throwable cause) {
    super(message, cause);
  }
}
