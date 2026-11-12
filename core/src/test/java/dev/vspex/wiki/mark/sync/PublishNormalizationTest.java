package dev.vspex.wiki.mark.sync;

import static org.junit.jupiter.api.Assertions.*;

import dev.vspex.wiki.mark.model.SyncAction;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishNormalizationTest {
  @TempDir Path root;

  PublishDriftTest helper() {
    var helper = new PublishDriftTest();
    helper.root = root;
    return helper;
  }

  PageSyncerTest.FakePageStore store() {
    var store = new PageSyncerTest.FakePageStore();
    // An arbitrary observed server rewrite: tracking must not depend on a whitelist.
    store.storedBody = body -> body.replace("hello", "he&#108;lo");
    return store;
  }

  String body(PageSyncerTest.FakePageStore store) {
    return ((Map<?, ?>) ((Map<?, ?>) PublishDriftTest.remote(store).get("body")).get("storage"))
        .get("value")
        .toString();
  }

  @Test
  void storedBaselineAndRepeatedForcedPublishSkipWithoutReadback() throws Exception {
    var h = helper();
    var opts = h.options();
    var store = store();
    var log = new PageSyncerTest.RecordingLog();
    var file = h.document("hello");
    PublishDriftTest.sync(file, opts, store, log);
    String id = PublishDriftTest.remote(store).get("id").toString();
    assertEquals(
        ContentHash.contentHash(body(store)),
        PublishBaseline.read(opts.baselineDirectory, store.baseUrl(), id).storageHash());
    assertEquals(1, store.pageReads);
    var snapshot = MergeSnapshot.read(opts.baselineDirectory, store.baseUrl(), id);
    assertEquals(snapshot.local(), snapshot.submitted());
    assertNotEquals(snapshot.submitted(), snapshot.remote());
    store.writes.clear();
    for (boolean force : List.of(false, true, false)) {
      opts.force = force;
      assertEquals(SyncAction.UNCHANGED, PublishDriftTest.sync(file, opts, store, log).action());
    }
    assertTrue(store.writes.isEmpty());
    assertEquals(4, store.pageReads); // One normal remote fetch per skipped run, no readback.
    assertEquals(snapshot, MergeSnapshot.read(opts.baselineDirectory, store.baseUrl(), id));
    h.document("hello again");
    assertEquals(SyncAction.UPDATED, PublishDriftTest.sync(file, opts, store, log).action());
    assertEquals(6, store.pageReads); // Remote fetch plus verification after the update.
    assertEquals(
        ContentHash.contentHash(body(store)),
        PublishBaseline.read(opts.baselineDirectory, store.baseUrl(), id).storageHash());
  }

  @Test
  void remoteChangesAndContextChangesAreNotSilentlySkipped() throws Exception {
    var h = helper();
    var opts = h.options();
    var store = store();
    var log = new PageSyncerTest.RecordingLog();
    var file = h.document("hello");
    PublishDriftTest.sync(file, opts, store, log);
    PublishDriftTest.edit(store, body(store), ""); // version-only drift
    store.writes.clear();
    assertThrows(SyncException.class, () -> PublishDriftTest.sync(file, opts, store, log));
    PublishDriftTest.edit(store, "<p>remote edit</p>", "");
    assertThrows(SyncException.class, () -> PublishDriftTest.sync(file, opts, store, log));
    assertTrue(store.writes.isEmpty());
    opts.force = true;
    PublishDriftTest.sync(file, opts, store, log);
    opts.force = false;
    assertEquals(SyncAction.UNCHANGED, PublishDriftTest.sync(file, opts, store, log).action());
    Path moved = root.resolve("moved.md");
    Files.copy(file, moved);
    assertEquals(SyncAction.UPDATED, PublishDriftTest.sync(moved, opts, store, log).action());
  }

  @Test
  void mergeEditsRewrittenBlockAndRetainsActualRemoteContribution() throws Exception {
    var h = helper();
    var opts = h.options();
    var store = store();
    var log = new PageSyncerTest.RecordingLog();
    var file = h.document("hello\n\nend");
    PublishDriftTest.sync(file, opts, store, log);
    opts.merge = true;
    h.document("hello edited\n\nend");
    assertEquals(SyncAction.UPDATED, PublishDriftTest.sync(file, opts, store, log).action());
    assertEquals(SyncAction.UNCHANGED, PublishDriftTest.sync(file, opts, store, log).action());
    PublishDriftTest.edit(store, body(store) + "<p>remote contribution</p>", "");
    PublishDriftTest.sync(file, opts, store, log);
    h.document("hello edited again\n\nend");
    PublishDriftTest.sync(file, opts, store, log);
    assertTrue(body(store).contains("remote contribution"));
    opts.merge = false;
    assertThrows(SyncException.class, () -> PublishDriftTest.sync(file, opts, store, log));
  }

  @Test
  void previewAndFailedVerificationLeaveTrackingUntouched() throws Exception {
    var h = helper();
    var opts = h.options();
    var store = store();
    var log = new PageSyncerTest.RecordingLog();
    var file = h.document("hello");
    PublishDriftTest.sync(file, opts, store, log);
    String id = PublishDriftTest.remote(store).get("id").toString();
    var before = MergeSnapshot.read(opts.baselineDirectory, store.baseUrl(), id);
    var baseline = PublishBaseline.read(opts.baselineDirectory, store.baseUrl(), id);
    h.document("hello changed");
    opts.dryRun = true;
    store.writes.clear();
    PublishDriftTest.sync(file, opts, store, log);
    assertTrue(store.writes.isEmpty());
    assertEquals(before, MergeSnapshot.read(opts.baselineDirectory, store.baseUrl(), id));
    opts.dryRun = false;
    store.missingReadbackBody = true;
    assertThrows(SyncException.class, () -> PublishDriftTest.sync(file, opts, store, log));
    assertEquals(before, MergeSnapshot.read(opts.baselineDirectory, store.baseUrl(), id));
    assertEquals(baseline, PublishBaseline.read(opts.baselineDirectory, store.baseUrl(), id));
    store.missingReadbackBody = false;
    store.changedReadbackVersion = true;
    opts.force = true;
    assertThrows(SyncException.class, () -> PublishDriftTest.sync(file, opts, store, log));
    assertEquals(before, MergeSnapshot.read(opts.baselineDirectory, store.baseUrl(), id));
  }

  @Test
  void cdataEntitiesAndWhitespaceRemainExactLocalChanges() throws Exception {
    var h = helper();
    var opts = h.options();
    var store = store();
    var log = new PageSyncerTest.RecordingLog();
    var file = h.document("```text\n&quot;  hello\n```");
    PublishDriftTest.sync(file, opts, store, log);
    assertTrue(body(store).contains("<![CDATA["));
    for (String content : List.of("```text\n\"  hello\n```", "```text\n\" hello\n```")) {
      h.document(content);
      String source = Files.readString(file);
      assertEquals(SyncAction.UPDATED, PublishDriftTest.sync(file, opts, store, log).action());
      assertEquals(source, Files.readString(file));
      assertEquals(SyncAction.UNCHANGED, PublishDriftTest.sync(file, opts, store, log).action());
    }
  }

  @Test
  void legacyAmbiguousHistoryRequiresOneReviewedPublish() throws Exception {
    var h = helper();
    var opts = h.options();
    var store = store();
    var log = new PageSyncerTest.RecordingLog();
    var file = h.document("hello");
    PublishDriftTest.sync(file, opts, store, log);
    Path snapshot;
    try (var files = Files.list(opts.baselineDirectory)) {
      snapshot = files.filter(p -> p.toString().endsWith(".merge.json")).findFirst().orElseThrow();
    }
    Files.writeString(snapshot, Files.readString(snapshot).replace("\"schema\":2", "\"schema\":1"));
    assertThrows(SyncException.class, () -> PublishDriftTest.sync(file, opts, store, log));
    opts.force = true;
    assertEquals(SyncAction.UPDATED, PublishDriftTest.sync(file, opts, store, log).action());
    opts.force = false;
    assertEquals(SyncAction.UNCHANGED, PublishDriftTest.sync(file, opts, store, log).action());
  }
}
