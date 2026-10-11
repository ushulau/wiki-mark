package com.wiki.mark.sync;

/**
 * A sync run or file failed: a missing attachment pattern, an empty selection error, a run summary,
 * a CLI usage error.
 *
 * <p>Port of the sync-level {@code ConfigError} uses. Unchecked like the other domain exceptions.
 */
public class SyncException extends RuntimeException {
  /**
   * Creates a failure.
   *
   * @param message the failure message
   */
  public SyncException(String message) {
    super(message);
  }

  /**
   * Creates a failure wrapping its cause.
   *
   * @param message the failure message
   * @param cause the cause
   */
  public SyncException(String message, Throwable cause) {
    super(message, cause);
  }
}
