package dev.vspex.wiki.mark.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergeSnapshotTest {
  @TempDir Path root;

  @Test
  void roundTripIntegrityAndIdentity() throws Exception {
    var snapshot =
        new MergeSnapshot("<p>local</p>", "<p>submitted</p>", "<p>remote</p>", 7, "context");
    snapshot.save(root, "https://example.test/", "7");
    assertEquals(snapshot, MergeSnapshot.read(root, "https://example.test", "7"));
    assertNull(MergeSnapshot.read(root, "https://other.test", "7"));
    assertNull(MergeSnapshot.read(root, "https://example.test", "8"));
    Path file;
    try (var files = Files.list(root)) {
      file = files.findFirst().orElseThrow();
    }
    String valid = Files.readString(file);
    Files.writeString(file, valid.replace("<p>submitted</p>", "<p>tampered</p>"));
    assertThrows(SyncException.class, () -> MergeSnapshot.read(root, "https://example.test", "7"));
    Files.writeString(file, valid.replace("<p>remote</p>", "<p>tampered</p>"));
    assertThrows(SyncException.class, () -> MergeSnapshot.read(root, "https://example.test", "7"));
  }

  @Test
  void unsupportedSchemaAndInvalidStateFailClosed() throws Exception {
    var snapshot = new MergeSnapshot("", "", 1, "context");
    snapshot.save(root, "site", "1");
    Path file;
    try (var files = Files.list(root)) {
      file = files.findFirst().orElseThrow();
    }
    Files.writeString(file, "{}");
    assertThrows(SyncException.class, () -> MergeSnapshot.read(root, "site", "1"));
    Files.writeString(file, "invalid JSON");
    assertThrows(SyncException.class, () -> MergeSnapshot.read(root, "site", "1"));
  }
}
