package dev.vspex.wiki.mark.importer;

import java.nio.file.Path;
import java.util.List;

/** Completed import or a write-free preview. */
public record ImportReport(
    Path document,
    String pageId,
    long version,
    String markdown,
    List<Path> files,
    List<String> warnings,
    int assetCount,
    boolean preview,
    String baselineStatus) {
  public ImportReport {
    files = List.copyOf(files);
    warnings = List.copyOf(warnings);
  }

  public String summary() {
    return (preview ? "Preview: " : "Imported: ")
        + document
        + " (page "
        + pageId
        + ", version "
        + version
        + ", "
        + assetCount
        + " assets)";
  }
}
