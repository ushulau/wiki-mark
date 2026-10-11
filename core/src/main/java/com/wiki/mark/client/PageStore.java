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
  /**
   * Base URL of the wiki, used to build page links.
   *
   * @return the wiki base URL
   */
  String baseUrl();

  /**
   * Live page by title and type, or null.
   *
   * @param space the space key
   * @param title the page title
   * @param type the page type
   * @return the page map, or null when absent
   */
  Map<String, Object> findPage(String space, String title, String type);

  /**
   * Page by id, with ancestors, version and storage body.
   *
   * @param pageId the remote page id
   * @return the page map
   */
  Map<String, Object> getPageById(String pageId);

  /**
   * Creates a page, optionally under {@code parentId} (may be null).
   *
   * @param space the space key
   * @param title the page title
   * @param body the storage body
   * @param type the page type
   * @param parentId the parent page id, or null for a top-level page
   * @return the created page map
   */
  Map<String, Object> createPage(
      String space, String title, String body, String type, String parentId);

  /**
   * Updates a page's content, retrying one 409 conflict afresh.
   *
   * @param page the current page map
   * @param content the new storage body
   * @param minorEdit whether to suppress notifications
   * @param versionMessage the version message
   * @param appearance the content appearance
   * @param emoji the page emoji
   * @return the updated page map
   */
  Map<String, Object> updatePage(
      Map<String, Object> page,
      String content,
      boolean minorEdit,
      String versionMessage,
      String appearance,
      String emoji);

  /**
   * Uploads {@code localPath} unless an identical attachment exists.
   *
   * @param pageId the remote page id
   * @param localPath the file to upload
   * @param comment the attachment comment
   * @return the attachment info plus whether anything was uploaded
   */
  EnsureResult ensureAttachment(String pageId, Path localPath, String comment);

  /**
   * Makes the page carry exactly {@code labels} (or adds them with append).
   *
   * @param pageId the remote page id
   * @param labels the labels to set
   * @param append whether to keep existing labels
   */
  void syncLabels(String pageId, List<String> labels, boolean append);
}
