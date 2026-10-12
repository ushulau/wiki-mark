package dev.vspex.wiki.mark.sync;

/**
 * Logging seam keeping core free of Gradle (or any other) logging API.
 *
 * <p>Messages arrive unprefixed; each backend formats them. The CLI prints {@code WARNING ...} and
 * {@code DEBUG ...} to stderr like the Python tool; the Gradle task maps the levels to its logger.
 */
public interface Log {
  /**
   * Logs a diagnostic message.
   *
   * @param message the message
   */
  void debug(String message);

  /**
   * Logs a progress message.
   *
   * @param message the message
   */
  void info(String message);

  /**
   * Logs a recoverable problem.
   *
   * @param message the message
   */
  void warn(String message);

  /**
   * Logs a failure.
   *
   * @param message the message
   */
  void error(String message);
}
