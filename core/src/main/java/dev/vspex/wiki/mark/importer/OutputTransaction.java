package dev.vspex.wiki.mark.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stages a bounded output set, rolls back failed commits, and installs Markdown last. */
final class OutputTransaction {
  interface Mover {
    void move(Path from, Path to) throws IOException;
  }

  private final Mover mover;

  OutputTransaction() {
    this((from, to) -> Files.move(from, to));
  }

  OutputTransaction(Mover mover) {
    this.mover = mover;
  }

  void write(Path project, Map<Path, byte[]> files, boolean overwrite) throws IOException {
    write(project, files, overwrite, Map.of());
  }

  void write(Path project, Map<Path, byte[]> files, boolean overwrite, Map<Path, Path> downloads)
      throws IOException {
    Path parent = files.keySet().iterator().next().getParent();
    ImportPaths.check(project, parent);
    Files.createDirectories(parent);
    Path stage = Files.createTempDirectory(parent, ".confluence-import-");
    List<Path> installed = new ArrayList<>();
    Map<Path, Path> backups = new LinkedHashMap<>();
    boolean keepStage = false;
    try {
      int index = 0;
      Map<Path, Path> staged = new LinkedHashMap<>();
      for (var entry : files.entrySet()) {
        Path file = stage.resolve("new-" + index++);
        if (downloads.containsKey(entry.getKey())) Files.copy(downloads.get(entry.getKey()), file);
        else Files.write(file, entry.getValue());
        staged.put(entry.getKey(), file);
      }
      for (var entry : staged.entrySet()) {
        Path destination = entry.getKey();
        ImportPaths.check(project, destination);
        Files.createDirectories(destination.getParent());
        if (Files.exists(destination)) {
          if (!overwrite || !Files.isRegularFile(destination))
            throw new IOException("Output already exists: " + destination);
          Path backup = stage.resolve("backup-" + backups.size());
          mover.move(destination, backup);
          backups.put(destination, backup);
        }
        mover.move(entry.getValue(), destination);
        installed.add(destination);
      }
    } catch (IOException | RuntimeException failure) {
      try {
        for (int i = installed.size() - 1; i >= 0; i--) Files.deleteIfExists(installed.get(i));
        for (var entry : backups.entrySet()) mover.move(entry.getValue(), entry.getKey());
      } catch (IOException | RuntimeException rollback) {
        keepStage = true;
        throw new ImportException(
            "Import commit and rollback failed; recovery files retained at " + stage);
      }
      throw failure;
    } finally {
      if (!keepStage) {
        try (var paths = Files.walk(stage)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            Files.deleteIfExists(path);
        }
      }
    }
  }
}
