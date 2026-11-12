package dev.vspex.wiki.mark.sync;

import static org.junit.jupiter.api.Assertions.*;

import dev.vspex.wiki.mark.model.SyncAction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishDriftTest {
  @TempDir Path root;

  Path document(String body) throws Exception {
    Path file = root.resolve("page.md");
    Files.writeString(file, "<!-- Space: DOC -->\n<!-- Title: Hello -->\n\n" + body);
    return file;
  }

  SyncOptions options() {
    var options = new SyncOptions();
    options.confirmOverwrite = null;
    options.baselineDirectory = root.resolve("state");
    return options;
  }

  static Map<String, Object> remote(PageSyncerTest.FakePageStore store) {
    return store.pages.get(List.of("DOC", "Hello", "page"));
  }

  static void edit(PageSyncerTest.FakePageStore store, String body, String message) {
    var page = remote(store);
    page.put("body", Map.of("storage", Map.of("value", body)));
    page.put(
        "version",
        Map.of(
            "number",
            ((Number) ((Map<?, ?>) page.get("version")).get("number")).longValue() + 1,
            "message",
            message));
  }

  static dev.vspex.wiki.mark.model.SyncResult sync(
      Path file,
      SyncOptions options,
      PageSyncerTest.FakePageStore store,
      PageSyncerTest.RecordingLog log)
      throws Exception {
    return PageSyncer.syncFile(file, options, store, Map.of(), log);
  }

  @Test
  void newAndLocalOnlyPublishWithoutPromptAndAlwaysTag() throws Exception {
    var store = new PageSyncerTest.FakePageStore();
    var opts = options();
    var log = new PageSyncerTest.RecordingLog();
    opts.confirmOverwrite =
        message -> {
          throw new AssertionError("unexpected prompt");
        };
    Path file = document("base");
    assertEquals(SyncAction.CREATED, sync(file, opts, store, log).action());
    String id = String.valueOf(remote(store).get("id"));
    assertEquals(
        ContentHash.contentHash("<p>base</p>"),
        PublishBaseline.read(opts.baselineDirectory, store.baseUrl(), id).storageHash());
    assertTrue(
        ((Map<?, ?>) remote(store).get("version")).get("message").toString().startsWith("[v"));
    document("local");
    assertEquals(SyncAction.UPDATED, sync(file, opts, store, log).action());
    assertEquals(
        2, PublishBaseline.read(opts.baselineDirectory, store.baseUrl(), id).versionNumber());
  }

  @Test
  void driftMatrixKeepsRemoteByDefaultAndFailsWithoutConsole() throws Exception {
    for (String local : List.of("base", "local")) {
      var store = new PageSyncerTest.FakePageStore();
      var opts = options();
      var log = new PageSyncerTest.RecordingLog();
      Path file = document("base");
      sync(file, opts, store, log);
      edit(store, "<p>remote</p>", "");
      document(local);
      store.writes.clear();
      var failure = assertThrows(SyncException.class, () -> sync(file, opts, store, log));
      assertTrue(
          failure.getMessage().contains(local.equals("base") ? "remote-only drift" : "conflict"));
      assertTrue(failure.getMessage().contains("--- a/remote"));
      assertTrue(failure.getMessage().contains("--force"));
      assertTrue(store.writes.isEmpty());
      AtomicInteger prompts = new AtomicInteger();
      opts.confirmOverwrite =
          message -> {
            prompts.incrementAndGet();
            return false;
          };
      assertEquals(SyncAction.SKIPPED, sync(file, opts, store, log).action());
      assertEquals(1, prompts.get());
      assertTrue(store.writes.isEmpty());
    }
  }

  @Test
  void unknownBaselinePreviewForceAndSecrets() throws Exception {
    var store = new PageSyncerTest.FakePageStore();
    store.seed("DOC", "Hello", "page", "<p>secret-token</p>");
    var opts = options();
    opts.redactions = List.of("secret-token");
    var log = new PageSyncerTest.RecordingLog();
    Path file = document("local");
    var failure = assertThrows(SyncException.class, () -> sync(file, opts, store, log));
    assertTrue(failure.getMessage().contains("unknown baseline"));
    assertFalse(failure.getMessage().contains("secret-token"));
    assertTrue(log.warns.stream().noneMatch(w -> w.contains("secret-token")));
    opts.dryRun = true;
    assertEquals(SyncAction.DRY_RUN, sync(file, opts, store, log).action());
    assertTrue(store.writes.isEmpty());
    assertFalse(Files.exists(opts.baselineDirectory));
    opts.dryRun = false;
    opts.force = true;
    assertEquals(SyncAction.UPDATED, sync(file, opts, store, log).action());
  }

  @Test
  void freshCloneUsesTagAndIdenticalVersionBumpDoesNotWrite() throws Exception {
    var store = new PageSyncerTest.FakePageStore();
    var opts = options();
    var log = new PageSyncerTest.RecordingLog();
    Path file = document("base");
    sync(file, opts, store, log);
    opts.baselineDirectory = root.resolve("fresh");
    document("local");
    assertEquals(SyncAction.UPDATED, sync(file, opts, store, log).action());
    edit(store, "<p>local</p>", "labels-only");
    store.writes.clear();
    assertEquals(SyncAction.UNCHANGED, sync(file, opts, store, log).action());
    assertTrue(store.writes.isEmpty());
  }

  @Test
  void wipedTagFreshCloneAndContradictoryStateFailSafe() throws Exception {
    var store = new PageSyncerTest.FakePageStore();
    var opts = options();
    var log = new PageSyncerTest.RecordingLog();
    Path file = document("base");
    sync(file, opts, store, log);
    edit(store, "<p>remote</p>", "");
    opts.baselineDirectory = root.resolve("fresh");
    assertTrue(
        assertThrows(SyncException.class, () -> sync(file, opts, store, log))
            .getMessage()
            .contains("unknown baseline"));
    String hash = ContentHash.contentHash("base");
    assertEquals(
        "",
        PublishBaseline.resolve(hash, 2, new PublishBaseline(ContentHash.contentHash("other"), 2)));
    assertEquals("", PublishBaseline.resolve(hash, 2, new PublishBaseline(hash, 3)));
  }

  @Test
  void rejectedDriftDoesNotCreateAncestorsOrAdvanceBaseline() throws Exception {
    var store = new PageSyncerTest.FakePageStore();
    var opts = options();
    var log = new PageSyncerTest.RecordingLog();
    Path file = document("base");
    sync(file, opts, store, log);
    String id = String.valueOf(remote(store).get("id"));
    var before = PublishBaseline.read(opts.baselineDirectory, store.baseUrl(), id);
    edit(store, "<p>remote</p>", "");
    store.writes.clear();
    opts.parents = List.of("Missing parent");
    assertThrows(SyncException.class, () -> sync(file, opts, store, log));
    assertTrue(store.writes.isEmpty());
    assertEquals(before, PublishBaseline.read(opts.baselineDirectory, store.baseUrl(), id));
  }

  @Test
  void failedWritesNeverAdvanceLocalBaseline() throws Exception {
    var store = new PageSyncerTest.FakePageStore();
    var opts = options();
    var log = new PageSyncerTest.RecordingLog();
    Path file = document("base");
    sync(file, opts, store, log);
    String id = String.valueOf(remote(store).get("id"));
    var before = PublishBaseline.read(opts.baselineDirectory, store.baseUrl(), id);
    document("local");
    store.failUpdate = true;
    assertThrows(SyncException.class, () -> sync(file, opts, store, log));
    assertEquals(before, PublishBaseline.read(opts.baselineDirectory, store.baseUrl(), id));
    store.failUpdate = false;
    store.failLabels = true;
    assertThrows(SyncException.class, () -> sync(file, opts, store, log));
    assertEquals(before, PublishBaseline.read(opts.baselineDirectory, store.baseUrl(), id));
  }

  @Test
  void interactiveApprovalAndHiddenDisplayDifferenceAreExplicit() throws Exception {
    var store = new PageSyncerTest.FakePageStore();
    var opts = options();
    var log = new PageSyncerTest.RecordingLog();
    Path file =
        document(
            "<!-- wiki-mark-raw-begin -->\n<p ac:local-id=\"new\">x</p>\n<!-- wiki-mark-raw-end -->\n");
    store.seed("DOC", "Hello", "page", "<p ac:local-id=\"old\">x</p>");
    opts.confirmOverwrite =
        warning -> {
          assertTrue(warning.contains("display normalization hides"));
          return true;
        };
    assertEquals(SyncAction.UPDATED, sync(file, opts, store, log).action());
  }
}
