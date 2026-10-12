package dev.vspex.wiki.mark.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vspex.wiki.mark.client.EnsureResult;
import dev.vspex.wiki.mark.client.PageStore;
import dev.vspex.wiki.mark.convert.MarkdownConverter;
import dev.vspex.wiki.mark.model.MetaException;
import dev.vspex.wiki.mark.model.SyncAction;
import dev.vspex.wiki.mark.model.SyncResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sync flow tests against an in-memory {@link PageStore}. Ports the {@code sync_file} and {@code
 * run_sync} cases from {@code test_cli.py} (minus target-url mode and output formats, both dropped
 * in v0.1).
 */
class PageSyncerTest {

  static final String DOC = "<!-- Space: DOC -->\n<!-- Title: Hello -->\n\nSome body.\n";
  static final String ROCKET = new String(Character.toChars(0x1F680));

  /** In-memory page store. Port of the {@code FakeClient} in {@code test_cli.py}. */
  static final class FakePageStore implements PageStore {
    final String base = "https://confluence.test";
    final Map<List<String>, Map<String, Object>> pages = new LinkedHashMap<>();
    final List<List<Object>> writes = new ArrayList<>();
    int nextId = 100;

    @Override
    public String baseUrl() {
      return base;
    }

    Map<String, Object> seed(String space, String title, String type, String body) {
      return seed(space, title, type, body, null);
    }

    Map<String, Object> seed(
        String space, String title, String type, String body, String parentId) {
      nextId++;
      String id = String.valueOf(nextId);
      Map<String, Object> page = new LinkedHashMap<>();
      page.put("id", id);
      page.put("type", type);
      page.put("title", title);
      page.put("space", space);
      Map<String, Object> version = new LinkedHashMap<>();
      version.put("number", 1L);
      version.put("message", "");
      page.put("version", version);
      List<Object> ancestors = new ArrayList<>();
      if (parentId != null) {
        ancestors.add(new LinkedHashMap<>(Map.of("id", parentId)));
      }
      page.put("ancestors", ancestors);
      Map<String, Object> links = new LinkedHashMap<>();
      links.put("base", base);
      links.put("webui", "/pages/" + id);
      page.put("_links", links);
      Map<String, Object> storage = new LinkedHashMap<>();
      storage.put("value", body);
      page.put("body", new LinkedHashMap<>(Map.of("storage", storage)));
      pages.put(List.of(space, title, type), page);
      return new LinkedHashMap<>(page);
    }

    @Override
    public Map<String, Object> findPage(String space, String title, String type) {
      Map<String, Object> found = pages.get(List.of(space, title, type));
      return found == null ? null : new LinkedHashMap<>(found);
    }

    @Override
    public Map<String, Object> getPageById(String pageId) {
      for (Map<String, Object> page : pages.values()) {
        if (page.get("id").equals(pageId)) {
          return new LinkedHashMap<>(page);
        }
      }
      throw new AssertionError("unknown page id " + pageId);
    }

    @Override
    public Map<String, Object> createPage(
        String space, String title, String body, String type, String parentId) {
      writes.add(List.of("create", title));
      return seed(space, title, type, body, parentId);
    }

    @Override
    public Map<String, Object> updatePage(
        Map<String, Object> page,
        String content,
        boolean minorEdit,
        String versionMessage,
        String appearance,
        String emoji) {
      Map<String, Object> kwargs = new LinkedHashMap<>();
      kwargs.put("minorEdit", minorEdit);
      kwargs.put("versionMessage", versionMessage);
      kwargs.put("appearance", appearance);
      kwargs.put("emoji", emoji);
      writes.add(List.of("update", String.valueOf(page.get("title")), content, kwargs));
      Map<String, Object> stored = null;
      for (Map<String, Object> candidate : pages.values()) {
        if (candidate.get("id").equals(page.get("id"))) {
          stored = candidate;
        }
      }
      if (stored == null) {
        throw new AssertionError("unknown page id " + page.get("id"));
      }
      Map<String, Object> storage = new LinkedHashMap<>();
      storage.put("value", content);
      stored.put("body", new LinkedHashMap<>(Map.of("storage", storage)));
      long number = (Long) ((Map<?, ?>) stored.get("version")).get("number");
      Map<String, Object> version = new LinkedHashMap<>();
      version.put("number", number + 1);
      version.put("message", versionMessage);
      stored.put("version", version);
      return new LinkedHashMap<>(stored);
    }

