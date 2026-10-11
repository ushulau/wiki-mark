package com.wiki.mark.model;

/**
 * Outcome of syncing one file: the action plus identifying details.
 *
 * <p>Port of the Python {@code SyncResult} action string plus {@code info} dict. Unused fields are
 * {@code ""}: a compiled file has no page id or URL, and only skipped/failed results carry a
 * reason.
 */
public record SyncResult(
    SyncAction action,
    String file,
    String space,
    String title,
    String pageId,
    String url,
    String reason) {}
