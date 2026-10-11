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
  /** Creates the extension. Gradle supplies the managed properties. */
  public ConfluenceExtension() {}

  /**
   * Confluence base URL, e.g. {@code https://wiki.example.com}. Required for publish only.
   *
   * @return the base URL property
   */
  public abstract Property<String> getBaseUrl();

  /**
   * API token. Defaults to {@code -Pconfluence.token}, else {@code $CONFLUENCE_TOKEN}. Never
   * logged, never fingerprinted.
   *
   * @return the API token property
   */
  public abstract Property<String> getAuthToken();

  /**
   * Username; present means Basic auth, absent means Bearer token..
   *
   * @return the username property
   */
  public abstract Property<String> getUsername();

  /**
   * Default space key when a page has no {@code Space} header.
   *
   * @return the default space key property
   */
  public abstract Property<String> getSpace();

  /**
   * Directory holding the wiki pages. Defaults to {@code documentation/wiki}.
   *
   * @return the pages directory property
   */
  public abstract DirectoryProperty getPagesDir();

  /**
   * Page patterns relative to {@code pagesDir}. Defaults to every Markdown file, recursively.
   *
   * @return the page patterns property
   */
  public abstract ListProperty<String> getPageIncludes();

  /**
   * Max image height in pixels; per-file header wins.
   *
   * @return the max image height property
   */
  public abstract Property<String> getImageHeight();

  /**
   * Max image width in pixels; per-file header wins.
   *
   * @return the max image width property
   */
  public abstract Property<String> getImageWidth();

  /**
   * Image alignment ({@code left}, {@code center}, {@code right}); per-file header wins.
   *
   * @return the image alignment property
   */
  public abstract Property<String> getImageAlign();

  /**
   * Content appearance ({@code full-width}, {@code fixed}, {@code default}) when unset per page.
   *
   * @return the content appearance property
   */
  public abstract Property<String> getContentAppearance();

  /**
   * Take the page title from the leading H1. Mutually exclusive with {@code titleFromFilename}.
   *
   * @return the title-from-H1 flag property
   */
  public abstract Property<Boolean> getTitleFromH1();

  /**
   * Take the page title from the file name. Mutually exclusive with {@code titleFromH1}.
   *
   * @return the title-from-filename flag property
   */
  public abstract Property<Boolean> getTitleFromFilename();

  /**
   * Append a short parents/space/title hash to each title.
   *
   * @return the title-hash flag property
   */
  public abstract Property<Boolean> getTitleAppendHash();

  /**
   * Do not publish the first H1 heading.
   *
   * @return the drop-H1 flag property
   */
  public abstract Property<Boolean> getDropH1();

  /**
   * Parent pages prepended to each document's own parents.
   *
   * @return the parents property
   */
  public abstract ListProperty<String> getParents();

  /**
   * Place each page under pages named after its directories.
   *
   * @return the parents-from-path flag property
   */
  public abstract Property<Boolean> getParentsFromPath();

  /**
   * Directory {@code parentsFromPath} measures from. Defaults to {@code pagesDir}.
   *
   * @return the parents-from-path root property
   */
  public abstract Property<String> getParentsFromPathRoot();

  /**
   * Add labels without removing existing ones.
   *
   * @return the append-labels flag property
   */
  public abstract Property<Boolean> getAppendLabels();

  /**
   * Do not send notifications on update.
   *
   * @return the minor-edit flag property
   */
  public abstract Property<Boolean> getMinorEdit();

  /**
   * Page version message.
   *
   * @return the version message property
   */
  public abstract Property<String> getVersionMessage();

  /**
   * Skip pages whose content hash already matches the remote version.
   *
   * @return the changes-only flag property
   */
  public abstract Property<Boolean> getChangesOnly();

  /**
   * Upload local files that links point at.
   *
   * @return the attach-referenced flag property
   */
  public abstract Property<Boolean> getAttachReferenced();

  /**
   * Keep processing remaining files after a failure.
   *
   * @return the continue-on-error flag property
   */
  public abstract Property<Boolean> getContinueOnError();

  /**
   * Publish only pages changed since the state file (the config form of the task's {@code
   * --changed} option). Unlike {@code changesOnly}, which compares each page against the remote
   * wiki, this filters the selection locally before anything runs.
   *
   * @return the only-changed flag property
   */
  public abstract Property<Boolean> getOnlyChanged();

  /**
   * When true, {@code confluenceCompile} joins {@code check} (publish never does).
   *
   * @return the verify-on-check flag property
   */
  public abstract Property<Boolean> getVerifyOnCheck();

  /**
   * Extra trust store augmenting the JVM store.
   *
   * @return the trust store property
   */
  public abstract RegularFileProperty getTrustStore();

  /**
   * Trust store password. Never logged, never fingerprinted.
   *
   * @return the trust store password property
   */
  public abstract Property<String> getTrustStorePassword();

  /**
   * Skip TLS verification. Loud warning on every use; testing only.
   *
   * @return the skip-TLS-verification flag property
   */
  public abstract Property<Boolean> getInsecureSkipTlsVerify();

  /**
   * Diagram rendering settings (the {@code diagrams} block).
   *
   * @return the diagrams extension
   */
  @Nested
  public abstract ConfluenceDiagramsExtension getDiagrams();

  /**
   * Configures the {@code diagrams} block.
   *
   * @param action configures the diagrams extension
   */
  public void diagrams(Action<? super ConfluenceDiagramsExtension> action) {
    action.execute(getDiagrams());
  }
}
