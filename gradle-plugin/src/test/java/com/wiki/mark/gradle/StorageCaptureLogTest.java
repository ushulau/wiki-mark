package com.wiki.mark.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wiki.mark.sync.Log;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageCaptureLogTest {

  @TempDir Path dir;

  @Test
  void capturesMarkerAndStorageIntoHtmlFile() {
    RecordingLog delegate = new RecordingLog();
    Path out = dir.resolve("out");
    StorageCaptureLog log = new StorageCaptureLog(delegate, dir, out);

    Path page = dir.resolve("sub/01-a.md");
    log.info("--- " + page + " -> SPACE / Hello ---");
    log.info("<h1>Hello</h1>");
    log.info("unrelated progress");

    assertEquals("<h1>Hello</h1>", read(out.resolve("sub/01-a.html")));
    assertEquals(List.of("unrelated progress"), delegate.infos);
  }

  @Test
  void emptyPageWritesEmptyFile() {
    RecordingLog delegate = new RecordingLog();
    StorageCaptureLog log = new StorageCaptureLog(delegate, dir, dir.resolve("out"));

    log.info("--- " + dir.resolve("01-a.md") + " -> SPACE / Empty ---");
    log.info("(empty page)");

    assertEquals("", read(dir.resolve("out/01-a.html")));
    assertTrue(delegate.infos.isEmpty());
  }

  @Test
  void markdownExtensionAndPassthrough() {
    RecordingLog delegate = new RecordingLog();
    StorageCaptureLog log = new StorageCaptureLog(delegate, dir, dir.resolve("out"));

    log.info("--- " + dir.resolve("note.markdown") + " -> S / N ---");
    log.info("x");
    log.warn("a warning");
    log.debug("debug noise");
    log.error("boom");

    assertEquals("x", read(dir.resolve("out/note.html")));
    assertEquals(List.of("a warning"), delegate.warnings);
    assertEquals(List.of("debug noise"), delegate.debugs);
    assertEquals(List.of("boom"), delegate.errors);
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new AssertionError("missing file " + file, e);
    }
  }

  static final class RecordingLog implements Log {
    final List<String> infos = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final List<String> debugs = new ArrayList<>();
    final List<String> errors = new ArrayList<>();

    @Override
    public void debug(String message) {
      debugs.add(message);
    }

    @Override
    public void info(String message) {
      infos.add(message);
    }

    @Override
    public void warn(String message) {
      warnings.add(message);
    }

    @Override
    public void error(String message) {
      errors.add(message);
    }
  }
}
