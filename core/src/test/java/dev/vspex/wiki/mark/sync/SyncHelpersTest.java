package dev.vspex.wiki.mark.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vspex.wiki.mark.model.Meta;
import dev.vspex.wiki.mark.model.MetaException;
import dev.vspex.wiki.mark.model.SyncAction;
import dev.vspex.wiki.mark.model.SyncResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for the small sync helpers: paths, layout, hashes, globs, URLs, the report. */
class SyncHelpersTest {

  @Test
  void parentsFromPath(@TempDir Path tmp) throws Exception {
    Path root = tmp.resolve("docs");
    Path leaf = root.resolve("guide/intro");
    Files.createDirectories(leaf);
    Path page = leaf.resolve("page.md");
    Files.writeString(page, "x", StandardCharsets.UTF_8);
    PageSyncer.PathParents nested = PageSyncer.parentsFromPath(page, root.toString());
    assertEquals(List.of("Guide", "Intro"), nested.parents());
    assertFalse(nested.index());
    Path index = leaf.resolve("index.md");
    Files.writeString(index, "x", StandardCharsets.UTF_8);
    PageSyncer.PathParents owned = PageSyncer.parentsFromPath(index, root.toString());
    assertEquals(List.of("Guide"), owned.parents());
    assertTrue(owned.index());
    // A file outside the root contributes no parents.
    PageSyncer.PathParents outside =
        PageSyncer.parentsFromPath(page, tmp.resolve("elsewhere").toString());
    assertEquals(List.of(), outside.parents());
  }

  @Test
  void parentsFromPathWithoutRootTakesTwoDirectories(@TempDir Path tmp) throws Exception {
    Path page = tmp.resolve("a/b/page.md");
    Files.createDirectories(page.getParent());
    Files.writeString(page, "x", StandardCharsets.UTF_8);
    PageSyncer.PathParents parents = PageSyncer.parentsFromPath(page, "");
    assertEquals(2, parents.parents().size());
    assertFalse(parents.index());
  }

  @Test
  void applyLayout() {
    assertEquals("<p>x</p>", Layout.applyLayout("<p>x</p>", "", ""));
    assertEquals("<p>x</p>", Layout.applyLayout("<p>x</p>", "other", "<p>side</p>"));
    String wrapped = Layout.applyLayout("<p>x</p>", "article", "<p>side</p>");
    assertTrue(wrapped.startsWith("<ac:layout>"));
    assertTrue(wrapped.contains("<p>x</p>") && wrapped.contains("<p>side</p>"));
  }

  @Test
  void contentHashRoundTrip() {
    String digest = ContentHash.contentHash("<p>x</p>");
    assertEquals(40, digest.length());
    assertEquals(digest, ContentHash.readContentHash(ContentHash.formatVersionMessage("", digest)));
    assertEquals(
        digest, ContentHash.readContentHash(ContentHash.formatVersionMessage("hello", digest)));
    assertEquals(digest, ContentHash.readContentHash("prefix [v" + digest + "]"));
    assertEquals("", ContentHash.readContentHash("no hash here"));
    assertEquals("", ContentHash.readContentHash(null));
  }

  @Test
  void resolveImageAlign() {
    assertEquals("center", PageSyncer.resolveImageAlign("center", ""));
    assertEquals("right", PageSyncer.resolveImageAlign("center", "right"));
    assertEquals("", PageSyncer.resolveImageAlign("", ""));
    MetaException e =
        assertThrows(MetaException.class, () -> PageSyncer.resolveImageAlign("top", ""));
    assertTrue(e.getMessage().contains("unknown image-align"));
  }

  @Test
  void resolveImageDimension() {
    assertEquals("800", PageSyncer.resolveImageDimension("image-height", "800", ""));
    assertEquals("600", PageSyncer.resolveImageDimension("image-width", "800", "600"));
    assertEquals("", PageSyncer.resolveImageDimension("image-width", "", ""));
    assertEquals("007", PageSyncer.resolveImageDimension("image-width", "007", ""));
    for (String bad : List.of("0", "-5", "12px", "1.5")) {
      MetaException e =
          assertThrows(
              MetaException.class, () -> PageSyncer.resolveImageDimension("image-width", bad, ""));
      assertTrue(e.getMessage().contains("expected a positive number of pixels"));
    }
  }

  @Test
  void findIncludes() {
    assertEquals(
        List.of("a.md", "b/c.md"),
        PageSyncer.findIncludes("<!-- Include: a.md -->\n<!--Include: b/c.md-->"));
    assertEquals(List.of(), PageSyncer.findIncludes("no includes here"));
  }

