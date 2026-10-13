package dev.vspex.wiki.mark.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI tests: flags, validation, file resolution, and compile-only runs. */
class MainTest {

  static final class Capture {
    final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    String outText() {
      return outBytes.toString(StandardCharsets.UTF_8);
    }

    String errText() {
      return errBytes.toString(StandardCharsets.UTF_8);
    }
  }

  static int run(Capture capture, String... args) {
    return Main.run(args, capture.out, capture.err, Map.of());
  }

  static Path writeDoc(Path dir, String name) throws Exception {
    Path path = dir.resolve(name);
    Files.writeString(
        path, "<!-- Space: DOC -->\n<!-- Title: Hello -->\n\nSome body.\n", StandardCharsets.UTF_8);
    return path;
  }

  @Test
  void helpPrintsUsage() {
    Capture capture = new Capture();
    assertEquals(0, run(capture, "--help"));
    assertTrue(capture.outText().contains("Usage:"));
    assertTrue(capture.outText().contains("--files"));
    assertTrue(capture.outText().contains("CONFLUENCE_TOKEN"));
  }

  @Test
  void versionPrints() {
    Capture capture = new Capture();
    assertEquals(0, run(capture, "--version"));
    assertTrue(capture.outText().contains("0.3.1"));
  }

  @Test
  void syncAliasAccepted() {
    Capture capture = new Capture();
    assertEquals(0, run(capture, "sync", "--help"));
    assertTrue(capture.outText().contains("Usage:"));
  }

  @Test
  void compileOnlyRun(@TempDir Path tmp) throws Exception {
    Path doc = writeDoc(tmp, "page.md");
    Capture capture = new Capture();
    assertEquals(0, run(capture, "--files", doc.toString(), "--compile-only"));
    assertTrue(capture.outText().contains("-> DOC / Hello"));
    assertTrue(capture.outText().contains("Some body."));
  }

  @Test
  void equalsFormParses(@TempDir Path tmp) throws Exception {
    Path doc = writeDoc(tmp, "page.md");
    Capture capture = new Capture();
    assertEquals(0, run(capture, "--files=" + doc, "--compile-only"));
    assertTrue(capture.outText().contains("-> DOC / Hello"));
  }

  @Test
  void missingFilesFlagFails() {
    Capture capture = new Capture();
    assertEquals(1, run(capture, "--compile-only"));
    assertTrue(capture.errText().contains("no files to sync"));
  }

  @Test
  void noMatchFailsWithoutCi(@TempDir Path tmp) {
    String pattern = tmp + "/nothing/*.md";
    Capture capture = new Capture();
    assertEquals(1, run(capture, "--files", pattern, "--compile-only"));
    assertTrue(capture.errText().contains("no files match"));
    Capture ci = new Capture();
    assertEquals(0, run(ci, "--files", pattern, "--compile-only", "--ci"));
    assertTrue(ci.outText().contains("No changed pages to publish."));
  }

  @Test
  void unknownOptionFails() {
    Capture capture = new Capture();
    assertEquals(1, run(capture, "--bogus"));
    assertTrue(capture.errText().contains("unknown option '--bogus'"));
  }

  @Test
  void missingValueFails() {
    Capture capture = new Capture();
    assertEquals(1, run(capture, "--space"));
    assertTrue(capture.errText().contains("needs a value"));
  }

  @Test
  void titleFlagsMutuallyExclusive(@TempDir Path tmp) throws Exception {
    Path doc = writeDoc(tmp, "page.md");
    Capture capture = new Capture();
    assertEquals(
        1,
        run(
            capture,
            "--files",
            doc.toString(),
            "--compile-only",
            "--title-from-h1",
            "--title-from-filename"));
    assertTrue(capture.errText().contains("mutually exclusive"));
  }

  @Test
  void tokenRequired(@TempDir Path tmp) throws Exception {
    Path doc = writeDoc(tmp, "page.md");
    Capture capture = new Capture();
    assertEquals(1, run(capture, "--files", doc.toString(), "--base-url", "https://x"));
    assertTrue(capture.errText().contains("token is required"));
  }

  @Test
  void passwordDashRejected(@TempDir Path tmp) throws Exception {
    Path doc = writeDoc(tmp, "page.md");
    Capture capture = new Capture();
    assertEquals(
        1, run(capture, "--files", doc.toString(), "--base-url", "https://x", "--password", "-"));
    assertTrue(capture.errText().contains("not supported"));
  }

  @Test
  void globRootAndRunRoot() {
    assertEquals("docs", Main.globRoot("docs/**/*.md"));
    assertEquals("docs", Main.globRoot("docs/*.md"));
    assertEquals("", Main.globRoot("a.md"));
    assertEquals("", Main.globRoot(""));
    assertEquals("docs", Main.runRoot(List.of("docs/**/*.md"), ""));
    assertEquals("override", Main.runRoot(List.of("docs/**/*.md"), "override"));
    assertEquals("", Main.runRoot(List.of("a.md"), ""));
  }
}
