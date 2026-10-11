package com.wiki.mark.sync;

import com.wiki.mark.client.PageStore;
import java.util.List;
import java.util.Map;

/** Parent-chain helper. Port of {@code mark.cli.ensure_parent_chain}. */
public final class ParentChain {
  private ParentChain() {}

  /**
   * Finds or creates each parent in turn; returns the deepest parent's id (or null when there are
   * no parents).
   *
   * <p>Under {@code dryRun} nothing is created: the first missing parent is reported and the
   * deepest <em>existing</em> parent's id is returned (or null).
   *
   * @param client the page store
   * @param space the space key
   * @param type the page type
   * @param parents the parent titles in order
   * @param dryRun whether to only report missing parents
   * @param log the log
   * @return the deepest parent id, or null when there are no parents
   */
  public static String ensureParentChain(
      PageStore client, String space, String type, List<String> parents, boolean dryRun, Log log) {
    String parentId = null;
    for (String title : parents) {
      Map<String, Object> page = client.findPage(space, title, type);
      if (page == null) {
        if (dryRun) {
          log.info("would create parent page '" + title + "' in space " + space);
          return parentId;
        }
        page = client.createPage(space, title, "", type, parentId);
        log.info("created parent page '" + title + "' in space " + space);
      }
      parentId = String.valueOf(page.get("id"));
    }
    return parentId;
  }
}