  @Test
  void warnUnsupportedHeaders() {
    Meta meta = new Meta();
    meta.folders = new ArrayList<>(List.of("F"));
    meta.properties = new LinkedHashMap<>(Map.of("k", "v"));
    meta.order = 1;
    PageSyncerTest.RecordingLog log = new PageSyncerTest.RecordingLog();
    PageSyncer.warnUnsupportedHeaders("p.md", meta, "<!-- Include: a.md -->", log);
    assertEquals(4, log.warns.size());
    assertTrue(log.warnContains("Folder headers are not supported"));
    assertTrue(log.warnContains("Property/properties are not applied"));
    assertTrue(log.warnContains("Order header is recorded but sibling ordering is not applied"));
    assertTrue(log.warnContains("Include 'a.md' is not expanded"));
    PageSyncerTest.RecordingLog quiet = new PageSyncerTest.RecordingLog();
    PageSyncer.warnUnsupportedHeaders("p.md", new Meta(), "plain", quiet);
    assertEquals(List.of(), quiet.warns);
  }

  @Test
  void matchGlobFindsLiteralMetacharacters(@TempDir Path tmp) throws Exception {
    Path weird = tmp.resolve("weird[1].png");
    Files.write(weird, new byte[] {1});
    // A glob class never matches itself; the literal file still resolves.
    assertEquals(List.of(weird), PageSyncer.matchGlob(tmp, "weird[1].png"));
  }

  @Test
  void matchGlobSortsMatchesDirectoriesAndRecurses(@TempDir Path tmp) throws Exception {
    Files.write(tmp.resolve("b.png"), new byte[] {1});
    Files.write(tmp.resolve("a.png"), new byte[] {2});
    Files.createDirectories(tmp.resolve("sub/deep"));
    Files.write(tmp.resolve("sub/deep/c.png"), new byte[] {3});
    assertEquals(
        List.of(tmp.resolve("a.png"), tmp.resolve("b.png")), PageSyncer.matchGlob(tmp, "*.png"));
    assertEquals(List.of(tmp.resolve("sub")), PageSyncer.matchGlob(tmp, "sub"));
    assertEquals(
        List.of(tmp.resolve("a.png"), tmp.resolve("b.png"), tmp.resolve("sub/deep/c.png")),
        PageSyncer.matchGlob(tmp, "**/*.png"));
    // An absolute pattern ignores the source dir.
    assertEquals(
        List.of(tmp.resolve("a.png"), tmp.resolve("b.png")),
        PageSyncer.matchGlob(tmp.resolve("sub"), tmp + "/*.png"));
    assertEquals(List.of(), PageSyncer.matchGlob(tmp, "nope/*.png"));
  }

  @Test
  void segmentMatchesClasses() {
    assertTrue(PageSyncer.segmentMatches("*.png", "a.png"));
    assertTrue(PageSyncer.segmentMatches("?.png", "a.png"));
    assertFalse(PageSyncer.segmentMatches("?.png", "ab.png"));
    assertTrue(PageSyncer.segmentMatches("[ab].png", "a.png"));
    assertFalse(PageSyncer.segmentMatches("[ab].png", "c.png"));
    assertTrue(PageSyncer.segmentMatches("[a-c].png", "b.png"));
    assertTrue(PageSyncer.segmentMatches("[!a].png", "b.png"));
    assertFalse(PageSyncer.segmentMatches("[!a].png", "a.png"));
    assertTrue(PageSyncer.segmentMatches("[]].png", "].png"));
    assertTrue(PageSyncer.segmentMatches("[a-].png", "-.png"));
    assertTrue(PageSyncer.segmentMatches("a[bc", "a[bc"));
    assertFalse(PageSyncer.segmentMatches("a[bc", "abc"));
    assertTrue(PageSyncer.segmentMatches("{a,b}.png", "{a,b}.png"));
    assertFalse(PageSyncer.segmentMatches("{a,b}.png", "a.png"));
  }

  @Test
  void matchGlobIgnoresCaseOnWindows(@TempDir Path tmp) throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        System.getProperty("os.name", "").startsWith("Windows"));
    Files.write(tmp.resolve("a.png"), new byte[] {1});
    assertEquals(List.of(tmp.resolve("a.png")), PageSyncer.matchGlob(tmp, "*.PNG"));
  }

  @Test
  void pageUrl() {
    Map<String, Object> page = Map.of("_links", Map.of("base", "https://x/", "webui", "/pages/7"));
    assertEquals("https://x/pages/7", PageSyncer.pageUrl("https://fallback", page));
    assertEquals("https://fallback", PageSyncer.pageUrl("https://fallback", Map.of()));
  }

  @Test
  void throwIfFailedSummarizes(@TempDir Path tmp) {
    SyncResult good =
        new SyncResult(SyncAction.CREATED, "good.md", "DOC", "G", "1", "https://x/1", "");
    SyncResult bad =
        new SyncResult(SyncAction.FAILED, "bad.md", "", "", "", "", "page title is not set");
    RunReport report = new RunReport(List.of(bad, good));
    assertEquals(List.of(bad), report.failures());
    SyncException summary = assertThrows(SyncException.class, report::throwIfFailed);
    assertEquals(
        "1 of 2 files failed:\n  bad.md: page title is not set\n  good.md: created",
        summary.getMessage());
    new RunReport(List.of(good)).throwIfFailed();
  }
}
