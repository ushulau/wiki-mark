package dev.vspex.wiki.mark.model;

/**
 * A document's metadata is malformed (typo header, bad value, ...).
 *
 * <p>Port of {@code mark.metadata.MetaError}. Unchecked so parser signatures stay clean.
 */
public class MetaException extends RuntimeException {
  /**
   * Creates a failure.
   *
   * @param message the failure message
   */
  public MetaException(String message) {
    super(message);
  }

  /**
   * Creates a failure wrapping its cause.
   *
   * @param message the failure message
   * @param cause the cause
   */
  public MetaException(String message, Throwable cause) {
    super(message, cause);
  }
}
