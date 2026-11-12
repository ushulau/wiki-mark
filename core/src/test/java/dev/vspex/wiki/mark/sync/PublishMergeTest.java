package dev.vspex.wiki.mark.sync;

import static org.junit.jupiter.api.Assertions.*;

import dev.vspex.wiki.mark.model.SyncAction;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishMergeTest {
  @TempDir Path root;

  @Test
  void repeatPublishRetainsRemoteAndOrdinaryPublishRequiresConsent() throws Exception {
    var helper = new PublishDriftTest();
    helper.root = root;
    var opts = helper.options();
    var store = new PageSyncerTest.FakePageStore();
    var log = new PageSyncerTest.RecordingLog();
    Path file = helper.document("a\n\nb");
    PublishDriftTest.sync(file, opts, store, log);
    PublishDriftTest.edit(store, "<p>a</p><p>b</p><p>remote</p>", "");
    helper.document("A\n\nb");
    opts.merge = true;
    assertEquals(SyncAction.UPDATED, PublishDriftTest.sync(file, opts, store, log).action());
    assertEquals("<p>A</p><p>b</p><p>remote</p>", body(store));
    store.writes.clear();
    assertEquals(SyncAction.UNCHANGED, PublishDriftTest.sync(file, opts, store, log).action());
    assertTrue(store.writes.isEmpty());
    helper.document("A\n\nB");
    PublishDriftTest.sync(file, opts, store, log);
    assertEquals("<p>A</p><p>B</p><p>remote</p>", body(store));
    opts.merge = false;
    assertThrows(SyncException.class, () -> PublishDriftTest.sync(file, opts, store, log));
  }

  @Test
  void conflictPreviewAndMissingHistoryNeverWrite() throws Exception {
    var helper = new PublishDriftTest();
    helper.root = root;
    var opts = helper.options();
    var store = new PageSyncerTest.FakePageStore();
    var log = new PageSyncerTest.RecordingLog();
    Path file = helper.document("a");
    PublishDriftTest.sync(file, opts, store, log);
    PublishDriftTest.edit(store, "<p>remote</p>", "");
    helper.document("local");
    opts.merge = true;
    store.writes.clear();
    assertThrows(SyncException.class, () -> PublishDriftTest.sync(file, opts, store, log));
    assertTrue(store.writes.isEmpty());
    helper.document("a");
    opts.dryRun = true;
    PublishDriftTest.sync(file, opts, store, log);
    assertTrue(store.writes.isEmpty());
    opts.dryRun = false;
    PublishDriftTest.sync(file, opts, store, log);
    assertTrue(store.writes.isEmpty());
    opts.baselineDirectory = root.resolve("empty");
    helper.document("different");
    assertThrows(SyncException.class, () -> PublishDriftTest.sync(file, opts, store, log));
    assertTrue(store.writes.isEmpty());
  }

  private String body(PageSyncerTest.FakePageStore store) {
    return ((Map<?, ?>) ((Map<?, ?>) PublishDriftTest.remote(store).get("body")).get("storage"))
        .get("value")
        .toString();
  }
}
