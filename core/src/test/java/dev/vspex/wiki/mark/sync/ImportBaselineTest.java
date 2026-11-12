package dev.vspex.wiki.mark.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vspex.wiki.mark.importer.ImportOptions;
import dev.vspex.wiki.mark.importer.ImportReport;
import dev.vspex.wiki.mark.importer.PageImportSource;
import dev.vspex.wiki.mark.importer.PageImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Import writes the same publish baseline that publish maintains, so an edited lossless import
 * publishes without {@code --force}. Uses recorded-style fakes on both sides; no network.
 */
class ImportBaselineTest {
  @TempDir Path project;

  static final class FakeImportSource implements PageImportSource {
    final String id;
    final String space;
    final String title;
    final long version;
    final String storage;

    FakeImportSource(String id, String space, String title, long version, String storage) {
      this.id = id;
      this.space = space;
      this.title = title;
      this.version = version;
      this.storage = storage;
    }

    final String base = "https://confluence.test";

    @Override
    public String baseUrl() {
      return base;
    }

    @Override
    public PageSnapshot readPage(String pageId) {
      return new PageSnapshot(id, title, space, version, storage, List.of(), List.of());
    }

    @Override
    public List<AttachmentSnapshot> attachments(String pageId) {
      return List.of();
    }

    @Override
    public byte[] download(AttachmentSnapshot attachment) {
      return new byte[0];
    }
  }

  private ImportReport lossless(FakeImportSource source, String output) throws IOException {
    return PageImporter.run(
        project,
        new ImportOptions(
            source.id, "", output, true, false, true, false, "", List.of(), "", "", false),
        source);
  }

  private SyncOptions publishOpts(boolean force) {
    SyncOptions opts = new SyncOptions();
    opts.force = force;
    opts.confirmOverwrite = null;
    opts.baselineDirectory = project.resolve(".gradle/confluence-publish/baselines");
    opts.redactions = List.of();
    return opts;
  }

  private static Path singleBaseline(Path directory) throws IOException {
    try (var paths = Files.list(directory)) {
      List<Path> files = paths.filter(path -> path.toString().endsWith(".json")).toList();
      assertEquals(1, files.size());
      return files.get(0);
    }
  }

  private Path baselineDirectory() {
    return project.resolve(".gradle/confluence-publish/baselines");
  }

