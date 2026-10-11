package com.wiki.mark.gradle;

import com.wiki.mark.sync.Log;
import com.wiki.mark.sync.SyncException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

/**
 * Renders {@code .mmd} diagrams via a pinned mermaid-cli container, replicating {@code render.sh}
 * without a shell (one engine invocation per diagram and format, Windows-safe). Skipped unless
 * {@code diagrams.convertToPng} and/or {@code diagrams.convertToSvg} is set. Within a run, only
 * diagrams whose inputs changed (or whose output is missing or tampered with) render; the rest skip
 * via per-diagram hashes in the shared state file.
 */
public abstract class ConfluenceRenderDiagramsTask extends DefaultTask {
  @Input
  public abstract Property<Boolean> getConvertToPng();

  @Input
  public abstract Property<Boolean> getConvertToSvg();

  @Internal
  public abstract DirectoryProperty getSourceDir();

  @Internal
  public abstract DirectoryProperty getProjectDir();

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

  @OutputDirectory
  public abstract DirectoryProperty getOutputDir();

  @Input
  @Option(
      option = "only",
      description = "Render only these diagrams (names, .mmd suffix optional). Repeatable.")
  public abstract ListProperty<String> getOnlyDiagrams();

  @Inject
  public abstract ExecOperations getExecOperations();

  /** One diagram in one format, with its current input digest. */
  private record RenderOp(Path mmd, String stem, String format, String inputHash) {}

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
    String image = getContainerImage().getOrElse(RenderSupport.DEFAULT_CONTAINER_IMAGE);
    List<String> extraRunArgs = getExtraRunArgs().getOrElse(List.of());
    int scale = getScale().getOrElse(2);
    String background = getBackground().getOrElse("white");
    String mermaidJson = getMermaidJson().getOrElse(RenderSupport.MERMAID_JSON);
    String puppeteerJson = getPuppeteerJson().getOrElse(RenderSupport.PUPPETEER_JSON);
    RenderSupport.validate(engine, scale, background);
    Path sourceDir = getSourceDir().getAsFile().get().toPath();
    List<String> only = getOnlyDiagrams().getOrElse(List.of());
    List<Path> diagrams = RenderSupport.resolveDiagrams(sourceDir, only);
    if (diagrams.isEmpty()) {
      log.info("no diagrams to render in '" + sourceDir + "' (no .mmd files).");
      return;
    }
    List<String> formats = formats(png, svg);
    Path statePath = SyncSupport.stateFile(getProjectDir().getAsFile().get().toPath());
    Map<String, String> state = SyncSupport.loadState(statePath);
    List<RenderOp> render = new ArrayList<>();
    List<RenderOp> skipped = new ArrayList<>();
    for (Path mmd : diagrams) {
      String stem = stem(mmd);
      String inputHash =
          RenderSupport.diagramDigest(
              Files.readAllBytes(mmd),
              image,
              engine,
              extraRunArgs,
              background,
              scale,
              mermaidJson,
              puppeteerJson);
      for (String format : formats) {
        RenderOp op = new RenderOp(mmd, stem, format, inputHash);
        Path output = sourceDir.resolve(stem + "." + format);
        if (RenderSupport.needsRender(
            state.get(RenderSupport.stateKey(stem, format)), inputHash, output)) {
          render.add(op);
        } else {
          skipped.add(op);
          log.debug("up-to-date " + stem + "." + format);
        }
      }
    }
    if (render.isEmpty()) {
      log.info(
          "all "
              + skipped.size()
              + " image"
              + plural(skipped.size())
              + " up-to-date, nothing to render.");
      return;
    }
    ensureEngine(engine);
    Path cfgDir = writeConfigs(mermaidJson, puppeteerJson);
    for (RenderOp op : render) {
      renderOne(engine, sourceDir, cfgDir, op, log, image, extraRunArgs, background, scale);
      if (op.format().equals("png") && !svg) {
        deleteStraySvg(sourceDir, op.mmd());
      }
      byte[] outputBytes = Files.readAllBytes(sourceDir.resolve(op.stem() + "." + op.format()));
      state.put(
          RenderSupport.stateKey(op.stem(), op.format()),
          op.inputHash() + ":" + RenderSupport.shaHex(outputBytes));
      SyncSupport.saveState(statePath, state);
    }
    if (only.isEmpty()) {
      RenderSupport.pruneRemovedDiagrams(state, sourceDir);
      SyncSupport.saveState(statePath, state);
    }
    log.info(
        "rendered "
            + render.size()
            + " image"
            + plural(render.size())
            + ", "
            + skipped.size()
            + " up-to-date.");
  }

  private static List<String> formats(boolean png, boolean svg) {
    if (png && svg) {
      return List.of("png", "svg");
    }
    return List.of(png ? "png" : "svg");
  }

  private static String stem(Path mmd) {
    String fileName = mmd.getFileName().toString();
    return fileName.substring(0, fileName.length() - ".mmd".length());
  }

  private static String plural(int count) {
    return count == 1 ? "" : "s";
  }

  private static void ensureEngine(String engine) {
    if (!RenderSupport.onPath(engine)) {
      throw new SyncException(RenderSupport.engineNotFoundMessage(engine));
    }
  }

  private Path writeConfigs(String mermaidJson, String puppeteerJson) throws IOException {
    Path cfgDir = getTemporaryDir().toPath().resolve("cfg");
    Files.createDirectories(cfgDir);
    Files.writeString(cfgDir.resolve("mermaid.json"), mermaidJson, StandardCharsets.UTF_8);
    Files.writeString(cfgDir.resolve("puppeteer.json"), puppeteerJson, StandardCharsets.UTF_8);
    return cfgDir;
  }

  private void renderOne(
      String engine,
      Path sourceDir,
      Path cfgDir,
      RenderOp op,
      Log log,
      String image,
      List<String> extraRunArgs,
      String background,
      int scale) {
    List<String> args =
        RenderSupport.containerArgs(
            sourceDir, cfgDir, image, extraRunArgs, background, scale, op.stem(), op.format());
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
              + op.stem()
              + ".mmd' to "
              + op.format()
              + " failed (exit "
              + result.getExitValue()
              + "):\n"
              + out.toString(StandardCharsets.UTF_8));
    }
    log.info("rendered " + op.stem() + "." + op.format());
  }

  private static void deleteStraySvg(Path sourceDir, Path mmd) {
    try {
      Files.deleteIfExists(sourceDir.resolve(stem(mmd) + ".svg"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
