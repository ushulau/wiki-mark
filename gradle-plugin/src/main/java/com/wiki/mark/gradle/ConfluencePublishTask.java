package com.wiki.mark.gradle;

import com.wiki.mark.client.PageStore;
import com.wiki.mark.model.MetaException;
import com.wiki.mark.sync.Log;
import com.wiki.mark.sync.PageSyncer;
import com.wiki.mark.sync.RunReport;
import com.wiki.mark.sync.SyncException;
import com.wiki.mark.sync.SyncOptions;
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
  @Internal
  public abstract Property<String> getBaseUrl();

  @Internal
  public abstract Property<String> getAuthToken();

  @Internal
  public abstract Property<String> getUsername();

  @Internal
  public abstract Property<String> getSpace();

  @Internal
  public abstract DirectoryProperty getPagesDir();

  @Internal
  public abstract DirectoryProperty getProjectDir();

  @Internal
  public abstract ListProperty<String> getPageIncludes();

  @Internal
  public abstract Property<String> getImageHeight();

  @Internal
  public abstract Property<String> getImageWidth();

  @Internal
  public abstract Property<String> getImageAlign();

  @Internal
  public abstract Property<String> getContentAppearance();

  @Internal
  public abstract Property<Boolean> getTitleFromH1();

  @Internal
  public abstract Property<Boolean> getTitleFromFilename();

  @Internal
  public abstract Property<Boolean> getTitleAppendHash();

  @Internal
  public abstract Property<Boolean> getDropH1();

  @Internal
  public abstract ListProperty<String> getParents();

  @Internal
  public abstract Property<Boolean> getParentsFromPath();

  @Internal
  public abstract Property<String> getParentsFromPathRoot();

  @Internal
  public abstract Property<Boolean> getAppendLabels();

  @Internal
  public abstract Property<Boolean> getMinorEdit();

  @Internal
  public abstract Property<String> getVersionMessage();

  @Internal
  public abstract Property<Boolean> getChangesOnly();

  @Internal
  public abstract Property<Boolean> getAttachReferenced();

  @Internal
  public abstract Property<Boolean> getContinueOnError();

  @Internal
  public abstract RegularFileProperty getTrustStore();

  @Internal
  public abstract Property<String> getTrustStorePassword();

  @Internal
  public abstract Property<Boolean> getInsecureSkipTlsVerify();

  // Named "preview": Gradle reserves --dry-run for its own no-execution mode, which would
  // shadow a task option and fake-succeed without running the action.
  @Internal
  @Option(option = "preview", description = "Resolve ancestry and print storage without writing.")
  public abstract Property<Boolean> getPreview();

  @Internal
  @Option(option = "changed", description = "Publish only pages changed since the state file.")
  public abstract Property<Boolean> getChanged();

  @Internal
  @Option(
      option = "only",
      description = "Publish only these files, relative to pagesDir. Repeatable.")
  public abstract ListProperty<String> getOnlyFiles();

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

    if (getPreview().getOrElse(false)) {
      opts.dryRun = true;
      PageSyncer.run(files, opts, client, log).throwIfFailed();
      return;
    }
    for (String warning : SyncSupport.outOfBatchWarnings(pagesDir, files)) {
      log.warn(warning);
    }
    RunReport report = PageSyncer.run(files, opts, client, log);
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