  private static void setVersion(
      PageSyncerTest.FakePageStore store,
      String id,
      long number,
      String message,
      String author,
      String when) {
    for (Map<String, Object> page : store.pages.values()) {
      if (page.get("id").equals(id)) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("number", number);
        version.put("message", message);
        if (author != null) {
          version.put("by", new LinkedHashMap<>(Map.of("displayName", author)));
        }
        if (when != null) {
          version.put("when", when);
        }
        page.put("version", version);
      }
    }
  }

  @Test
  void overwriteRefreshesBaselineDespiteCopiedTagAndStaleMergeHistory() throws Exception {
    var store = new PageSyncerTest.FakePageStore();
    String id = String.valueOf(store.seed("DOC", "Doc", "page", "<p>Imported</p>").get("id"));
    setVersion(
        store,
        id,
        3L,
        ContentHash.formatVersionMessage("copied tag", ContentHash.contentHash("old")),
        null,
        null);
    var source = new FakeImportSource(id, "DOC", "Doc", 3L, "<p>Imported</p>");
    var directory = baselineDirectory();
    new PublishBaseline(ContentHash.contentHash("old"), 1).save(directory, source.base, id);
    new MergeSnapshot("<p>local</p>", "<p>remote</p>", 1, "old context")
        .save(directory, source.base, id);
    Path doc = lossless(source, "guide.md").document();
    new PublishBaseline(ContentHash.contentHash("old"), 1).save(directory, source.base, id);
    new MergeSnapshot("<p>local</p>", "<p>remote</p>", 1, "old context")
        .save(directory, source.base, id);
    var report =
        PageImporter.run(
            project,
            new ImportOptions(
                id, "", "guide.md", true, false, true, false, "", List.of(), "", "", false),
            source);
    assertTrue(report.baselineStatus().startsWith("Publish baseline recorded"));
    org.junit.jupiter.api.Assertions.assertNull(MergeSnapshot.read(directory, source.base, id));
    Files.writeString(doc, Files.readString(doc).replace("<p>Imported</p>", "<p>Edited</p>"));
    var result =
        PageSyncer.run(List.of(doc), publishOpts(false), store, new PageSyncerTest.RecordingLog());
    assertEquals(List.of(), result.failures());
    assertEquals(1, store.count("update"));
  }

  @Test
  void newerRemoteVersionWithSameStorageStillRequiresConsentForLocalEdit() throws Exception {
    var store = new PageSyncerTest.FakePageStore();
    String id = String.valueOf(store.seed("DOC", "Doc", "page", "<p>Imported</p>").get("id"));
    setVersion(store, id, 3L, "", null, null);
    Path doc =
        lossless(new FakeImportSource(id, "DOC", "Doc", 3L, "<p>Imported</p>"), "guide.md")
            .document();
    Files.writeString(doc, Files.readString(doc).replace("<p>Imported</p>", "<p>Edited</p>"));
    setVersion(store, id, 4L, "", null, null);
    var log = new PageSyncerTest.RecordingLog();
    var result = PageSyncer.run(List.of(doc), publishOpts(false), store, log);
    assertEquals(1, result.failures().size());
    assertEquals(0, store.count("update"));
    assertTrue(log.errors.get(0).contains("Remote version 4 (baseline version 3)"));
  }

  @Test
  void failedImportKeepsExistingBaselineAndMergeHistory() throws Exception {
    var source = new FakeImportSource("7", "DOC", "Doc", 3L, "<p>Imported</p>");
    lossless(source, "guide.md");
    var baseline = new PublishBaseline(ContentHash.contentHash("old"), 1);
    baseline.save(baselineDirectory(), source.base, "7");
    var merge = new MergeSnapshot("local", "remote", 1, "context");
    merge.save(baselineDirectory(), source.base, "7");
    Files.writeString(project.resolve("guide.md"), "existing");
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class,
        () ->
            PageImporter.run(
                project,
                new ImportOptions(
                    "7", "", "guide.md", false, false, true, false, "", List.of(), "", "", false),
                source));
    assertEquals(baseline, PublishBaseline.read(baselineDirectory(), source.base, "7"));
    assertEquals(merge, MergeSnapshot.read(baselineDirectory(), source.base, "7"));
  }

  @Test
  void importThenEditThenPublishWithoutForce() throws Exception {
    String storage = "<p>Hello</p>";
    var store = new PageSyncerTest.FakePageStore();
    String id = String.valueOf(store.seed("DOC", "Doc", "page", storage).get("id"));
    setVersion(store, id, 3L, "", null, null);
    var source = new FakeImportSource(id, "DOC", "Doc", 3L, storage);
    ImportReport report = lossless(source, "guide.md");
    assertTrue(report.baselineStatus().startsWith("Publish baseline recorded: version 3"));
    Path doc = report.document();
    Files.writeString(
        doc, Files.readString(doc).replace("<p>Hello</p>", "<p>Hello</p><p>Edited</p>"));
    RunReport result =
        PageSyncer.run(List.of(doc), publishOpts(false), store, new PageSyncerTest.RecordingLog());
    assertEquals(List.of(), result.failures());
    assertEquals(1, store.count("update"));
    PublishBaseline refreshed =
        PublishBaseline.read(
            project.resolve(".gradle/confluence-publish/baselines"), source.base, id);
    assertEquals(4L, refreshed.versionNumber());
  }

  @Test
  void importThenPublishUnchangedSkips() throws Exception {
    String storage = "<p>Hello</p>";
    var store = new PageSyncerTest.FakePageStore();
    String id = String.valueOf(store.seed("DOC", "Doc", "page", storage).get("id"));
    setVersion(store, id, 3L, "", null, null);
    var source = new FakeImportSource(id, "DOC", "Doc", 3L, storage);
    ImportReport report = lossless(source, "guide.md");
    assertTrue(Files.exists(singleBaseline(baselineDirectory())));
    RunReport result =
        PageSyncer.run(
            List.of(report.document()),
            publishOpts(false),
            store,
            new PageSyncerTest.RecordingLog());
    assertEquals(List.of(), result.failures());
    assertEquals(0, store.count("update"));
  }

  @Test
  void remoteEditThenPublishConflictsWithDetails() throws Exception {
    String storage = "<p>Hello</p>";
    var store = new PageSyncerTest.FakePageStore();
    String id = String.valueOf(store.seed("DOC", "Doc", "page", storage).get("id"));
    setVersion(store, id, 3L, "", null, null);
    var source = new FakeImportSource(id, "DOC", "Doc", 3L, storage);
    ImportReport report = lossless(source, "guide.md");
    Path doc = report.document();
    Files.writeString(
        doc, Files.readString(doc).replace("<p>Hello</p>", "<p>Hello</p><p>Edited</p>"));
    setVersion(store, id, 4L, "", "Jane Doe", "2026-11-10T09:12:00.000Z");
    var remote =
        store.pages.values().stream()
            .filter(page -> page.get("id").equals(id))
            .findFirst()
            .orElseThrow();
    ((Map<String, Object>) remote.get("body")).put("storage", Map.of("value", "<p>Remote</p>"));
    var log = new PageSyncerTest.RecordingLog();
    RunReport result = PageSyncer.run(List.of(doc), publishOpts(false), store, log);
    assertEquals(1, result.failures().size());
    assertEquals(0, store.count("update"));
    String diagnostic = log.errors.get(0);
    assertTrue(diagnostic.contains("conflict"), diagnostic);
    assertTrue(diagnostic.contains("Remote version 4"), diagnostic);
    assertTrue(diagnostic.contains("baseline version 3"), diagnostic);
    assertTrue(diagnostic.contains("Jane Doe"), diagnostic);
    assertTrue(diagnostic.contains("2026-11-10T09:12:00.000Z"), diagnostic);
    assertTrue(diagnostic.contains("--force"), diagnostic);
    assertTrue(diagnostic.contains("re-import"), diagnostic);
    RunReport forced =
        PageSyncer.run(List.of(doc), publishOpts(true), store, new PageSyncerTest.RecordingLog());
    assertEquals(List.of(), forced.failures());
    assertEquals(1, store.count("update"));
  }

  @Test
  void conflictMessageToleratesMissingAuthorAndTime() throws Exception {
    String storage = "<p>Hello</p>";
    var store = new PageSyncerTest.FakePageStore();
    String id = String.valueOf(store.seed("DOC", "Doc", "page", storage).get("id"));
    setVersion(store, id, 4L, "", null, null);
    var log = new PageSyncerTest.RecordingLog();
    RunReport result =
        PageSyncer.run(
            List.of(
                lossless(new FakeImportSource(id, "DOC", "Doc", 4L, "<p>Other</p>"), "g.md")
                    .document()),
            publishOpts(false),
            store,
            log);
    assertEquals(1, result.failures().size());
    assertTrue(log.errors.get(0).contains("unknown editor"), log.errors.get(0));
    assertTrue(log.errors.get(0).contains("unknown time"), log.errors.get(0));
  }

  @Test
  void previewWritesNoBaseline() throws Exception {
    var source = new FakeImportSource("7", "DOC", "Doc", 3L, "<p>Hello</p>");
    ImportReport report =
        PageImporter.run(
            project,
            new ImportOptions(
                "7", "", "guide.md", false, true, true, false, "", List.of(), "", "", false),
            source);
    assertTrue(report.baselineStatus().contains("preview writes nothing"));
    assertFalse(Files.exists(project.resolve(".gradle/confluence-publish/baselines")));
  }

  @Test
  void retargetedImportSkipsBaseline() throws Exception {
    var source = new FakeImportSource("7", "DOC", "Doc", 3L, "<p>Hello</p>");
    ImportReport space =
        PageImporter.run(
            project,
            new ImportOptions(
                "7", "", "s.md", false, false, true, false, "NEW", List.of(), "", "", false),
            source);
    assertTrue(space.baselineStatus().contains("retargets space"), space.baselineStatus());
    ImportReport title =
        PageImporter.run(
            project,
            new ImportOptions(
                "7", "", "t.md", false, false, true, false, "", List.of(), "Copy", "", false),
            source);
    assertTrue(title.baselineStatus().contains("retargets title"), title.baselineStatus());
    ImportReport parents =
        PageImporter.run(
            project,
            new ImportOptions(
                "7", "", "p.md", false, false, true, false, "", List.of("Top"), "", "", false),
            source);
    assertTrue(parents.baselineStatus().contains("retargets parents"), parents.baselineStatus());
    assertTrue(parents.baselineStatus().contains("first publish"), parents.baselineStatus());
    assertFalse(Files.exists(project.resolve(".gradle/confluence-publish/baselines")));
  }

  @Test
  void lossyImportSkipsBaseline() throws Exception {
    var source = new FakeImportSource("7", "DOC", "Doc", 3L, "<p>Hello</p>");
    ImportReport report =
        PageImporter.run(project, new ImportOptions("7", "", "guide.md", false, false), source);
    assertTrue(report.baselineStatus().contains("Markdown import is lossy"));
    assertFalse(Files.exists(project.resolve(".gradle/confluence-publish/baselines")));
  }

  @Test
  void noBaselineFlagSkipsBaseline() throws Exception {
    var source = new FakeImportSource("7", "DOC", "Doc", 3L, "<p>Hello</p>");
    ImportReport report =
        PageImporter.run(
            project,
            new ImportOptions(
                "7", "", "guide.md", false, false, true, false, "", List.of(), "", "", true),
            source);
    assertTrue(report.baselineStatus().contains("--no-baseline"));
    assertFalse(Files.exists(project.resolve(".gradle/confluence-publish/baselines")));
  }

  @Test
  void importTwiceRewritesSameBaseline() throws Exception {
    var source = new FakeImportSource("7", "DOC", "Doc", 3L, "<p>Hello</p>");
    ImportReport first = lossless(source, "guide.md");
    assertTrue(first.baselineStatus().startsWith("Publish baseline recorded"));
    byte[] bytes = Files.readAllBytes(singleBaseline(baselineDirectory()));
    ImportReport second = lossless(source, "guide.md");
    assertTrue(second.baselineStatus().startsWith("Publish baseline recorded"));
    assertArrayEquals(bytes, Files.readAllBytes(singleBaseline(baselineDirectory())));
  }

  @Test
  void baselineFileMatchesPublishWriter() throws Exception {
    String storage = "<p>Hello</p>";
    var source = new FakeImportSource("7", "DOC", "Doc", 3L, storage);
    lossless(source, "guide.md");
    Path directory = project.resolve(".gradle/confluence-publish/baselines");
    Path other = Files.createDirectories(project.resolve("other-baselines"));
    String hash = ContentHash.contentHash(storage);
    new PublishBaseline(hash, 3L).save(other, source.base, "7");
    Path imported = singleBaseline(directory);
    Path republished = singleBaseline(other);
    assertEquals(imported.getFileName(), republished.getFileName());
    assertArrayEquals(Files.readAllBytes(republished), Files.readAllBytes(imported));
  }

  @Test
  void manifestKeepsPageIdVersionHashAndMode() throws Exception {
    String storage = "<p>Hello</p>";
    var source = new FakeImportSource("7", "DOC", "Doc", 3L, storage);
    ImportReport report = lossless(source, "guide.md");
    var manifest =
        (Map<?, ?>)
            dev.vspex.wiki.mark.json.Json.parse(
                Files.readString(report.document().resolveSibling("guide.confluence.json")));
    assertEquals("7", manifest.get("pageId"));
    assertEquals(3L, manifest.get("version"));
    assertEquals(ContentHash.contentHash(storage), manifest.get("storageHash"));
    assertEquals("lossless", manifest.get("mode"));
  }
}
