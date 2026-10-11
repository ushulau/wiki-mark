package com.wiki.mark.gradle;

import com.wiki.mark.model.MetaException;
import com.wiki.mark.sync.PageSyncer;
import com.wiki.mark.sync.RunReport;
import com.wiki.mark.sync.SyncException;
import com.wiki.mark.sync.SyncOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Converts pages to Confluence storage XHTML without touching the network. No token or base URL
 * needed; header validation failures fail the task, so it can join {@code check}.
 */
@CacheableTask
public abstract class ConfluenceCompileTask extends DefaultTask {
  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getPagesDir();

  @Input
  public abstract ListProperty<String> getPageIncludes();

  @Input
  public abstract Property<String> getSpace();

  @Input
  public abstract Property<String> getImageHeight();

  @Input
  public abstract Property<String> getImageWidth();

  @Input
  public abstract Property<String> getImageAlign();

  @Input
  public abstract Property<String> getContentAppearance();

  @Input
  public abstract Property<Boolean> getTitleFromH1();

  @Input
  public abstract Property<Boolean> getTitleFromFilename();

  @Input
  public abstract Property<Boolean> getTitleAppendHash();

  @Input
  public abstract Property<Boolean> getDropH1();

  @Input
  public abstract ListProperty<String> getParents();

  @Input
  public abstract Property<Boolean> getParentsFromPath();

  @Input
  public abstract Property<String> getParentsFromPathRoot();

  @Input
  public abstract Property<Boolean> getAppendLabels();

  @Input
  public abstract Property<Boolean> getMinorEdit();

  @Input
  public abstract Property<String> getVersionMessage();

  @Input
  public abstract Property<Boolean> getChangesOnly();

  @Input
  public abstract Property<Boolean> getAttachReferenced();

  @Input
  public abstract Property<Boolean> getContinueOnError();

  @OutputDirectory
  public abstract DirectoryProperty getOutputDir();

  @TaskAction
  public void compile() {
    try {
      Path pagesDir = getPagesDir().getAsFile().get().toPath();
      Path outputDir = getOutputDir().getAsFile().get().toPath();
      SyncOptions opts = options(pagesDir);
      opts.compileOnly = true;
      SyncSupport.validate(opts);
      List<Path> files = SyncSupport.resolveFiles(pagesDir, getPageIncludes().getOrElse(List.of()));
      StorageCaptureLog log =
          new StorageCaptureLog(new GradleLog(getLogger()), pagesDir, outputDir);
      RunReport report = PageSyncer.run(files, opts, null, log);
      report.throwIfFailed();
    } catch (SyncException | MetaException | IOException | UncheckedIOException e) {
      throw new GradleException(failureMessage(e), e);
    }
  }

  private SyncOptions options(Path pagesDir) {
    SyncOptions opts = new SyncOptions();
    opts.space = getSpace().getOrElse("");
    opts.parents = getParents().getOrElse(List.of());
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

  private static String failureMessage(Exception e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }
}
