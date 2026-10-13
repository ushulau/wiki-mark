package dev.vspex.wiki.mark.gradle;

import dev.vspex.wiki.mark.client.PageStore;
import dev.vspex.wiki.mark.model.MetaException;
import dev.vspex.wiki.mark.sync.Log;
import dev.vspex.wiki.mark.sync.PageSyncer;
import dev.vspex.wiki.mark.sync.RunReport;
import dev.vspex.wiki.mark.sync.SyncException;
import dev.vspex.wiki.mark.sync.SyncOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/**
 * Syncs pages to Confluence. Untracked (remote writes are never up-to-date); the token and trust
 * store password stay {@code @Internal} so they are never fingerprinted or logged.
 */
@UntrackedTask(because = "Publishes to remote Confluence")
public abstract class ConfluencePublishTask extends DefaultTask {
  /** Creates the task. Gradle supplies the managed properties. */
  public ConfluencePublishTask() {}

  /**
   * Confluence base URL, e.g. {@code https://wiki.example.com}. Required for publish only.
   *
   * @return the base URL property
   */
  @Internal
  public abstract Property<String> getBaseUrl();

  /**
   * API token. Defaults to {@code -Pconfluence.token}, else {@code $CONFLUENCE_TOKEN}. Never
   * logged, never fingerprinted.
   *
   * @return the API token property
   */
  @Internal
  public abstract Property<String> getAuthToken();

  /**
   * Username; present means Basic auth, absent means token-only auth.
   *
   * @return the username property
   */
  @Internal
  public abstract Property<String> getUsername();

  /**
   * Default space key when a page has no {@code Space} header.
   *
   * @return the default space key property
   */
  @Internal
  public abstract Property<String> getSpace();

  /**
   * Directory holding the wiki pages. Defaults to {@code documentation/wiki}.
   *
   * @return the pages directory property
   */
  @Internal
  public abstract DirectoryProperty getPagesDir();

  /**
   * Project directory; locates the change-tracking state file.
   *
   * @return the project directory property
   */
  @Internal
  public abstract DirectoryProperty getProjectDir();

  /**
   * Page patterns relative to {@code pagesDir}. Defaults to every Markdown file, recursively.
   *
   * @return the page patterns property
   */
  @Internal
  public abstract ListProperty<String> getPageIncludes();

  /**
   * Max image height in pixels; per-file header wins.
   *
   * @return the max image height property
   */
  @Internal
  public abstract Property<String> getImageHeight();

  /**
   * Max image width in pixels; per-file header wins.
   *
   * @return the max image width property
   */
  @Internal
  public abstract Property<String> getImageWidth();

  /**
   * Image alignment ({@code left}, {@code center}, {@code right}); per-file header wins.
   *
   * @return the image alignment property
   */
  @Internal
  public abstract Property<String> getImageAlign();

  /**
   * Content appearance ({@code full-width}, {@code fixed}, {@code default}) when unset per page.
   *
   * @return the content appearance property
   */
  @Internal
  public abstract Property<String> getContentAppearance();

  /**
   * Take the page title from the leading H1. Mutually exclusive with {@code titleFromFilename}.
   *
   * @return the title-from-H1 flag property
   */
  @Internal
  public abstract Property<Boolean> getTitleFromH1();

  /**
   * Take the page title from the file name. Mutually exclusive with {@code titleFromH1}.
   *
   * @return the title-from-filename flag property
   */
  @Internal
  public abstract Property<Boolean> getTitleFromFilename();

  /**
   * Append a short parents/space/title hash to each title.
   *
   * @return the title-hash flag property
   */
  @Internal
  public abstract Property<Boolean> getTitleAppendHash();

  /**
   * Do not publish the first H1 heading.
   *
   * @return the drop-H1 flag property
   */
  @Internal
  public abstract Property<Boolean> getDropH1();

  /**
   * Parent pages prepended to each document's own parents.
   *
   * @return the parents property
   */
  @Internal
  public abstract ListProperty<String> getParents();

  /**
   * Place each page under pages named after its directories.
   *
   * @return the parents-from-path flag property
   */
  @Internal
  public abstract Property<Boolean> getParentsFromPath();