    @Override
    public EnsureResult ensureAttachment(String pageId, Path localPath, String comment) {
      writes.add(List.of("attach", pageId, localPath.toString()));
      return new EnsureResult(Map.of("id", "a1"), true);
    }

    @Override
    public void syncLabels(String pageId, List<String> labels, boolean append) {
      writes.add(List.of("labels", pageId, new ArrayList<>(labels), append));
    }

    long count(String kind) {
      return writes.stream().filter(write -> write.get(0).equals(kind)).count();
    }
  }

  static final class RecordingLog implements Log {
    final List<String> debugs = new ArrayList<>();
    final List<String> infos = new ArrayList<>();
    final List<String> warns = new ArrayList<>();
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
      warns.add(message);
    }

    @Override
    public void error(String message) {
      errors.add(message);
    }

    boolean infoContains(String fragment) {
      return infos.stream().anyMatch(line -> line.contains(fragment));
    }

    boolean warnContains(String fragment) {
      return warns.stream().anyMatch(line -> line.contains(fragment));
    }
  }

  static Path writeDoc(Path dir, String name, String text) throws IOException {
    Path path = dir.resolve(name);
    Files.createDirectories(path.getParent());
    Files.writeString(path, text, StandardCharsets.UTF_8);
    return path;
  }

  static SyncResult sync(Path file, SyncOptions opts, PageStore client, RecordingLog log)
      throws IOException {
    return PageSyncer.syncFile(file, opts, client, Map.of(), log);
  }

  @Test
  void compileOnlyPrintsBannerAndStorage(@TempDir Path tmp) throws Exception {
    Path path = writeDoc(tmp, "page.md", DOC);
    SyncOptions opts = new SyncOptions();
    opts.compileOnly = true;
    RecordingLog log = new RecordingLog();
    SyncResult result = sync(path, opts, null, log);
    assertEquals(SyncAction.COMPILED, result.action());
    assertEquals("Hello", result.title());
    assertEquals("--- " + path + " -> DOC / Hello ---", log.infos.get(0));
    assertTrue(log.infoContains("Some body."));
  }

  @Test
  void compileOnlyNeedsNoTitleAndPrintsEmptyPage(@TempDir Path tmp) throws Exception {
    Path path = writeDoc(tmp, "page.md", "<!-- Space: DOC -->\n\n");
    SyncOptions opts = new SyncOptions();
    opts.compileOnly = true;
    RecordingLog log = new RecordingLog();
    SyncResult result = sync(path, opts, null, log);
    assertEquals(SyncAction.COMPILED, result.action());
    assertTrue(log.infoContains("(no title)"));
    assertTrue(log.infos.contains("(empty page)"));
  }

  @Test
  void skippedWhenNotSynchronized(@TempDir Path tmp) throws Exception {
    String text =
        "<!-- Space: DOC -->\n<!-- Title: Skip -->\n<!-- Synchronized: false -->\n\nBody.\n";
    Path path = writeDoc(tmp, "page.md", text);
    SyncOptions opts = new SyncOptions();
    opts.compileOnly = true;
    RecordingLog log = new RecordingLog();
    SyncResult result = sync(path, opts, null, log);
    assertEquals(SyncAction.SKIPPED, result.action());
    assertEquals("Synchronized is false", result.reason());
    assertTrue(log.infoContains("skipped " + path + ": Synchronized is false"));
  }

  @Test
  void dryRunIsReadOnly(@TempDir Path tmp) throws Exception {
    Path path = writeDoc(tmp, "page.md", DOC);
    FakePageStore client = new FakePageStore();
    SyncOptions opts = new SyncOptions();
    opts.space = "DOC";
    opts.dryRun = true;
    opts.parents = new ArrayList<>(List.of("P1", "P2"));
    RecordingLog log = new RecordingLog();
    SyncResult result = sync(path, opts, client, log);
    assertEquals(SyncAction.DRY_RUN, result.action());
    assertEquals(List.of(), client.writes);
    assertTrue(log.infoContains("dry-run " + path + ": would publish 'Hello' (new)"));
    assertTrue(log.infoContains("under 'P2' in space DOC"));
    assertTrue(log.infoContains("Some body."));
  }

  @Test
  void dryRunPrintsStorageWithoutLayout(@TempDir Path tmp) throws Exception {
    String text =
        "<!-- Space: DOC -->\n<!-- Title: Hello -->\n<!-- Layout: article -->\n"
            + "<!-- Sidebar: <p>side</p> -->\n\nSome body.\n";
    Path path = writeDoc(tmp, "page.md", text);
    SyncOptions opts = new SyncOptions();
    opts.dryRun = true;
    RecordingLog log = new RecordingLog();
    SyncResult result = sync(path, opts, new FakePageStore(), log);
    assertEquals(SyncAction.DRY_RUN, result.action());
    assertTrue(log.infoContains("Some body."));
    assertTrue(log.infos.stream().noneMatch(line -> line.contains("<ac:layout>")));
  }

  @Test
  void createWithParentsAttachmentsLabels(@TempDir Path tmp) throws Exception {
    Files.createDirectories(tmp.resolve("assets"));
    Files.write(tmp.resolve("assets/a.png"), new byte[] {1, 2, 3});
    String text =
        "<!-- Space: DOC -->\n<!-- Title: Hello -->\n"
            + "<!-- Label: l1 -->\n<!-- Label: l2 -->\n"
            + "<!-- Attachment: assets/a.png -->\n\nBody ![](assets/a.png).\n";
    Path path = writeDoc(tmp, "page.md", text);
    FakePageStore client = new FakePageStore();
    SyncOptions opts = new SyncOptions();
    opts.parents = new ArrayList<>(List.of("P1"));
    RecordingLog log = new RecordingLog();
    SyncResult result = sync(path, opts, client, log);
    assertEquals(SyncAction.CREATED, result.action());
    assertEquals(2, client.count("create"));
    assertTrue(
        client.writes.contains(List.of("labels", result.pageId(), List.of("l1", "l2"), false)));
    assertEquals(1, client.count("attach"));
    assertTrue(result.url().startsWith("https://confluence.test/pages/"));
  }

  @Test
  void attachmentGlobMatchesSeveralFiles(@TempDir Path tmp) throws Exception {
    Files.createDirectories(tmp.resolve("assets"));
    Files.write(tmp.resolve("assets/b.png"), new byte[] {1});
    Files.write(tmp.resolve("assets/c.png"), new byte[] {2});
    String text =
        "<!-- Space: DOC -->\n<!-- Title: Hello -->\n"
            + "<!-- Attachment: assets/*.png -->\n\nBody.\n";
    Path path = writeDoc(tmp, "page.md", text);
    FakePageStore client = new FakePageStore();
    SyncResult result = sync(path, new SyncOptions(), client, new RecordingLog());
    assertEquals(SyncAction.CREATED, result.action());
    assertEquals(2, client.count("attach"));
  }

  @Test
  void buildPageTitleMap(@TempDir Path tmp) throws Exception {
    Path a = writeDoc(tmp, "a.md", "<!-- Space: DOC -->\n<!-- Title: A -->\n\nBody.\n");
    Path b = writeDoc(tmp, "b.md", "<!-- Space: DOC -->\n<!-- Title: B -->\n\nBody.\n");
    Map<String, String> titles = PageSyncer.buildPageTitleMap(List.of(a, b), new SyncOptions());
    assertEquals(
        Map.of(
            a.toAbsolutePath().normalize().toString(), "A",
            b.toAbsolutePath().normalize().toString(), "B"),
        titles);
  }

  @Test
  void buildPageTitleMapSkipsUntitledAndMissing(@TempDir Path tmp) throws Exception {
    Path untitled = writeDoc(tmp, "u.md", "Just a body, no headers.\n");
    Map<String, String> titles =
        PageSyncer.buildPageTitleMap(
            List.of(untitled, tmp.resolve("missing.md")), new SyncOptions());
    assertEquals(Map.of(), titles);
  }

  @Test
  void resolvesCrossFilePageLink(@TempDir Path tmp) throws Exception {
    writeDoc(tmp, "b.md", "<!-- Space: DOC -->\n<!-- Title: Target Page -->\n\nBody.\n");
    Path a =
        writeDoc(tmp, "a.md", "<!-- Space: DOC -->\n<!-- Title: A -->\n\nSee [the other](b.md).\n");
    SyncOptions opts = new SyncOptions();
    opts.compileOnly = true;
    List<Path> files = List.of(a, tmp.resolve("b.md"));
    Map<String, String> pageTitles = PageSyncer.buildPageTitleMap(files, opts);
    RecordingLog log = new RecordingLog();
    PageSyncer.syncFile(a, opts, null, pageTitles, log);
    assertTrue(log.infoContains("<ri:page ri:content-title=\"Target Page\" />"));
  }

  @Test
  void updateUnchangedAndEmoji(@TempDir Path tmp) throws Exception {
    Path path = writeDoc(tmp, "page.md", DOC);
    FakePageStore client = new FakePageStore();
    SyncOptions opts = new SyncOptions();
    RecordingLog log = new RecordingLog();
    SyncResult first = sync(path, opts, client, log);
    assertEquals(SyncAction.CREATED, first.action());
    client.writes.clear();
    SyncResult second = sync(path, opts, client, log);
    assertEquals(SyncAction.UNCHANGED, second.action());
    assertEquals(0, client.count("update"));
    assertEquals(first.pageId(), second.pageId());
    String changed =
        DOC.replace("Some body.", "Changed.")
            .replace(
                "<!-- Title: Hello -->", "<!-- Title: Hello -->\n<!-- Emoji: " + ROCKET + " -->");
    Files.writeString(path, changed, StandardCharsets.UTF_8);
    assertEquals(SyncAction.UPDATED, sync(path, opts, client, log).action());
    List<Object> update =
        client.writes.stream().filter(w -> w.get(0).equals("update")).findFirst().orElseThrow();
    assertEquals(ROCKET, ((Map<?, ?>) update.get(3)).get("emoji"));
  }

  @Test
  void unchangedStillSyncsAttachmentsAndLabels(@TempDir Path tmp) throws Exception {
    Files.createDirectories(tmp.resolve("assets"));
    Files.write(tmp.resolve("assets/a.png"), new byte[] {1});
    String text =
        "<!-- Space: DOC -->\n<!-- Title: Hello -->\n<!-- Label: l1 -->\n"
            + "<!-- Attachment: assets/a.png -->\n\nBody.\n";
    Path path = writeDoc(tmp, "page.md", text);
    FakePageStore client = new FakePageStore();
    SyncOptions opts = new SyncOptions();
    RecordingLog log = new RecordingLog();
    assertEquals(SyncAction.CREATED, sync(path, opts, client, log).action());
    client.writes.clear();
    assertEquals(SyncAction.UNCHANGED, sync(path, opts, client, log).action());
    assertEquals(1, client.count("attach"));
    assertEquals(1, client.count("labels"));
  }

  @Test
  void changesOnlyComparesVersionTag(@TempDir Path tmp) throws Exception {
    Path path = writeDoc(tmp, "page.md", DOC);
    String storage =
        MarkdownConverter.render("Some body.\n", new MarkdownConverter.RenderOptions()).storage();
    String digest = ContentHash.contentHash(storage);
    FakePageStore client = new FakePageStore();
    SyncOptions opts = new SyncOptions();
    opts.changesOnly = true;
    opts.versionMessage = "ship";
    RecordingLog log = new RecordingLog();
    assertEquals(SyncAction.CREATED, sync(path, opts, client, log).action());
    // Simulate a previous changes-only publish with a matching hash.
    Map<String, Object> stored = client.pages.get(List.of("DOC", "Hello", "page"));
    Map<String, Object> staleStorage = new LinkedHashMap<>();
    staleStorage.put("value", "STALE");
    stored.put("body", new LinkedHashMap<>(Map.of("storage", staleStorage)));
    Map<String, Object> tagged = new LinkedHashMap<>();
    tagged.put("number", 2L);
    tagged.put("message", ContentHash.formatVersionMessage("ship", digest));
    stored.put("version", tagged);
    client.writes.clear();
    assertEquals(SyncAction.UNCHANGED, sync(path, opts, client, log).action());
    assertEquals(0, client.count("update"));
    // A mismatched hash updates and stamps the new digest.
    Map<String, Object> old = new LinkedHashMap<>();
    old.put("number", 3L);
    old.put("message", "[v0*****] old");
    stored.put("version", old);
    assertEquals(SyncAction.UPDATED, sync(path, opts, client, log).action());
    List<Object> update =
        client.writes.stream().filter(w -> w.get(0).equals("update")).findFirst().orElseThrow();
    assertEquals(
        ContentHash.formatVersionMessage("ship", digest),
        ((Map<?, ?>) update.get(3)).get("versionMessage"));
  }

  @Test
  void missingTitleAndSpace(@TempDir Path tmp) throws Exception {
    Path untitled = writeDoc(tmp, "page.md", "No headers here.\n");
    SyncOptions withSpace = new SyncOptions();
    withSpace.space = "DOC";
    MetaException noTitle =
        assertThrows(
            MetaException.class,
            () -> sync(untitled, withSpace, new FakePageStore(), new RecordingLog()));
    assertTrue(noTitle.getMessage().contains("title is not set"));
    Path unspaced = writeDoc(tmp, "t.md", "<!-- Title: T -->\n\nBody.\n");
    MetaException noSpace =
        assertThrows(
            MetaException.class,
            () -> sync(unspaced, new SyncOptions(), new FakePageStore(), new RecordingLog()));
    assertTrue(noSpace.getMessage().contains("space is not set"));
  }

  @Test
  void missingAttachmentFailsTheFile(@TempDir Path tmp) throws Exception {
    String text =
        "<!-- Space: DOC -->\n<!-- Title: Hello -->\n<!-- Attachment: nope.png -->\n\nBody.\n";
    Path path = writeDoc(tmp, "page.md", text);
    SyncException e =
        assertThrows(
            SyncException.class,
            () -> sync(path, new SyncOptions(), new FakePageStore(), new RecordingLog()));
    assertTrue(e.getMessage().contains("does not exist"));
  }

  @Test
  void missingBodyRefWarnsAndIsSkipped(@TempDir Path tmp) throws Exception {
    Path path = writeDoc(tmp, "page.md", DOC.replace("Some body.", "![](nope.png)"));
    RecordingLog log = new RecordingLog();
    SyncResult result = sync(path, new SyncOptions(), new FakePageStore(), log);
    assertEquals(SyncAction.CREATED, result.action());
    assertTrue(log.warnContains("does not exist; skipped"));
  }

  @Test
  void noLabelSyncWhenAppendAndEmpty(@TempDir Path tmp) throws Exception {
    Path path = writeDoc(tmp, "page.md", DOC);
    FakePageStore client = new FakePageStore();
    SyncOptions opts = new SyncOptions();
    opts.appendLabels = true;
    sync(path, opts, client, new RecordingLog());
    assertEquals(0, client.count("labels"));
  }

  @Test
  void invalidImageAlignFailsTheFile(@TempDir Path tmp) throws Exception {
    Path path = writeDoc(tmp, "page.md", DOC);
    SyncOptions opts = new SyncOptions();
    opts.imageAlign = "top";
    MetaException e =
        assertThrows(
            MetaException.class, () -> sync(path, opts, new FakePageStore(), new RecordingLog()));
    assertTrue(e.getMessage().contains("unknown image-align"));
  }

  @Test
  void errorCollectionStopsWithoutContinue(@TempDir Path tmp) throws Exception {
    Path bad = writeDoc(tmp, "bad.md", "<!-- Titel: Typo -->\n\nBody.\n");
    Path good = writeDoc(tmp, "good.md", DOC);
    FakePageStore client = new FakePageStore();
    SyncOptions opts = new SyncOptions();
    opts.space = "DOC";
    RecordingLog log = new RecordingLog();
    RunReport report = PageSyncer.run(List.of(bad, good), opts, client, log);
    assertEquals(1, report.results().size());
    assertEquals(SyncAction.FAILED, report.results().get(0).action());
    assertEquals(0, client.count("create"));
    assertEquals(1, log.errors.size());
    assertTrue(log.errors.get(0).contains("bad.md"));
    SyncException summary = assertThrows(SyncException.class, report::throwIfFailed);
    assertTrue(summary.getMessage().contains("1 of 1 files failed:"));
    assertTrue(summary.getMessage().contains("bad.md"));
  }

  @Test
  void errorCollectionContinuesWithFlag(@TempDir Path tmp) throws Exception {
    Path bad = writeDoc(tmp, "bad.md", "<!-- Titel: Typo -->\n\nBody.\n");
    Path good = writeDoc(tmp, "good.md", DOC);
    FakePageStore client = new FakePageStore();
    SyncOptions opts = new SyncOptions();
    opts.space = "DOC";
    opts.continueOnError = true;
    RecordingLog log = new RecordingLog();
    RunReport report = PageSyncer.run(List.of(bad, good), opts, client, log);
    assertEquals(2, report.results().size());
    assertEquals(SyncAction.FAILED, report.results().get(0).action());
    assertEquals(SyncAction.CREATED, report.results().get(1).action());
    assertTrue(client.writes.contains(List.of("create", "Hello")));
    assertEquals(1, report.failures().size());
    SyncException summary = assertThrows(SyncException.class, report::throwIfFailed);
    assertTrue(summary.getMessage().contains("1 of 2 files failed:"));
    assertTrue(summary.getMessage().contains("good.md: created"));
  }

  @Test
  void reportCarriesFileDetails(@TempDir Path tmp) throws Exception {
    Path good = writeDoc(tmp, "good.md", DOC);
    FakePageStore client = new FakePageStore();
    RecordingLog log = new RecordingLog();
    RunReport report = PageSyncer.run(List.of(good), new SyncOptions(), client, log);
    assertEquals(List.of(), report.failures());
    report.throwIfFailed();
    SyncResult result = report.results().get(0);
    assertEquals(SyncAction.CREATED, result.action());
    assertEquals(good.toString(), result.file());
    assertEquals("DOC", result.space());
    assertEquals("Hello", result.title());
    assertFalse(result.pageId().isEmpty());
    assertTrue(result.url().startsWith("https://confluence.test/pages/"));
  }

  @Test
  void emptySelectionSucceeds(@TempDir Path tmp) {
    RecordingLog log = new RecordingLog();
    RunReport report = PageSyncer.run(List.of(), new SyncOptions(), null, log);
    assertEquals(List.of(), report.results());
    assertEquals(List.of("No changed pages to publish."), log.infos);
    report.throwIfFailed();
  }

  @Test
  void declaredParentsDisablePathParents(@TempDir Path tmp) throws Exception {
    Path page =
        writeDoc(
            tmp,
            "docs/guide/page.md",
            "<!-- Space: DOC -->\n<!-- Title: Hello -->\n<!-- Parent: Leaf -->\n\nBody.\n");
    FakePageStore client = new FakePageStore();
    SyncOptions opts = new SyncOptions();
    opts.parents = new ArrayList<>(List.of("Top"));
    opts.parentsFromPath = true;
    opts.parentsFromPathRoot = tmp.resolve("docs").toString();
    SyncResult result = sync(page, opts, client, new RecordingLog());
    assertEquals(SyncAction.CREATED, result.action());
    List<String> created =
        client.writes.stream()
            .filter(w -> w.get(0).equals("create"))
            .map(w -> String.valueOf(w.get(1)))
            .toList();
    assertEquals(List.of("Top", "Leaf", "Hello"), created);
  }

  @Test
  void parentsFromPathInsertsBetweenDslAndDoc(@TempDir Path tmp) throws Exception {
    // No declared Parent: path parents slot between the DSL prefix and doc parents.
    // Doc parents without a DSL prefix would need undeclared headers, so this pins [DSL, path].
    Path page = writeDoc(tmp, "docs/guide/page.md", DOC);
    FakePageStore client = new FakePageStore();
    SyncOptions opts = new SyncOptions();
    opts.parents = new ArrayList<>(List.of("Top"));
    opts.parentsFromPath = true;
    opts.parentsFromPathRoot = tmp.resolve("docs").toString();
    SyncResult result = sync(page, opts, client, new RecordingLog());
    assertEquals(SyncAction.CREATED, result.action());
    List<String> created =
        client.writes.stream()
            .filter(w -> w.get(0).equals("create"))
            .map(w -> String.valueOf(w.get(1)))
            .toList();
    assertEquals(List.of("Top", "Guide", "Hello"), created);
  }
}
