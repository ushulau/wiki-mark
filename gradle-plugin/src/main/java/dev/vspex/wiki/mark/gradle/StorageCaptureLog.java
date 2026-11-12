package dev.vspex.wiki.mark.gradle;

import dev.vspex.wiki.mark.sync.Log;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Captures typed compile output without interpreting storage as logging control messages. */
final class StorageCaptureLog implements Log {
  private final Log delegate;
  private final Path pagesDir;
  private final Path outputDir;

  StorageCaptureLog(Log delegate, Path pagesDir, Path outputDir) {
    this.delegate = delegate;
    this.pagesDir = pagesDir;
    this.outputDir = outputDir;
  }

  @Override
  public void debug(String message) {
    delegate.debug(message);
  }

  @Override
  public void info(String message) {
    delegate.info(message);
  }

  @Override
  public void storage(Path source, String space, String title, String storage) {
    String relative = SyncSupport.relativeKey(pagesDir, source);
    String lower = relative.toLowerCase(Locale.ROOT);
    String html =
        lower.endsWith(".md") || lower.endsWith(".markdown")
            ? relative.substring(0, relative.lastIndexOf('.')) + ".html"
            : relative + ".html";
    writeStorage(outputDir.resolve(html), storage);
  }

  @Override
  public void warn(String message) {
    delegate.warn(message);
  }

  @Override
  public void error(String message) {
    delegate.error(message);
  }

  private void writeStorage(Path file, String storage) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, storage, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file + ": " + e.getMessage(), e);
    }
  }
}