  /**
   * Directory {@code parentsFromPath} measures from. Defaults to {@code pagesDir}.
   *
   * @return the parents-from-path root property
   */
  @Internal
  public abstract Property<String> getParentsFromPathRoot();

  /**
   * Add labels without removing existing ones.
   *
   * @return the append-labels flag property
   */
  @Internal
  public abstract Property<Boolean> getAppendLabels();

  /**
   * Do not send notifications on update.
   *
   * @return the minor-edit flag property
   */
  @Internal
  public abstract Property<Boolean> getMinorEdit();

  /**
   * Page version message.
   *
   * @return the version message property
   */
  @Internal
  public abstract Property<String> getVersionMessage();

  /**
   * Skip pages whose content hash already matches the remote version.
   *
   * @return the changes-only flag property
   */
  @Internal
  public abstract Property<Boolean> getChangesOnly();

  /**
   * Upload local files that links point at.
   *
   * @return the attach-referenced flag property
   */
  @Internal
  public abstract Property<Boolean> getAttachReferenced();

  /**
   * Keep processing remaining files after a failure.
   *
   * @return the continue-on-error flag property
   */
  @Internal
  public abstract Property<Boolean> getContinueOnError();

  /**
   * Extra trust store augmenting the JVM store.
   *
   * @return the trust store property
   */
  @Internal
  public abstract RegularFileProperty getTrustStore();

  /**
   * Trust store password. Never logged, never fingerprinted.
   *
   * @return the trust store password property
   */
  @Internal
  public abstract Property<String> getTrustStorePassword();

  /**
   * Skip TLS verification. Loud warning on every use; testing only.
   *
   * @return the skip-TLS-verification flag property
   */
  @Internal
  public abstract Property<Boolean> getInsecureSkipTlsVerify();

  // Named "preview": Gradle reserves --dry-run for its own no-execution mode, which would
  // shadow a task option and fake-succeed without running the action.
  /**
   * Resolve ancestry and print storage without writing.
   *
   * @return the preview flag property
   */
  @Internal
  @Option(option = "preview", description = "Resolve ancestry and print storage without writing.")
  public abstract Property<Boolean> getPreview();

  /**
   * Publish only pages changed since the state file.
   *
   * @return the changed flag property
   */
  @Internal
  @Option(option = "changed", description = "Publish only pages changed since the state file.")
  public abstract Property<Boolean> getChanged();

  /**
   * Publish only these files, relative to pagesDir. Repeatable.
   *
   * @return the only-files property
   */
  @Internal
  @Option(
      option = "only",
      description = "Publish only these files, relative to pagesDir. Repeatable.")
  public abstract ListProperty<String> getOnlyFiles();

  /** Syncs the selected pages to Confluence. */
  @TaskAction
  public void publish() {
    try {
      run();
    } catch (SyncException | MetaException | IOException e) {
      throw new GradleException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
    }
  }

  private void run() throws IOException {
    Log log = new GradleLog(getLogger());
    String baseUrl = getBaseUrl().getOrElse("");
    if (baseUrl.isEmpty()) {
      throw new SyncException(
          "confluence.baseUrl is required for confluencePublish"
              + " (e.g. confluence { baseUrl = 'https://wiki.example.com' })");
    }
    String token = getAuthToken().getOrNull();
    if (token == null || token.isEmpty()) {
      throw new SyncException(
          "Confluence API token is required for confluencePublish:"
              + " set confluence.authToken, pass -Pconfluence.token=..., or export CONFLUENCE_TOKEN");
    }
    Path pagesDir = getPagesDir().getAsFile().get().toPath();
    SyncOptions opts = options(pagesDir);
    SyncSupport.validate(opts);

    List<Path> files = selectFiles(pagesDir, log);
    if (files.isEmpty()) {
      log.info("No changed pages to publish.");
      return;
    }
    for (Path file : files) {
      log.info("  " + SyncSupport.relativeKey(pagesDir, file));
    }

    Path trustStore =
        getTrustStore().getAsFile().getOrNull() == null
            ? null
            : getTrustStore().getAsFile().get().toPath();
    PageStore client =
        SyncSupport.createClient(
            baseUrl,
            getUsername().getOrElse(""),
            token,
            trustStore,
            getTrustStorePassword().getOrNull(),
            getInsecureSkipTlsVerify().getOrElse(false),
            log);

    // Links resolve against every page, while only the selection syncs: changed-only runs
    // keep working links to unchanged pages.
    List<Path> universe =
        SyncSupport.resolveFiles(pagesDir, getPageIncludes().getOrElse(List.of()));
    Map<String, String> pageTitles = PageSyncer.buildPageTitleMap(universe, opts);

    if (getPreview().getOrElse(false)) {
      opts.dryRun = true;
      PageSyncer.run(files, universe, opts, client, log).throwIfFailed();
      return;
    }
    for (String warning : SyncSupport.unresolvedLinkWarnings(pagesDir, files, pageTitles)) {
      log.warn(warning);
    }
    RunReport report = PageSyncer.run(files, universe, opts, client, log);
    if (report.failures().isEmpty()) {
      writeState(pagesDir, files);
    }
    report.throwIfFailed();
  }

