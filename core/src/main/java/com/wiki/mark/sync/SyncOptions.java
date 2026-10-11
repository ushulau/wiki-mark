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
  /** Creates options with defaults. */
  public SyncOptions() {}

  /** Default space key when a page has no {@code Space} header. */
  public String space = "";

  /** Parent pages prepended to each document's own parents. */
  public List<String> parents = new ArrayList<>();

  /** Place each page under pages named after its directories. */
  public boolean parentsFromPath = false;

  /**
   * Directory {@code parentsFromPath} measures from. The Gradle task passes the pages dir (or its
   * override); the CLI passes the glob root. Empty means no root (last-two-directories fallback).
   */
  public String parentsFromPathRoot = "";

  /** Take the page title from the leading H1. Mutually exclusive with {@code titleFromFilename}. */
  public boolean titleFromH1 = false;

  /** Take the page title from the file name. Mutually exclusive with {@code titleFromH1}. */
  public boolean titleFromFilename = false;

  /** Append a short parents/space/title hash to each title. */
  public boolean titleAppendHash = false;

  /** Do not publish the first H1 heading. */
  public boolean dropH1 = false;

  /** Accepted for parity; the renderer never emits raw linebreaks, so always a no-op. */
  public boolean stripLinebreaks = false;

  /** Convert pages without touching the network. */
  public boolean compileOnly = false;

  /** Resolve ancestry and print storage without writing. */
  public boolean dryRun = false;

  /** Do not send notifications on update. */
  public boolean minorEdit = false;

  /** Page version message. */
  public String versionMessage = "";

  /** Skip pages whose content hash already matches the remote version. */
  public boolean changesOnly = false;

  /** Keep processing remaining files after a failure. */
  public boolean continueOnError = false;

  /** Image alignment; per-file header wins. */
  public String imageAlign = "";

  /** Max image width in pixels; per-file header wins. */
  public String imageWidth = "";

  /** Max image height in pixels; per-file header wins. */
  public String imageHeight = "";

  /** Content appearance when unset per page. */
  public String contentAppearance = "";

  /** Add labels without removing existing ones. */
  public boolean appendLabels = false;

  /** Upload local files that links point at. */
  public boolean attachReferenced = false;
}
