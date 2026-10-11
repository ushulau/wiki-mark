package com.wiki.mark.gradle;

import org.gradle.api.Action;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Nested;

/**
 * The {@code confluence} extension: connection, page selection, rendering defaults, and safety
 * flags. All lazy; unset optionals are simply omitted (never assign {@code null}).
 */
public abstract class ConfluenceExtension {
  /** Confluence base URL, e.g. {@code https://wiki.example.com}. Required for publish only. */
  public abstract Property<String> getBaseUrl();

  /**
   * API token. Defaults to {@code -Pconfluence.token}, else {@code $CONFLUENCE_TOKEN}. Never
   * logged, never fingerprinted.
   */
  public abstract Property<String> getAuthToken();

  /** Username; present means Basic auth, absent means Bearer token. */
  public abstract Property<String> getUsername();

  /** Default space key when a page has no {@code Space} header. */
  public abstract Property<String> getSpace();

  /** Directory holding the wiki pages. Defaults to {@code documentation/wiki}. */
  public abstract DirectoryProperty getPagesDir();

  /** Page patterns relative to {@code pagesDir}. Defaults to every Markdown file, recursively. */
  public abstract ListProperty<String> getPageIncludes();

  /** Max image height in pixels; per-file header wins. */
  public abstract Property<String> getImageHeight();

  /** Max image width in pixels; per-file header wins. */
  public abstract Property<String> getImageWidth();

  /** Image alignment ({@code left}, {@code center}, {@code right}); per-file header wins. */
  public abstract Property<String> getImageAlign();

  /**
   * Content appearance ({@code full-width}, {@code fixed}, {@code default}) when unset per page.
   */
  public abstract Property<String> getContentAppearance();

  /** Take the page title from the leading H1. Mutually exclusive with {@code titleFromFilename}. */
  public abstract Property<Boolean> getTitleFromH1();

  /** Take the page title from the file name. Mutually exclusive with {@code titleFromH1}. */
  public abstract Property<Boolean> getTitleFromFilename();

  /** Append a short parents/space/title hash to each title. */
  public abstract Property<Boolean> getTitleAppendHash();

  /** Do not publish the first H1 heading. */
  public abstract Property<Boolean> getDropH1();

  /** Parent pages prepended to each document's own parents. */
  public abstract ListProperty<String> getParents();

  /** Place each page under pages named after its directories. */
  public abstract Property<Boolean> getParentsFromPath();

  /** Directory {@code parentsFromPath} measures from. Defaults to {@code pagesDir}. */
  public abstract Property<String> getParentsFromPathRoot();

  /** Add labels without removing existing ones. */
  public abstract Property<Boolean> getAppendLabels();

  /** Do not send notifications on update. */
  public abstract Property<Boolean> getMinorEdit();

  /** Page version message. */
  public abstract Property<String> getVersionMessage();

  /** Skip pages whose content hash already matches the remote version. */
  public abstract Property<Boolean> getChangesOnly();

  /** Upload local files that links point at. */
  public abstract Property<Boolean> getAttachReferenced();

  /** Keep processing remaining files after a failure. */
  public abstract Property<Boolean> getContinueOnError();

  /** When true, {@code confluenceCompile} joins {@code check} (publish never does). */
  public abstract Property<Boolean> getVerifyOnCheck();

  /** Extra trust store augmenting the JVM store. */
  public abstract RegularFileProperty getTrustStore();

  /** Trust store password. Never logged, never fingerprinted. */
  public abstract Property<String> getTrustStorePassword();

  /** Skip TLS verification. Loud warning on every use; testing only. */
  public abstract Property<Boolean> getInsecureSkipTlsVerify();

  /** Diagram rendering settings (the {@code diagrams} block). */
  @Nested
  public abstract ConfluenceDiagramsExtension getDiagrams();

  /** Configures the {@code diagrams} block. */
  public void diagrams(Action<? super ConfluenceDiagramsExtension> action) {
    action.execute(getDiagrams());
  }
}
