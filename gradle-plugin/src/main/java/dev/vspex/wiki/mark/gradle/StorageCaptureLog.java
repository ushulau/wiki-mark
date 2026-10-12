package dev.vspex.wiki.mark.gradle;

import dev.vspex.wiki.mark.sync.Log;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Captures the compile branch's per-page output into storage files instead of the log.
 *
 * <p>Mirrors {@code PageSyncer}'s compile sequence exactly: a {@code --- <path> -> ... ---} marker
 * info line immediately followed by one info line carrying the storage XHTML. The marker and its
 * storage are written to {@code <outputDir>/<relative>.html} and swallowed; every other message
 * passes through to the delegate.
 */
final class StorageCaptureLog implements Log {
  private final Log delegate;
  private final Path pagesDir;
  private final Path outputDir;
  private Path pendingFile;

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
    Path marker = markerFile(message);
    if (marker != null) {
      pendingFile = marker;
      return;
    }
    if (pendingFile != null) {
      Path file = pendingFile;
      pendingFile = null;
      writeStorage(file, message.equals("(empty page)") ? "" : message);
      return;
    }
    delegate.info(message);
  }

  @Override
  public void warn(String message) {
    delegate.warn(message);
  }

  @Override
  public void error(String message) {
    delegate.error(message);
  }

  private Path markerFile(String message) {
    if (!message.startsWith("--- ") || !message.endsWith(" ---")) {
      return null;
    }
    String inner = message.substring("--- ".length(), message.length() - " ---".length());
    int arrow = inner.indexOf(" -> ");
    if (arrow < 0) {
      return null;
    }
    String relative = SyncSupport.relativeKey(pagesDir, Path.of(inner.substring(0, arrow)));
    String lower = relative.toLowerCase(Locale.ROOT);
    String html =
        lower.endsWith(".md") || lower.endsWith(".markdown")
            ? relative.substring(0, relative.lastIndexOf('.')) + ".html"
            : relative + ".html";
    return outputDir.resolve(html);
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
