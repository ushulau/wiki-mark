package com.wiki.mark.model;

/** What syncing one file did. Wire names match the Python {@code sync_file} actions. */
public enum SyncAction {
  /** The page was created remotely. */
  CREATED("created"),
  /** The page was updated remotely. */
  UPDATED("updated"),
  /** The remote page already matched; nothing was written. */
  UNCHANGED("unchanged"),
  /** The file was left out of the run. */
  SKIPPED("skipped"),
  /** The file was rendered offline without any remote call. */
  COMPILED("compiled"),
  /** The run resolved ancestry and printed storage without writing. */
  DRY_RUN("dry-run"),
  /** The file failed to sync; see the reason. */
  FAILED("failed");

  private final String wireName;

  SyncAction(String wireName) {
    this.wireName = wireName;
  }

  /**
   * Lowercase action name used in logs and reports.
   *
   * @return the wire name
   */
  public String wireName() {
    return wireName;
  }
}
