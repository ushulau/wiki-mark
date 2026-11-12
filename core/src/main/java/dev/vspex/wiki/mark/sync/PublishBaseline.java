package dev.vspex.wiki.mark.sync;

import dev.vspex.wiki.mark.json.Json;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/** Last successful publish, keyed by instance and remote page identity. */
public record PublishBaseline(String storageHash, long versionNumber) {
  public static PublishBaseline read(Path directory, String site, String id) throws IOException {
    if (directory == null) return null;
    Path file = file(directory, site, id);
    if (!Files.exists(file)) return null;
    try {
      var value = (Map<?, ?>) Json.parse(Files.readString(file));
      String hash = (String) value.get("storageHash");
      long version = ((Number) value.get("versionNumber")).longValue();
      return hash.matches("[a-f0-9]{40}") && version > 0
          ? new PublishBaseline(hash, version)
          : null;
    } catch (RuntimeException e) {
      return null; // Corrupt state never authorizes an overwrite.
    }
  }

  public void save(Path directory, String site, String id) throws IOException {
    if (directory == null) return;
    Files.createDirectories(directory);
    Path destination = file(directory, site, id);
    Path temp = Files.createTempFile(directory, "baseline-", ".tmp");
    try {
      Files.writeString(
          temp,
          Json.write(Map.of("storageHash", storageHash, "versionNumber", versionNumber)) + "\n");
      try {
        Files.move(
            temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static Path file(Path directory, String site, String id) {
    return directory.resolve(
        ContentHash.contentHash(site.replaceAll("/+$", "") + "\n" + id) + ".json");
  }

  /** Import explicitly accepts the fetched remote body and invalidates older merge history. */
  public void saveImported(Path directory, String site, String id) throws IOException {
    if (directory == null) return;
    Path baseline = file(directory, site, id);
    String name = baseline.getFileName().toString();
    Files.deleteIfExists(baseline.resolveSibling(name.replace(".json", ".merge.json")));
    save(directory, site, id);
  }

  /** Exact same-version local evidence takes precedence over a copied version-message tag. */
  public static String resolve(
      String tag, long remoteVersion, PublishBaseline state, String remoteHash) {
    if (state != null
        && state.versionNumber == remoteVersion
        && state.storageHash.equals(remoteHash)) return state.storageHash;
    return resolve(tag, remoteVersion, state);
  }

  /** Conflicting same-version evidence and state from the future are not trusted. */
  public static String resolve(String tag, long remoteVersion, PublishBaseline state) {
    if (state == null) return tag;
    if (state.versionNumber > remoteVersion) return "";
    if (!tag.isEmpty()) {
      if (state.versionNumber == remoteVersion && !tag.equals(state.storageHash)) return "";
      return tag;
    }
    return state.storageHash;
  }
}
