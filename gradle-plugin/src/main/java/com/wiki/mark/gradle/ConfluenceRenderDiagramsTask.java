package com.wiki.mark.gradle;

import com.wiki.mark.sync.Log;
import com.wiki.mark.sync.SyncException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

/**
 * Renders {@code .mmd} diagrams via a pinned mermaid-cli container, replicating {@code render.sh}
 * without a shell (one engine invocation per diagram and format, Windows-safe). Skipped unless
 * {@code diagrams.convertToPng} and/or {@code diagrams.convertToSvg} is set; full re-render on
 * execution.
 */
public abstract class ConfluenceRenderDiagramsTask extends DefaultTask {
  @Input
  public abstract Property<Boolean> getConvertToPng();

  @Input
  public abstract Property<Boolean> getConvertToSvg();

  @Internal
  public abstract DirectoryProperty getSourceDir();

  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getMmdSources();

  @Input
  public abstract Property<String> getContainerImage();

  @Input
  public abstract Property<String> getContainerEngine();

  @Input
  public abstract ListProperty<String> getExtraRunArgs();

  @Input
  public abstract Property<Integer> getScale();

  @Input
  public abstract Property<String> getBackground();

  @Input
  public abstract Property<String> getMermaidJson();

  @Input
  public abstract Property<String> getPuppeteerJson();

  @Internal
  @Option(
      option = "only",
      description = "Render only these diagrams (names, .mmd suffix optional). Repeatable.")
  public abstract ListProperty<String> getOnlyDiagrams();

  @Inject
  public abstract ExecOperations getExecOperations();

  @TaskAction
  public void render() {
    try {
      run();
    } catch (SyncException | UncheckedIOException | IOException e) {
      throw new GradleException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
    }
  }

  private void run() throws IOException {
    Log log = new GradleLog(getLogger());
    boolean png = getConvertToPng().getOrElse(false);
    boolean svg = getConvertToSvg().getOrElse(false);
    if (!png && !svg) {
      log.info(
          "diagram rendering is disabled (set diagrams.convertToPng and/or"
              + " diagrams.convertToSvg to render); skipping confluenceRenderDiagrams.");
      return;
    }
    String engine = getContainerEngine().getOrElse("docker");
    int scale = getScale().getOrElse(2);
    String background = getBackground().getOrElse("white");
    RenderSupport.validate(engine, scale, background);
    Path sourceDir = getSourceDir().getAsFile().get().toPath();
    List<Path> diagrams =
        RenderSupport.resolveDiagrams(sourceDir, getOnlyDiagrams().getOrElse(List.of()));
    if (diagrams.isEmpty()) {
      log.info("no diagrams to render in '" + sourceDir + "' (no .mmd files).");
      return;
    }
    ensureEngine(engine);
    Path cfgDir = writeConfigs();
    for (Path mmd : diagrams) {
      if (png) {
        renderOne(engine, sourceDir, cfgDir, mmd, log, "png");
        if (!svg) {
          deleteStraySvg(sourceDir, mmd);
        }
      }
      if (svg) {
        renderOne(engine, sourceDir, cfgDir, mmd, log, "svg");
      }
    }
  }

  private static void ensureEngine(String engine) {
    if (!RenderSupport.onPath(engine)) {
      throw new SyncException(RenderSupport.engineNotFoundMessage(engine));
    }
  }

  private Path writeConfigs() throws IOException {
    Path cfgDir = getTemporaryDir().toPath().resolve("cfg");
    Files.createDirectories(cfgDir);
    Files.writeString(
        cfgDir.resolve("mermaid.json"),
        getMermaidJson().getOrElse(RenderSupport.MERMAID_JSON),
        StandardCharsets.UTF_8);
    Files.writeString(
        cfgDir.resolve("puppeteer.json"),
        getPuppeteerJson().getOrElse(RenderSupport.PUPPETEER_JSON),
        StandardCharsets.UTF_8);
    return cfgDir;
  }

  private void renderOne(
      String engine, Path sourceDir, Path cfgDir, Path mmd, Log log, String format) {
    String fileName = mmd.getFileName().toString();
    String stem = fileName.substring(0, fileName.length() - ".mmd".length());
    List<String> args =
        RenderSupport.containerArgs(
            sourceDir,
            cfgDir,
            getContainerImage().getOrElse(RenderSupport.DEFAULT_CONTAINER_IMAGE),
            getExtraRunArgs().getOrElse(List.of()),
            getBackground().getOrElse("white"),
            getScale().getOrElse(2),
            stem,
            format);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ExecResult result =
        getExecOperations()
            .exec(
                spec -> {
                  spec.setExecutable(engine);
                  spec.args(args);
                  spec.setIgnoreExitValue(true);
                  spec.setStandardOutput(out);
                  spec.setErrorOutput(out);
                });
    if (result.getExitValue() != 0) {
      throw new GradleException(
          "confluenceRenderDiagrams: rendering '"
              + stem
              + ".mmd' to "
              + format
              + " failed (exit "
              + result.getExitValue()
              + "):\n"
              + out.toString(StandardCharsets.UTF_8));
    }
    log.info("rendered " + stem + "." + format);
  }

  private static void deleteStraySvg(Path sourceDir, Path mmd) {
    String fileName = mmd.getFileName().toString();
    String stem = fileName.substring(0, fileName.length() - ".mmd".length());
    try {
      Files.deleteIfExists(sourceDir.resolve(stem + ".svg"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
