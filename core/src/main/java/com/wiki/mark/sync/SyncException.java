package com.wiki.mark.sync;

/**
 * A sync run or file failed: a missing attachment pattern, an empty selection error, a run summary,
 * a CLI usage error.
 *
 * <p>Port of the sync-level {@code ConfigError} uses. Unchecked like the other domain exceptions.
 */
public class SyncException extends RuntimeException {
  public SyncException(String message) {
    super(message);
  }

  public SyncException(String message, Throwable cause) {
    super(message, cause);
  }
}
