package dev.vspex.wiki.mark.gradle;

import dev.vspex.wiki.mark.client.ConfluenceClient;
import dev.vspex.wiki.mark.importer.ConfluenceImportSource;
import dev.vspex.wiki.mark.importer.ImportOptions;
import dev.vspex.wiki.mark.importer.PageImporter;
import java.io.IOException;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.tasks.options.Option;

/** Explicit, read-only Confluence snapshot import; remote inputs are never cached. */
@UntrackedTask(because = "Reads live Confluence content which may change independently")
public abstract class ConfluenceImportTask extends DefaultTask {
  /** Creates the task; Gradle supplies its managed properties. */
  public ConfluenceImportTask() {}

  /**
   * The Confluence site URL, including its context path.
   *
   * @return the Confluence site URL, including its context path
   */
  @Internal
  public abstract Property<String> getBaseUrl();

  /**
   * The API token, excluded from fingerprints and logs.
   *
   * @return the API token, excluded from fingerprints and logs
   */
  @Internal
  public abstract Property<String> getAuthToken();

  /**
   * The Basic auth username, or empty for Bearer authentication.
   *
   * @return the Basic auth username, or empty for Bearer authentication
   */
  @Internal
  public abstract Property<String> getUsername();

  /**
   * The optional trust store augmenting the JVM trust store.
   *
   * @return the optional trust store augmenting the JVM trust store
   */
  @Internal
  public abstract RegularFileProperty getTrustStore();

  /**
   * The trust store password, excluded from fingerprints and logs.
   *
   * @return the trust store password, excluded from fingerprints and logs
   */
  @Internal
  public abstract Property<String> getTrustStorePassword();

  /**
   * Whether TLS verification is disabled, default false.
   *
   * @return whether TLS verification is disabled, default false
   */
  @Internal
  public abstract Property<Boolean> getInsecureSkipTlsVerify();

  /**
   * The project directory containing the wiki output root.
   *
   * @return the project directory containing the wiki output root
   */
  @Internal
  public abstract DirectoryProperty getProjectDir();

  /**
   * The wiki root directory import output resolves beneath. Defaults to the {@code confluence}
   * extension's pages directory.
   *
   * @return the wiki root directory property
   */
  @Internal
  public abstract DirectoryProperty getWikiDir();

  /**
   * The numeric page selector, mutually exclusive with pageUrl.
   *
   * @return the numeric page selector, mutually exclusive with pageUrl
   */
  @Internal
  public abstract Property<String> getPageId();

  /**
   * The same-site page URL selector, mutually exclusive with pageId.
   *
   * @return the same-site page URL selector, mutually exclusive with pageId
   */
  @Internal
  public abstract Property<String> getPageUrl();

  /**
   * The relative Markdown path, or empty for a generated title-and-ID filename.
   *
   * @return the relative Markdown path, or empty for a generated title-and-ID filename
   */
  @Internal
  public abstract Property<String> getOutput();

  /**
   * Whether files in this import's output set may be replaced, default false.
   *
   * @return whether files in this import's output set may be replaced, default false
   */
  @Internal
  public abstract Property<Boolean> getOverwrite();

  /**
   * Whether to print a write-free preview without downloading asset bodies.
   *
   * @return whether to print a write-free preview without downloading asset bodies
   */
  @Internal
  public abstract Property<Boolean> getPreview();

  /**
   * Whether to write raw storage markup instead of converting to Markdown, default false.
   *
   * @return whether lossless mode was requested
   */
  @Internal
  public abstract Property<Boolean> getLossless();

  /**
   * Whether to recompile the new file in memory and fail on any byte difference, default false.
   *
   * @return whether verification was requested
   */
  @Internal
  public abstract Property<Boolean> getVerify();

  /**
   * Retarget space for the import, or empty to keep the source space.
   *
   * @return the retarget space property
   */
  @Internal
  public abstract Property<String> getSpace();

  /**
   * Retarget title for the import, or empty to keep the source title.
   *
   * @return the retarget title property
   */
  @Internal
  public abstract Property<String> getTitle();

  /**
   * Retarget parents for the import (outermost first), or empty to keep the source ancestors.
   *
   * @return the retarget parents property
   */
  @Internal
  @Option(
      option = "parent",
      description = "Retarget under another parent. Repeatable, outermost first.")
  public abstract ListProperty<String> getParents();

