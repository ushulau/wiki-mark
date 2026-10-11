package com.wiki.mark.client;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Narrow page/attachment/label surface the sync layer needs.
 *
 * <p>{@link ConfluenceClient} implements it; tests substitute an in-memory fake. Pages are the
 * client's {@code Map<String, Object>} shapes ({@code
 * id/title/type/version/ancestors/_links/body}).
 */
public interface PageStore {
  /** Base URL of the wiki, used to build page links. */
  String baseUrl();

  /** Live page by title and type, or null. */
  Map<String, Object> findPage(String space, String title, String type);

  /** Page by id, with ancestors, version and storage body. */
  Map<String, Object> getPageById(String pageId);

  /** Creates a page, optionally under {@code parentId} (may be null). */
  Map<String, Object> createPage(
      String space, String title, String body, String type, String parentId);

  /** Updates a page's content, retrying one 409 conflict afresh. */
  Map<String, Object> updatePage(
      Map<String, Object> page,
      String content,
      boolean minorEdit,
      String versionMessage,
      String appearance,
      String emoji);

  /** Uploads {@code localPath} unless an identical attachment exists. */
  EnsureResult ensureAttachment(String pageId, Path localPath, String comment);

  /** Makes the page carry exactly {@code labels} (or adds them with append). */
  void syncLabels(String pageId, List<String> labels, boolean append);
}
