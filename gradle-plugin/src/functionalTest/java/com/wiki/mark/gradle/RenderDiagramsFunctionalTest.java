package com.wiki.mark.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code confluenceRenderDiagrams} without a container engine: skip flags, errors, empty dir. */
class RenderDiagramsFunctionalTest {

  @TempDir Path projectDir;

  private void writeBuild(String diagramsBlock) throws Exception {
    Files.writeString(projectDir.resolve("settings.gradle"), "", StandardCharsets.UTF_8);
    Files.writeString(
        projectDir.resolve("build.gradle"),
        "plugins { id 'com.wiki.mark.confluence-publish' }\n\nconfluence {\n  diagrams {\n"
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
    Path emptyBin = Files.createDirectory(projectDir.resolve("empty-bin"));

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withEnvironment(Map.of("PATH", emptyBin.toString()))
            .withArguments("confluenceRenderDiagrams")
            .buildAndFail();

    assertTrue(result.getOutput().contains("not found on PATH"), result.getOutput());
    assertTrue(result.getOutput().contains("-x confluenceRenderDiagrams"), result.getOutput());
  }

  @Test
  void svgConversionNeedsEngineToo() throws Exception {
    writeBuild("    containerEngine = 'docker'\n    convertToSvg = true\n");
    writeMmd("flow.mmd");
    Path emptyBin = Files.createDirectory(projectDir.resolve("empty-bin"));

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withEnvironment(Map.of("PATH", emptyBin.toString()))
            .withArguments("confluenceRenderDiagrams")
            .buildAndFail();

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
}