  /**
   * Sets the corresponding command-line option.
   *
   * @param value the numeric ID from --page-id
   */
  @Option(option = "page-id", description = "Numeric ID of the page to import")
  public void pageId(String value) {
    getPageId().set(value);
  }

  /**
   * Sets the corresponding command-line option.
   *
   * @param value the same-site URL from --page-url
   */
  @Option(option = "page-url", description = "Same-site Confluence page URL")
  public void pageUrl(String value) {
    getPageUrl().set(value);
  }

  /**
   * Sets the corresponding command-line option.
   *
   * @param value the relative Markdown path from --output
   */
  @Option(option = "output", description = "Relative .md path beneath the wiki directory")
  public void output(String value) {
    getOutput().set(value);
  }

  /**
   * Sets the corresponding command-line option.
   *
   * @param value whether --overwrite was requested
   */
  @Option(option = "overwrite", description = "Replace existing files in this import's output set")
  public void overwrite(boolean value) {
    getOverwrite().set(value);
  }

  /**
   * Sets the corresponding command-line option.
   *
   * @param value whether --preview was requested
   */
  @Option(
      option = "preview",
      description = "Show Markdown and paths without writing files or downloading assets")
  public void preview(boolean value) {
    getPreview().set(value);
  }

  /**
   * Sets the corresponding command-line option.
   *
   * @param value whether --lossless was requested
   */
  @Option(option = "lossless", description = "Write raw storage markup without Markdown conversion")
  public void lossless(boolean value) {
    getLossless().set(value);
  }

  /**
   * Sets the corresponding command-line option.
   *
   * @param value whether --verify was requested
   */
  @Option(option = "verify", description = "Recompile in memory and fail on any byte difference")
  public void verify(boolean value) {
    getVerify().set(value);
  }

  /**
   * Sets the corresponding command-line option.
   *
   * @param value the space key from --space
   */
  @Option(option = "space", description = "Retarget the import into another space")
  public void space(String value) {
    getSpace().set(value);
  }

  /**
   * Sets the corresponding command-line option.
   *
   * @param value the title from --title
   */
  @Option(option = "title", description = "Retarget the import with another title")
  public void title(String value) {
    getTitle().set(value);
  }

  /** Fetches and converts the selected page, then reports the preview or committed output. */
  @TaskAction
  public void importPage() {
    if (getBaseUrl().get().isEmpty()) throw new GradleException("confluence.baseUrl is required");
    if (getAuthToken().getOrElse("").isEmpty())
      throw new GradleException("Confluence API token is required");
    ConfluenceClient client =
        new ConfluenceClient(getBaseUrl().get(), getUsername().get(), getAuthToken().get());
    if (getInsecureSkipTlsVerify().get()) client.setInsecureSkipTlsVerify(true);
    else if (getTrustStore().isPresent())
      client.setTrustStore(
          getTrustStore().get().getAsFile().toPath(), getTrustStorePassword().get());
    try {
      var report =
          PageImporter.run(
              getProjectDir().get().getAsFile().toPath(),
              new ImportOptions(
                  getPageId().get(),
                  getPageUrl().get(),
                  getOutput().get(),
                  getOverwrite().get(),
                  getPreview().get(),
                  getLossless().getOrElse(false),
                  getVerify().getOrElse(false),
                  getSpace().getOrElse(""),
                  getParents().getOrElse(List.of()),
                  getTitle().getOrElse(""),
                  getWikiDir().get().getAsFile().toPath().toString()),
              new ConfluenceImportSource(client));
      getLogger().lifecycle(report.summary());
      if (report.preview()) {
        report.files().forEach(path -> getLogger().lifecycle("Would write: {}", path));
        getLogger().lifecycle(report.markdown());
      }
      report.warnings().forEach(warning -> getLogger().warn("Import: {}", warning));
      report.files().stream()
          .filter(path -> path.toString().endsWith(".confluence-storage.xml"))
          .forEach(path -> getLogger().lifecycle("Original storage: {}", path));
    } catch (IOException | RuntimeException e) {
      throw new GradleException("Confluence import failed: " + e.getMessage());
    }
  }
}
