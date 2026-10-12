package dev.vspex.wiki.mark.gradle;

import dev.vspex.wiki.mark.sync.Log;
import org.gradle.api.logging.Logger;

/** Maps core log levels onto the Gradle logger; messages pass through unprefixed. */
final class GradleLog implements Log {
  private final Logger logger;

  GradleLog(Logger logger) {
    this.logger = logger;
  }

  @Override
  public void debug(String message) {
    logger.debug(message);
  }

  @Override
  public void info(String message) {
    logger.lifecycle(message);
  }

  @Override
  public void warn(String message) {
    logger.warn(message);
  }

  @Override
  public void error(String message) {
    logger.error(message);
  }
}