  private List<Path> selectFiles(Path pagesDir, Log log) throws IOException {
    List<String> only = getOnlyFiles().getOrElse(List.of());
    if (!only.isEmpty()) {
      if (getChanged().getOrElse(false)) {
        log.info("--only wins over --changed; --changed is ignored.");
      }
      return SyncSupport.resolveOnlyFiles(pagesDir, only);
    }
    List<Path> files = SyncSupport.resolveFiles(pagesDir, getPageIncludes().getOrElse(List.of()));
    if (!getChanged().getOrElse(false)) {
      return files;
    }
    Path statePath = SyncSupport.stateFile(getProjectDir().getAsFile().get().toPath());
    Map<String, String> state = SyncSupport.loadState(statePath);
    if (!Files.isRegularFile(statePath)) {
      Map<String, String> legacy = SyncSupport.importLegacyHashes(pagesDir);
      if (!legacy.isEmpty()) {
        log.info("Imported " + legacy.size() + " hashes from .published-hashes.");
        state.putAll(legacy);
      }
    }
    List<Path> changed = new ArrayList<>();
    for (Path file : files) {
      String key = SyncSupport.relativeKey(pagesDir, file);
      if (!SyncSupport.digestPage(pagesDir, file).equals(state.get(key))) {
        changed.add(file);
      }
    }
    return changed;
  }

  private void writeState(Path pagesDir, List<Path> files) throws IOException {
    Path statePath = SyncSupport.stateFile(getProjectDir().getAsFile().get().toPath());
    Map<String, String> state = new LinkedHashMap<>(SyncSupport.loadState(statePath));
    for (Path file : files) {
      state.put(SyncSupport.relativeKey(pagesDir, file), SyncSupport.digestPage(pagesDir, file));
    }
    SyncSupport.saveState(statePath, state);
  }

  private SyncOptions options(Path pagesDir) {
    SyncOptions opts = new SyncOptions();
    opts.space = getSpace().getOrElse("");
    opts.parents = new ArrayList<>(getParents().getOrElse(List.of()));
    opts.parentsFromPath = getParentsFromPath().getOrElse(false);
    opts.parentsFromPathRoot =
        SyncSupport.effectiveParentsRoot(
            pagesDir, getParentsFromPathRoot().getOrElse(""), opts.parentsFromPath);
    opts.titleFromH1 = getTitleFromH1().getOrElse(false);
    opts.titleFromFilename = getTitleFromFilename().getOrElse(false);
    opts.titleAppendHash = getTitleAppendHash().getOrElse(false);
    opts.dropH1 = getDropH1().getOrElse(false);
    opts.minorEdit = getMinorEdit().getOrElse(false);
    opts.versionMessage = getVersionMessage().getOrElse("");
    opts.changesOnly = getChangesOnly().getOrElse(false);
    opts.continueOnError = getContinueOnError().getOrElse(false);
    opts.imageAlign = getImageAlign().getOrElse("");
    opts.imageWidth = getImageWidth().getOrElse("");
    opts.imageHeight = getImageHeight().getOrElse("");
    opts.contentAppearance = getContentAppearance().getOrElse("");
    opts.appendLabels = getAppendLabels().getOrElse(false);
    opts.attachReferenced = getAttachReferenced().getOrElse(false);
    return opts;
  }
}
