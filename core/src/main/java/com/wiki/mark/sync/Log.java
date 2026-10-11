package com.wiki.mark.sync;

/**
 * Logging seam keeping core free of Gradle (or any other) logging API.
 *
 * <p>Messages arrive unprefixed; each backend formats them. The CLI prints {@code WARNING ...} and
 * {@code DEBUG ...} to stderr like the Python tool; the Gradle task maps the levels to its logger.
 */
public interface Log {
  void debug(String message);

  void info(String message);

  void warn(String message);

  void error(String message);
}
