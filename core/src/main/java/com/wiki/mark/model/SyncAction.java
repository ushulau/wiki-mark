package com.wiki.mark.model;

/** What syncing one file did. Wire names match the Python {@code sync_file} actions. */
public enum SyncAction {
  CREATED("created"),
  UPDATED("updated"),
  UNCHANGED("unchanged"),
  SKIPPED("skipped"),
  COMPILED("compiled"),
  DRY_RUN("dry-run"),
  FAILED("failed");

  private final String wireName;

  SyncAction(String wireName) {
    this.wireName = wireName;
  }

  /** Lowercase action name used in logs and reports. */
  public String wireName() {
    return wireName;
  }
}
