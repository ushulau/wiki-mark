package dev.vspex.wiki.mark.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code confluenceRenderDiagrams} without a container engine: skip flags, errors, empty dir. */
class RenderDiagramsFunctionalTest {

  @TempDir Path projectDir;

  private void writeBuild(String diagramsBlock) throws Exception {
    Files.writeString(projectDir.resolve("settings.gradle"), "", StandardCharsets.UTF_8);
    Files.writeString(
        projectDir.resolve("build.gradle"),
        "plugins { id 'dev.vspex.confluence-publish' }\n\nconfluence {\n  diagrams {\n"
            + diagramsBlock
            + "\n  }\n}\n",
        StandardCharsets.UTF_8);
  }

  private void writeMmd(String name) throws Exception {
    Path file = projectDir.resolve("documentation/wiki/diagrams/" + name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "flowchart LR\n    A --> B\n", StandardCharsets.UTF_8);
  }

  private GradleRunner runner(String... args) {
    return GradleRunner.create()
        .withProjectDir(projectDir.toFile())
        .withPluginClasspath()
        .withArguments(args);
  }

  private GradleRunner runnerWithoutEngine(String... args) throws Exception {
    Path emptyBin = Files.createDirectory(projectDir.resolve("empty-bin"));
    return GradleRunner.create()
        .withProjectDir(projectDir.toFile())
        .withPluginClasspath()
        .withEnvironment(Map.of("PATH", emptyBin.toString()))
        .withArguments(args);
  }

  @Test
  void conversionDisabledByDefault() throws Exception {
    writeBuild("");
    writeMmd("flow.mmd");

    BuildResult result = runner("confluenceRenderDiagrams", "--configuration-cache").build();

    assertTrue(result.getOutput().contains("diagram rendering is disabled"), result.getOutput());
    assertFalse(
        Files.exists(projectDir.resolve("documentation/wiki/diagrams/flow.png")),
        "no PNG rendered while disabled");
    assertFalse(
        Files.exists(projectDir.resolve("documentation/wiki/diagrams/flow.svg")),
        "no SVG rendered while disabled");
  }

  @Test
  void missingEngineFailsActionably() throws Exception {
    writeBuild("    containerEngine = 'docker'\n    convertToPng = true\n");
    writeMmd("flow.mmd");

    BuildResult result = runnerWithoutEngine("confluenceRenderDiagrams").buildAndFail();

    assertTrue(result.getOutput().contains("not found on PATH"), result.getOutput());
    assertTrue(result.getOutput().contains("-x confluenceRenderDiagrams"), result.getOutput());
  }

  @Test
  void svgConversionNeedsEngineToo() throws Exception {
    writeBuild("    containerEngine = 'docker'\n    convertToSvg = true\n");
    writeMmd("flow.mmd");

    BuildResult result = runnerWithoutEngine("confluenceRenderDiagrams").buildAndFail();

    assertTrue(result.getOutput().contains("not found on PATH"), result.getOutput());
  }

  @Test
  void invalidEngineFailsFast() throws Exception {
    writeBuild("    containerEngine = 'rocket'\n    convertToPng = true\n");
    writeMmd("flow.mmd");

    BuildResult result = runner("confluenceRenderDiagrams").buildAndFail();

    assertTrue(result.getOutput().contains("containerEngine"), result.getOutput());
  }

  @Test
  void missingSourceDirSucceeds() throws Exception {
    writeBuild("    convertToPng = true\n");

    BuildResult result = runner("confluenceRenderDiagrams").build();

    assertTrue(result.getOutput().contains("no diagrams to render"), result.getOutput());
  }

  @Test
  void skipsWhenUpToDate() throws Exception {
    writeBuild("");
    writeMmd("flow.mmd");

    BuildResult first = runner("confluenceRenderDiagrams").build();
    assertTrue(first.task(":confluenceRenderDiagrams").getOutcome() == TaskOutcome.SUCCESS);
    BuildResult second = runner("confluenceRenderDiagrams").build();
    assertTrue(
        second.task(":confluenceRenderDiagrams").getOutcome() == TaskOutcome.UP_TO_DATE,
        second.getOutput());
  }

  @Test
  void skipsDiagramsWhenHashesMatch() throws Exception {
    writeBuild("    convertToPng = true\n");
    writeMmd("flow.mmd");
    Path png = projectDir.resolve("documentation/wiki/diagrams/flow.png");
    byte[] image = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);
    Files.write(png, image);
    primeDiagram("flow", "png", image);

    // No engine on PATH: success proves nothing needed rendering.
    BuildResult result = runnerWithoutEngine("confluenceRenderDiagrams").build();

    assertTrue(result.getOutput().contains("up-to-date, nothing to render"), result.getOutput());
    assertEquals("fake-png-bytes", Files.readString(png, StandardCharsets.UTF_8));
  }

  @Test
  void renderReusesConfigurationCache() throws Exception {
    writeBuild("    convertToPng = true\n");
    writeMmd("flow.mmd");
    Path png = projectDir.resolve("documentation/wiki/diagrams/flow.png");
    byte[] image = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);
    Files.write(png, image);
    primeDiagram("flow", "png", image);

    // Hashes primed: nothing renders, so no engine is needed on either run.
    runner("confluenceRenderDiagrams", "--configuration-cache").build();
    BuildResult reused = runner("confluenceRenderDiagrams", "--configuration-cache").build();

    assertTrue(reused.getOutput().contains("Reusing configuration cache."), reused.getOutput());
  }

  @Test
  void missingOutputRequiresEngine() throws Exception {
    writeBuild("    convertToPng = true\n");
    writeMmd("flow.mmd");
    primeDiagram("flow", "png", "fake-png-bytes".getBytes(StandardCharsets.UTF_8));
    // NOTE: no flow.png on disk.

    BuildResult result = runnerWithoutEngine("confluenceRenderDiagrams").buildAndFail();

    assertTrue(result.getOutput().contains("not found on PATH"), result.getOutput());
  }

  /** Records a matching input/output hash pair so the diagram counts as rendered. */
  private void primeDiagram(String stem, String format, byte[] output) throws Exception {
    Path diagrams = projectDir.resolve("documentation/wiki/diagrams");
    byte[] mmd = Files.readAllBytes(diagrams.resolve(stem + ".mmd"));
    String input =
        RenderSupport.diagramDigest(
            mmd,
            RenderSupport.DEFAULT_CONTAINER_IMAGE,
            "docker",
            RenderSupport.defaultExtraRunArgs("docker"),
            "white",
            2,
            RenderSupport.MERMAID_JSON,
            RenderSupport.PUPPETEER_JSON);
    Map<String, String> state = new LinkedHashMap<>();
    state.put(RenderSupport.stateKey(stem, format), input + ":" + RenderSupport.shaHex(output));
    SyncSupport.saveState(SyncSupport.stateFile(projectDir), state);
  }
}
