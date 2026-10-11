package com.wiki.mark.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end plugin behavior in an isolated TestKit build (no network, no token). */
class PluginFunctionalTest {

  @TempDir Path projectDir;

  private void writeBuild(String confluenceBlock) throws Exception {
    Files.writeString(projectDir.resolve("settings.gradle"), "", StandardCharsets.UTF_8);
    Files.writeString(
        projectDir.resolve("build.gradle"),
        "plugins { id 'com.wiki.mark.confluence-publish' }\n\nconfluence {\n"
            + confluenceBlock
            + "\n}\n",
        StandardCharsets.UTF_8);
  }

  private void writePage(String relative, String text) throws Exception {
    Path file = projectDir.resolve("documentation/wiki/" + relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  private GradleRunner runner(String... args) {
    return GradleRunner.create()
        .withProjectDir(projectDir.toFile())
        .withPluginClasspath()
        .withArguments(args);
  }

  private static void assertTask(BuildResult result, String path, TaskOutcome outcome) {
    BuildTask task = result.task(path);
    assertTrue(task != null, "task " + path + " ran");
    assertTrue(
        task.getOutcome() == outcome,
        "task " + path + " " + task.getOutcome() + ", want " + outcome);
  }

  @Test
  void compileWritesStorageFiles() throws Exception {
    writeBuild("");
    writePage(
        "01-hello.md", "<!-- Space: DEMO -->\n<!-- Title: Hello -->\n\n# Hello\n\nBody text.\n");

    BuildResult result = runner("confluenceCompile", "--configuration-cache", "--info").build();

    assertTask(result, ":confluenceCompile", TaskOutcome.SUCCESS);
    Path storage = projectDir.resolve("build/confluence/storage/01-hello.html");
    assertTrue(Files.isRegularFile(storage), "storage file written");
    String html = Files.readString(storage, StandardCharsets.UTF_8);
    assertTrue(html.contains("Hello") && html.contains("Body text."), html);
  }

  @Test
  void compileFailsOnInvalidHeaders() throws Exception {
    writeBuild("");
    writePage("01-broken.md", "<!-- Space: S -->\n<!-- Titel: T -->\n\nBody.\n");

    BuildResult result = runner("confluenceCompile").buildAndFail();

    assertTask(result, ":confluenceCompile", TaskOutcome.FAILED);
    assertTrue(result.getOutput().contains("Titel"), result.getOutput());
  }

  @Test
  void publishRequiresBaseUrl() throws Exception {
    writeBuild("");
    writePage("01-hello.md", "<!-- Space: DEMO -->\n<!-- Title: Hello -->\n\nBody.\n");

    BuildResult result = runner("confluencePublish").buildAndFail();

    assertTrue(result.getOutput().contains("confluence.baseUrl is required"), result.getOutput());
  }

  @Test
  void publishRequiresToken() throws Exception {
    writeBuild("  baseUrl = 'https://wiki.example.com'\n");
    writePage("01-hello.md", "<!-- Space: DEMO -->\n<!-- Title: Hello -->\n\nBody.\n");

    // Present-but-empty property beats any ambient CONFLUENCE_TOKEN in the environment.
    BuildResult result = runner("confluencePublish", "-Pconfluence.token=").buildAndFail();

    assertTrue(result.getOutput().contains("Confluence API token is required"), result.getOutput());
  }

  @Test
  void previewOptionReachesTaskAction() throws Exception {
    writeBuild("  baseUrl = 'https://wiki.example.com'\n");
    writePage("01-hello.md", "<!-- Space: DEMO -->\n<!-- Title: Hello -->\n\nBody.\n");

    // The action must run (and fail on the empty token). A Gradle-reserved option
    // name would fake-succeed here because Gradle skips all task actions.
    BuildResult result =
        runner("confluencePublish", "--preview", "-Pconfluence.token=").buildAndFail();

    assertTrue(result.getOutput().contains("Confluence API token is required"), result.getOutput());
  }

  @Test
  void resetStateDeletesStateFile() throws Exception {
    writeBuild("");
    Path state = projectDir.resolve(".gradle/confluence-publish/state.properties");
    Files.createDirectories(state.getParent());
    Files.writeString(state, "01-a.md=abc\n", StandardCharsets.UTF_8);

    BuildResult result = runner("confluenceResetState").build();

    assertTask(result, ":confluenceResetState", TaskOutcome.SUCCESS);
    assertFalse(Files.exists(state), "state file deleted");
  }
}
