package dev.vspex.wiki.mark.importer;

import java.util.List;

/** Selection and local output options shared by the CLI and Gradle task. */
public record ImportOptions(
    String pageId,
    String pageUrl,
    String output,
    boolean overwrite,
    boolean preview,
    boolean lossless,
    boolean verify,
    String space,
    List<String> parents,
    String title,
    String wikiDir) {
  public ImportOptions {
    parents = List.copyOf(parents);
  }

  /** Markdown-mode options; lossless off, no retargeting, default wiki directory. */
  public ImportOptions(
      String pageId, String pageUrl, String output, boolean overwrite, boolean preview) {
    this(pageId, pageUrl, output, overwrite, preview, false, false, "", List.of(), "", "");
  }
}
