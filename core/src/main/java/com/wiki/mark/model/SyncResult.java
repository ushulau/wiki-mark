package com.wiki.mark.model;

/**
 * Outcome of syncing one file: the action plus identifying details.
 *
 * <p>Port of the Python {@code SyncResult} action string plus {@code info} dict. Unused fields are
 * {@code ""}: a compiled file has no page id or URL, and only skipped/failed results carry a
 * reason.
 *
 * @param action what happened to the file
 * @param file the synced file, relative to the pages directory
 * @param space the Confluence space key
 * @param title the page title
 * @param pageId the remote page id, or {@code ""} when unknown
 * @param url the remote page URL, or {@code ""} when unknown
 * @param reason why the file was skipped or failed, or {@code ""} otherwise
 */
public record SyncResult(
    SyncAction action,
    String file,
    String space,
    String title,
    String pageId,
    String url,
    String reason) {}
