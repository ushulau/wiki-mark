package dev.vspex.wiki.mark.sync;

import dev.vspex.wiki.mark.json.Json;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/** Accepted local input and remote representation; never a display-normalized body. */
record MergeSnapshot(String local, String remote, long version, String context) {
  private static Path file(Path dir, String site, String id) {
    if (dir == null) throw new SyncException("Merge requires a persistent baseline directory");
    return dir.resolve(
        ContentHash.contentHash(site.replaceAll("/+$", "") + "\n" + id) + ".merge.json");
  }

  static MergeSnapshot read(Path dir, String site, String id) throws IOException {
    Path path = file(dir, site, id);
    if (!Files.exists(path)) return null;
    if (Files.size(path) > 30_000_000) throw new SyncException("Merge snapshot exceeds size limit");
    try {
      var map = (Map<?, ?>) Json.parse(Files.readString(path));
      String local = (String) map.get("local"), remote = (String) map.get("remote");
      if (!Long.valueOf(1).equals(((Number) map.get("schema")).longValue())
          || !ContentHash.contentHash(local).equals(map.get("localHash"))
          || !ContentHash.contentHash(remote).equals(map.get("remoteHash")))
        throw new IllegalArgumentException();
      if (((Number) map.get("version")).longValue() < 1 || !(map.get("context") instanceof String))
        throw new IllegalArgumentException();
      return new MergeSnapshot(
          local, remote, ((Number) map.get("version")).longValue(), (String) map.get("context"));
    } catch (RuntimeException e) {
      throw new SyncException(
          "Invalid merge snapshot; reconcile source and remote before publishing");
    }
  }

  void save(Path dir, String site, String id) throws IOException {
    Path destination = file(dir, site, id);
    Files.createDirectories(dir);
    Path temp = Files.createTempFile(dir, "merge-", ".tmp");
    try {
      Files.writeString(
          temp,
          Json.write(
              Map.of(
                  "context",
                  context,
                  "schema",
                  1,
                  "local",
                  local,
                  "remote",
                  remote,
                  "localHash",
                  ContentHash.contentHash(local),
                  "remoteHash",
                  ContentHash.contentHash(remote),
                  "version",
                  version)));
      try {
        Files.move(
            temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
