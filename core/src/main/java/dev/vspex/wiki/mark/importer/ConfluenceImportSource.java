package dev.vspex.wiki.mark.importer;

import dev.vspex.wiki.mark.client.ConfluenceClient;
import dev.vspex.wiki.mark.client.ConfluenceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts the existing client's JSON maps to validated, read-only snapshots. */
public final class ConfluenceImportSource implements PageImportSource {
  private final ConfluenceClient client;

  public ConfluenceImportSource(ConfluenceClient client) {
    this.client = client.forReadOnlyImport();
  }

  @Override
  public String baseUrl() {
    return client.baseUrl();
  }

  @Override
  public PageSnapshot readPage(String id) {
    try {
      var page = client.getPageById(id, "body.storage,space,ancestors,version");
      if (!"page".equals(page.get("type")))
        throw new ImportException("Selected content is not a page");
      if (!id.equals(string(page, "id")))
        throw new ImportException("Server returned a different page ID");
      Object storage = map(map(page.get("body")).get("storage")).get("value");
      if (!(storage instanceof String body))
        throw new ImportException("Page has no storage representation");
      String title = string(page, "title");
      String space = string(map(page.get("space")), "key");
      Object version = map(page.get("version")).get("number");
      if (title.isEmpty()
          || space.isEmpty()
          || !(version instanceof Number number)
          || number.longValue() < 1) {
        throw new ImportException("Page response is missing title, space, or version");
      }
      List<String> ancestors = new ArrayList<>();
      if (!(page.get("ancestors") instanceof List<?> parents))
        throw new ImportException("Page response has no expanded ancestors");
      for (Object parent : parents) {
        String parentTitle = string(map(parent), "title");
        if (parentTitle.isEmpty()) throw new ImportException("Ancestor is missing its title");
        ancestors.add(parentTitle);
      }
      return new PageSnapshot(
          id, title, space, number.longValue(), body, ancestors, client.getLabels(id));
    } catch (ConfluenceException e) {
      throw failure("Read page", e);
    }
  }

  @Override
  public List<AttachmentSnapshot> attachments(String pageId) {
    try {
      List<AttachmentSnapshot> result = new ArrayList<>();
      for (var item : client.getAttachments(pageId)) {
        String id = string(item, "id");
        String filename = string(item, "title");
        String download = string(map(item.get("_links")), "download");
        if (id.isEmpty() || filename.isEmpty())
          throw new ImportException("Attachment response is missing ID or filename");
        result.add(new AttachmentSnapshot(id, filename, download));
      }
      return result;
    } catch (ConfluenceException e) {
      throw failure("List attachments", e);
    }
  }

  @Override
  public byte[] download(AttachmentSnapshot attachment) {
    try {
      return client.downloadForImport(attachment.downloadUrl());
    } catch (ConfluenceException e) {
      // The bounded downloader emits fixed diagnostics, never server bodies or URLs.
      throw new ImportException("Download attachment " + attachment.id() + ": " + e.getMessage());
    }
  }

  @Override
  public long downloadTo(
      AttachmentSnapshot attachment, java.nio.file.Path destination, long remaining) {
    try {
      return client.downloadForImport(attachment.downloadUrl(), destination, remaining);
    } catch (ConfluenceException e) {
      throw new ImportException("Download attachment: " + e.getMessage());
    }
  }

  private static ImportException failure(String operation, ConfluenceException e) {
    return new ImportException(
        operation
            + (e.status() == null
                ? ": request failed (check connection, TLS, download route and size limit)"
                : ": HTTP " + e.status()));
  }

  private static Map<?, ?> map(Object value) {
    return value instanceof Map<?, ?> map ? map : Map.of();
  }

  private static String string(Map<?, ?> map, String key) {
    return map.get(key) instanceof String s ? s : "";
  }
}
