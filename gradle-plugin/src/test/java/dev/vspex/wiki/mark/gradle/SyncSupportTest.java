package dev.vspex.wiki.mark.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vspex.wiki.mark.client.ConfluenceClient;
import dev.vspex.wiki.mark.client.PageStore;
import dev.vspex.wiki.mark.model.MetaException;
import dev.vspex.wiki.mark.sync.Log;
import dev.vspex.wiki.mark.sync.SyncException;
import dev.vspex.wiki.mark.sync.SyncOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncSupportTest {

  @TempDir Path dir;

  private Path write(String relative, String text) throws Exception {
    Path file = dir.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
    return file;
  }

  @Test
  void resolveFilesSortsAndDedupes() throws Exception {
    write("02-b.md", "b");
    write("01-a.md", "a");
    write("sub/03-c.md", "c");
    write("notes.txt", "x");
    Files.createDirectories(dir.resolve("empty-dir"));

    List<Path> files = SyncSupport.resolveFiles(dir, List.of("**/*.md", "*.md"));

    assertEquals(
        List.of("01-a.md", "02-b.md", "sub/03-c.md"),
        files.stream().map(file -> SyncSupport.relativeKey(dir, file)).toList());
  }

  @Test
  void resolveFilesEmptyWhenNothingMatches() throws Exception {
    assertTrue(SyncSupport.resolveFiles(dir, List.of("**/*.md")).isEmpty());
  }

  @Test
  void resolveOnlyFilesRejectsMissingAndEscapes() throws Exception {
    write("01-a.md", "a");

    List<Path> files = SyncSupport.resolveOnlyFiles(dir, List.of("01-a.md"));
    assertEquals(1, files.size());

    SyncException missing =
        assertThrows(
            SyncException.class, () -> SyncSupport.resolveOnlyFiles(dir, List.of("nope.md")));
    assertTrue(missing.getMessage().contains("nope.md"), missing.getMessage());

    assertThrows(
        SyncException.class, () -> SyncSupport.resolveOnlyFiles(dir, List.of("../outside.md")));
  }

  @Test
  void relativeKeyFallsBackToFilenameOutsidePagesDir() {
    assertEquals("sub/01-a.md", SyncSupport.relativeKey(dir, dir.resolve("sub/01-a.md")));
    assertEquals("other.md", SyncSupport.relativeKey(dir, dir.getParent().resolve("other.md")));
  }

  @Test
  void validateRejectsBadConfig() {
    SyncOptions both = new SyncOptions();
    both.titleFromH1 = true;
    both.titleFromFilename = true;
    assertThrows(SyncException.class, () -> SyncSupport.validate(both));

    SyncOptions appearance = new SyncOptions();
    appearance.contentAppearance = "wide";
    SyncException badAppearance =
        assertThrows(SyncException.class, () -> SyncSupport.validate(appearance));
    assertTrue(
        badAppearance.getMessage().contains("contentAppearance"), badAppearance.getMessage());

    SyncOptions align = new SyncOptions();
    align.imageAlign = "diagonal";
    assertThrows(MetaException.class, () -> SyncSupport.validate(align));

    SyncOptions width = new SyncOptions();
    width.imageWidth = "zero";
    assertThrows(MetaException.class, () -> SyncSupport.validate(width));

    SyncSupport.validate(new SyncOptions());
  }

  @Test
  void effectiveParentsRootDefaultsToPagesDir() {
    String pages = dir.toAbsolutePath().normalize().toString();
    assertEquals(pages, SyncSupport.effectiveParentsRoot(dir, "", true));
    assertEquals("custom", SyncSupport.effectiveParentsRoot(dir, "custom", true));
    assertEquals("", SyncSupport.effectiveParentsRoot(dir, "", false));
  }

  @Test
  void digestIsStableAndHeaderSensitive() throws Exception {
    Path file = write("01-a.md", "<!-- Space: S -->\n\nBody.\n");
    String first = SyncSupport.digestPage(dir, file);
    assertEquals(first, SyncSupport.digestPage(dir, file));

    write("01-a.md", "<!-- Space: T -->\n\nBody.\n");
    String changed = SyncSupport.digestPage(dir, file);
    assertTrue(!changed.equals(first) && changed.matches("[0-9a-f]{64}"), changed);
  }

  @Test
  void digestIncludesMmdTextAndIgnoresMissingMmd() throws Exception {
    write("01-a.md", "See ![](diagrams/flow.png) and ![](diagrams/gone.png).\n");
    String withoutMmd =
        SyncSupport.digestPage(dir, write("01-a.md", "See ![](diagrams/flow.png).\n"));

    write("diagrams/flow.mmd", "graph TD\n  A-->B\n");
    String withMmd = SyncSupport.digestPage(dir, dir.resolve("01-a.md"));
    assertTrue(!withMmd.equals(withoutMmd));

    write("diagrams/flow.mmd", "graph TD\n  A-->C\n");
    assertTrue(!SyncSupport.digestPage(dir, dir.resolve("01-a.md")).equals(withMmd));
  }

  @Test
  void stateRoundTripIsSorted() throws Exception {
    Path state = SyncSupport.stateFile(dir);
    Map<String, String> saved = new LinkedHashMap<>();
    saved.put("b.md", "bb");
    saved.put("a.md", "aa");
    SyncSupport.saveState(state, saved);

    assertEquals(saved, SyncSupport.loadState(state));
    String text = Files.readString(state, StandardCharsets.ISO_8859_1);
    assertTrue(text.indexOf("a.md=") < text.indexOf("b.md="), text);
    assertTrue(SyncSupport.loadState(dir.resolve("missing.properties")).isEmpty());
  }

  @Test
  void importLegacyHashesMapsBasenames() throws Exception {
    write("01-a.md", "a");
    write("sub/02-b.md", "b");
    write("sub/dup.md", "1");
    write("other/dup.md", "2");
    write(".published-hashes", "aa 01-a.md\nbb 02-b.md\ncc dup.md\ndd ghost.md\nnot-a-hash-line\n");

    Map<String, String> imported = SyncSupport.importLegacyHashes(dir);

    assertEquals(Map.of("01-a.md", "aa", "sub/02-b.md", "bb"), imported);
    assertTrue(SyncSupport.importLegacyHashes(dir.resolve("empty")).isEmpty());
  }

  @Test
  void outOfBatchWarnsForUnselectedExistingTargets() throws Exception {
    write("01-a.md", "See [b](02-b.md) and [c](03-c.md) and [x](99-x.md).\n");
    write("02-b.md", "b");

    List<String> warnings = SyncSupport.outOfBatchWarnings(dir, List.of(dir.resolve("01-a.md")));

    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("01-a.md links to 02-b.md"), warnings.get(0));
    assertTrue(!warnings.get(0).contains("03-c.md"), warnings.get(0));
    assertTrue(!warnings.get(0).contains("99-x.md"), warnings.get(0));
  }

  @Test
  void outOfBatchWarnsOnlyWhenTargetExists() throws Exception {
    write("01-a.md", "See [b](02-b.md).\n");
    write("02-b.md", "b");

    List<String> warnings = SyncSupport.outOfBatchWarnings(dir, List.of(dir.resolve("01-a.md")));

    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("01-a.md links to 02-b.md"), warnings.get(0));

    assertTrue(
        SyncSupport.outOfBatchWarnings(dir, List.of(dir.resolve("01-a.md"), dir.resolve("02-b.md")))
            .isEmpty());
  }

  @Test
  void createClientWarnsWhenInsecure() {
    RecordingLog log = new RecordingLog();
    PageStore client = SyncSupport.createClient("https://wiki", "", "token", null, "", true, log);
    assertTrue(client instanceof ConfluenceClient);
    assertEquals(1, log.warnings.size());
    assertTrue(log.warnings.get(0).contains("DISABLED"), log.warnings.get(0));

    RecordingLog quiet = new RecordingLog();
    SyncSupport.createClient("https://wiki", "", "token", null, "", false, quiet);
    assertTrue(quiet.warnings.isEmpty());
  }

  static final class RecordingLog implements Log {
    final List<String> infos = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    @Override
    public void debug(String message) {}

    @Override
    public void info(String message) {
      infos.add(message);
    }

    @Override
    public void warn(String message) {
      warnings.add(message);
    }

    @Override
    public void error(String message) {}
  }
}
