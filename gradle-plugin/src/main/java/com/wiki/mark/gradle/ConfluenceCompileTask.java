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
  /** Creates the task. Gradle supplies the managed properties. */
  public ConfluenceCompileTask() {}

  /**
   * Directory holding the wiki pages. Defaults to {@code documentation/wiki}.
   *
   * @return the pages directory property
   */
  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getPagesDir();

  /**
   * Page patterns relative to {@code pagesDir}. Defaults to every Markdown file, recursively.
   *
   * @return the page patterns property
   */
  @Input
  public abstract ListProperty<String> getPageIncludes();

  /**
   * Default space key when a page has no {@code Space} header.
   *
   * @return the default space key property
   */
  @Input
  public abstract Property<String> getSpace();

  /**
   * Max image height in pixels; per-file header wins.
   *
   * @return the max image height property
   */
  @Input
  public abstract Property<String> getImageHeight();

  /**
   * Max image width in pixels; per-file header wins.
   *
   * @return the max image width property
   */
  @Input
  public abstract Property<String> getImageWidth();

  /**
   * Image alignment ({@code left}, {@code center}, {@code right}); per-file header wins.
   *
   * @return the image alignment property
   */
  @Input
  public abstract Property<String> getImageAlign();

  /**
   * Content appearance ({@code full-width}, {@code fixed}, {@code default}) when unset per page.
   *
   * @return the content appearance property
   */
  @Input
  public abstract Property<String> getContentAppearance();

  /**
   * Take the page title from the leading H1. Mutually exclusive with {@code titleFromFilename}.
   *
   * @return the title-from-H1 flag property
   */
  @Input
  public abstract Property<Boolean> getTitleFromH1();

  /**
   * Take the page title from the file name. Mutually exclusive with {@code titleFromH1}.
   *
   * @return the title-from-filename flag property
   */
  @Input
  public abstract Property<Boolean> getTitleFromFilename();

  /**
   * Append a short parents/space/title hash to each title.
   *
   * @return the title-hash flag property
   */
  @Input
  public abstract Property<Boolean> getTitleAppendHash();

  /**
   * Do not publish the first H1 heading.
   *
   * @return the drop-H1 flag property
   */
  @Input
  public abstract Property<Boolean> getDropH1();

  /**
   * Parent pages prepended to each document's own parents.
   *
   * @return the parents property
   */
  @Input
  public abstract ListProperty<String> getParents();

  /**
   * Place each page under pages named after its directories.
   *
   * @return the parents-from-path flag property
   */
  @Input
  public abstract Property<Boolean> getParentsFromPath();

  /**
   * Directory {@code parentsFromPath} measures from. Defaults to {@code pagesDir}.
   *
   * @return the parents-from-path root property
   */
  @Input
  public abstract Property<String> getParentsFromPathRoot();

  /**
   * Add labels without removing existing ones.
   *
   * @return the append-labels flag property
   */
  @Input
  public abstract Property<Boolean> getAppendLabels();

  /**
   * Do not send notifications on update.
   *
   * @return the minor-edit flag property
   */
  @Input
  public abstract Property<Boolean> getMinorEdit();

  /**
   * Page version message.
   *
   * @return the version message property
   */
  @Input
  public abstract Property<String> getVersionMessage();

  /**
   * Skip pages whose content hash already matches the remote version.
   *
   * @return the changes-only flag property
   */
  @Input
  public abstract Property<Boolean> getChangesOnly();

  /**
   * Upload local files that links point at.
   *
   * @return the attach-referenced flag property
   */
  @Input
  public abstract Property<Boolean> getAttachReferenced();

  /**
   * Keep processing remaining files after a failure.
   *
   * @return the continue-on-error flag property
   */
  @Input
  public abstract Property<Boolean> getContinueOnError();

  /**
   * Directory receiving the rendered storage files. Defaults to {@code build/confluence/storage}.
   *
   * @return the output directory property
   */
  @OutputDirectory
  public abstract DirectoryProperty getOutputDir();

  /** Runs the offline conversion; header validation failures fail the task. */
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
