package dev.vspex.wiki.mark.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.vspex.wiki.mark.json.Json;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lossless import records a publish baseline under {@code .gradle}, reset keeps it, and {@code
 * --no-baseline} leaves it untouched. Uses a local stub; no network beyond loopback.
 */
class ImportBaselineFunctionalTest {
  @TempDir Path project;

  private GradleRunner runner(String... args) {
    return GradleRunner.create()
        .withProjectDir(project.toFile())
        .withPluginClasspath(List.of(Path.of(System.getProperty("plugin.jar")).toFile()))
        .withArguments(args);
  }

  @Test
  void losslessImportRecordsBaselineAndResetKeepsIt() throws Exception {
    String storage = "<p>Functional baseline</p>";
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          Object payload =
              exchange.getRequestURI().getPath().endsWith("/7")
                  ? Map.of(
                      "id",
                      "7",
                      "title",
                      "Guide",
                      "type",
                      "page",
                      "version",
                      Map.of("number", 5),
                      "space",
                      Map.of("key", "ENG"),
                      "ancestors",
                      List.of(),
                      "body",
                      Map.of("storage", Map.of("value", storage)))
                  : Map.of("results", List.of());
          byte[] data = Json.write(payload).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, data.length);
          try (var output = exchange.getResponseBody()) {
            output.write(data);
          }
        });
    server.start();
    try {
      Files.writeString(project.resolve("settings.gradle"), "");
      Files.writeString(
          project.resolve("build.gradle"),
          "plugins { id 'dev.vspex.confluence-publish' }\n"
              + "confluence { baseUrl = 'http://127.0.0.1:"
              + server.getAddress().getPort()
              + "/wiki' }\n");
      var imported =
          runner(
                  "confluenceImport",
                  "--page-id=7",
                  "--lossless",
                  "--verify",
                  "--output=team/guide.md",
                  "-Pconfluence.token=test")
              .build();
      assertEquals(TaskOutcome.SUCCESS, imported.task(":confluenceImport").getOutcome());
      assertTrue(
          imported.getOutput().contains("Publish baseline recorded: version 5"),
          imported.getOutput());
      Path baselines = project.resolve(".gradle/confluence-publish/baselines");
      Path baseline;
      try (var paths = Files.list(baselines)) {
        List<Path> files = paths.filter(path -> path.toString().endsWith(".json")).toList();
        assertEquals(1, files.size());
        baseline = files.get(0);
      }
      var recorded = (Map<?, ?>) Json.parse(Files.readString(baseline));
      assertEquals(5L, recorded.get("versionNumber"));
      byte[] before = Files.readAllBytes(baseline);
      var reset = runner("confluenceResetState").build();
      assertEquals(TaskOutcome.SUCCESS, reset.task(":confluenceResetState").getOutcome());
      assertEquals(before.length, Files.readAllBytes(baseline).length);
      var skipped =
          runner(
                  "confluenceImport",
                  "--page-id=7",
                  "--lossless",
                  "--no-baseline",
                  "--output=other.md",
                  "-Pconfluence.token=test")
              .build();
      assertEquals(TaskOutcome.SUCCESS, skipped.task(":confluenceImport").getOutcome());
      assertTrue(
          skipped.getOutput().contains("Publish baseline not recorded (--no-baseline)"),
          skipped.getOutput());
      try (var paths = Files.list(baselines)) {
        assertEquals(1, paths.filter(path -> path.toString().endsWith(".json")).toList().size());
      }
      assertEquals(new String(before), new String(Files.readAllBytes(baseline)));
    } finally {
      server.stop(0);
    }
  }
}
