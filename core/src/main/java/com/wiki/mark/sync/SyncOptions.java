package com.wiki.mark.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * Effective options for one sync run: the retained subset of the Python {@code Config}.
 *
 * <p>Dropped options ({@code targetUrl}, {@code ci}, output formats, TOML/env/password-command,
 * accepted-but-unimplemented flags) are intentionally absent: file selection comes from the caller
 * and the run report is a plain object. Fields are mutable and public like the other option bags.
 */
public class SyncOptions {
  public String space = "";
  public List<String> parents = new ArrayList<>();
  public boolean parentsFromPath = false;

  /**
   * Directory {@code parentsFromPath} measures from. The Gradle task passes the pages dir (or its
   * override); the CLI passes the glob root. Empty means no root (last-two-directories fallback).
   */
  public String parentsFromPathRoot = "";

  public boolean titleFromH1 = false;
  public boolean titleFromFilename = false;
  public boolean titleAppendHash = false;
  public boolean dropH1 = false;

  /** Accepted for parity; the renderer never emits raw linebreaks, so always a no-op. */
  public boolean stripLinebreaks = false;

  public boolean compileOnly = false;
  public boolean dryRun = false;
  public boolean minorEdit = false;
  public String versionMessage = "";
  public boolean changesOnly = false;
  public boolean continueOnError = false;
  public String imageAlign = "";
  public String imageWidth = "";
  public String imageHeight = "";
  public String contentAppearance = "";
  public boolean appendLabels = false;
  public boolean attachReferenced = false;
}
