package dev.vspex.wiki.mark.importer;

import java.util.List;

/** Read-only remote surface used by imports. */
public interface PageImportSource {
  String baseUrl();

  PageSnapshot readPage(String pageId);

  List<AttachmentSnapshot> attachments(String pageId);

  byte[] download(AttachmentSnapshot attachment);

  /**
   * Streams an asset into a temporary file; legacy sources may use the bounded byte-array fallback.
   */
  default long downloadTo(
      AttachmentSnapshot attachment, java.nio.file.Path destination, long remaining)
      throws java.io.IOException {
    byte[] data = download(attachment);
    if (data.length > 50L * 1024 * 1024 || data.length > remaining)
      throw new ImportException(
          "Attachment downloads exceed 50 MiB per asset or 200 MiB per import");
    java.nio.file.Files.write(destination, data);
    return data.length;
  }

  record PageSnapshot(
      String id,
      String title,
      String space,
      long version,
      String storage,
      List<String> ancestors,
      List<String> labels) {
    public PageSnapshot {
      ancestors = List.copyOf(ancestors);
      labels = List.copyOf(labels);
    }
  }

  record AttachmentSnapshot(String id, String filename, String downloadUrl) {}
}
