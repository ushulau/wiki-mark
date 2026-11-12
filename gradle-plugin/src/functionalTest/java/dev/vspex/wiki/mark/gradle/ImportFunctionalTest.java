package dev.vspex.wiki.mark.gradle;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.vspex.wiki.mark.json.Json;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportFunctionalTest {
  @TempDir Path project;

  private GradleRunner runner(String... args) {
    return GradleRunner.create()
        .withProjectDir(project.toFile())
        .withPluginClasspath(List.of(Path.of(System.getProperty("plugin.jar")).toFile()))
        .withArguments(args);
  }

  @Test
  void packagedPluginImportsLiveContentAndReusesConfigurationCache() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger version = new AtomicInteger(1),
        writes = new AtomicInteger(),
        downloads = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          if (!exchange.getRequestMethod().equals("GET")) writes.incrementAndGet();
          byte[] data;
          if (exchange.getRequestURI().getPath().equals("/wiki/download/image")) {
            downloads.incrementAndGet();
            data = new byte[] {1, 2, 3};
          } else {
            Object payload;
            if (exchange.getRequestURI().getPath().endsWith("/7")) {
              payload =
                  Map.of(
                      "id",
                      "7",
                      "title",
                      "Guide",
                      "type",
                      "page",
                      "version",
                      Map.of("number", version.get()),
                      "space",
                      Map.of("key", "ENG"),
                      "ancestors",
                      List.of(),
                      "body",
                      Map.of(
                          "storage",
                          Map.of(
                              "value",
                              "<h1>Guide</h1><p>Version "
                                  + version.get()
                                  + " &copy;</p>"
                                  + "<ac:structured-macro ac:name=\"code\"><ac:plain-text-body><![CDATA[x < y]]></ac:plain-text-body></ac:structured-macro>"
                                  + "<table><tr><th>A</th></tr><tr><td>B</td></tr></table>"
                                  + "<ac:link><ri:page ri:content-title=\"Other\"/></ac:link>"
                                  + "<ac:image><ri:attachment ri:filename=\"Image.png\"/></ac:image>")));
            } else if (exchange.getRequestURI().getPath().endsWith("/attachment")) {
              payload =
                  Map.of(
                      "results",
                      List.of(
                          Map.of(
                              "id",
                              "att8",
                              "title",
                              "Image.png",
                              "_links",
                              Map.of("download", "/download/image"))));
            } else payload = Map.of("results", List.of());
            data = Json.write(payload).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
          }
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
              + "/wiki'; pagesDir = layout.projectDirectory.dir('documents/wiki') }\n");
      var preview =
          runner(
                  "confluenceImport",
                  "--page-url=http://127.0.0.1:"
                      + server.getAddress().getPort()
                      + "/wiki/spaces/ENG/pages/7/Guide",
                  "--preview",
                  "-Pconfluence.token=test")
              .build();
      assertEquals(TaskOutcome.SUCCESS, preview.task(":confluenceImport").getOutcome());
      assertFalse(Files.exists(project.resolve("documents")));
      assertEquals(0, downloads.get());
      String[] args = {
        "confluenceImport",
        "--page-id=7",
        "--output=team/guide.md",
        "--overwrite",
        "--configuration-cache",
        "-Pconfluence.token=test"
      };
      var first = runner(args).build();
      assertEquals(TaskOutcome.SUCCESS, first.task(":confluenceImport").getOutcome());
      assertNull(first.task(":confluenceCompile"));
      assertNull(first.task(":confluenceRenderDiagrams"));
      version.set(2);
      var second = runner(args).build();
      assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
      assertEquals(TaskOutcome.SUCCESS, second.task(":confluenceImport").getOutcome());
      String document = Files.readString(project.resolve("documents/wiki/team/guide.md"));
      assertTrue(document.contains("Version 2 ©"), document);
      assertTrue(document.contains("guide.assets/att8-image.png"));
      assertEquals(2, downloads.get());
      assertEquals(0, writes.get());
      assertFalse(Files.exists(project.resolve(".gradle/confluence-publish/state.properties")));
      var collision =
          runner(
                  "confluenceImport",
                  "--page-id=7",
                  "--output=team/guide.md",
                  "-Pconfluence.token=test")
              .buildAndFail();
      assertTrue(collision.getOutput().contains("--overwrite"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void losslessImportCompilesByteIdentical() throws Exception {
    Map<?, ?> fixture;
    try (var stream = getClass().getResourceAsStream("/lossless-page.json")) {
      fixture = (Map<?, ?>) Json.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
    String storage =
        (String) ((Map<?, ?>) ((Map<?, ?>) fixture.get("body")).get("storage")).get("value");
    assertTrue(storage.length() > 27_000);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] data;
          String path = exchange.getRequestURI().getPath();
          if (path.equals("/wiki/download/notes")) {
            data = new byte[] {4, 5, 6};
          } else {
            Object payload;
            if (path.endsWith("/7")) {
              payload =
                  Map.of(
                      "id",
                      "7",
                      "title",
                      "Release",
                      "type",
                      "page",
                      "version",
                      Map.of("number", 3),
                      "space",
                      Map.of("key", "ENG"),
                      "ancestors",
                      List.of(Map.of("title", "Releases")),
                      "body",
                      Map.of("storage", Map.of("value", storage)));
            } else if (path.endsWith("/attachment")) {
              payload =
                  Map.of(
                      "results",
                      List.of(
                          Map.of(
                              "id",
                              "att3",
                              "title",
                              "Release notes.png",
                              "_links",
                              Map.of("download", "/wiki/download/notes"))));
            } else {
              payload = Map.of("results", List.of());
            }
            data = Json.write(payload).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
          }
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
              + "/wiki'; pagesDir = layout.projectDirectory.dir('documents/wiki'); pageIncludes = ['releases/[0-9]*.md'] }\n");
      var imported =
          runner(
                  "confluenceImport",
                  "--page-id=7",
                  "--output=releases/2026.md",
                  "--lossless",
                  "--verify",
                  "-Pconfluence.token=secret-token-xyz")
              .build();
      assertEquals(TaskOutcome.SUCCESS, imported.task(":confluenceImport").getOutcome());
      String document = Files.readString(project.resolve("documents/wiki/releases/2026.md"));
      assertTrue(document.contains("<!-- Space: ENG -->\n"), document);
      assertTrue(document.contains("<!-- Parent: Releases -->\n"), document);
      assertTrue(document.contains("<!-- Title: Release -->\n"), document);
      assertTrue(
          document.contains("<!-- Attachment: 2026.assets/Release notes.png -->\n"), document);
      assertTrue(document.contains("<!-- wiki-mark-raw-begin -->\n"), document);
      assertTrue(document.contains(storage), document);
      assertArrayEquals(
          new byte[] {4, 5, 6},
          Files.readAllBytes(
              project.resolve("documents/wiki/releases/2026.assets/Release notes.png")));
      assertTrue(Files.exists(project.resolve("documents/wiki/releases/2026.confluence.json")));
      assertTrue(
          Files.exists(project.resolve("documents/wiki/releases/2026.confluence-storage.xml")));
      Files.writeString(project.resolve("documents/wiki/releases/ignored.md"), "not included");
      var compiled = runner("confluenceCompile", "-Pconfluence.token=secret-token-xyz").build();
      assertEquals(TaskOutcome.SUCCESS, compiled.task(":confluenceCompile").getOutcome());
      assertEquals(
          storage,
          Files.readString(project.resolve("build/confluence/storage/releases/2026.html")));
      assertFalse(Files.exists(project.resolve("build/confluence/storage/releases/ignored.html")));
      try (var outputs = Files.list(project.resolve("build/confluence/storage/releases"))) {
        assertEquals(1, outputs.count());
      }
      var invalid =
          runner(
                  "confluenceImport",
                  "--page-id=7",
                  "--verify",
                  "-Pconfluence.token=secret-token-xyz")
              .buildAndFail();
      assertTrue(invalid.getOutput().contains("require --lossless"));
      var collision =
          runner(
                  "confluenceImport",
                  "--page-id=7",
                  "--output=releases/2026.md",
                  "--lossless",
                  "-Pconfluence.token=secret-token-xyz")
              .buildAndFail();
      assertTrue(collision.getOutput().contains("--overwrite"), collision.getOutput());
      assertFalse(collision.getOutput().contains("secret-token-xyz"), collision.getOutput());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void lifecycleDoesNotRunImport() throws Exception {
    Files.writeString(project.resolve("settings.gradle"), "");
    Files.writeString(
        project.resolve("build.gradle"),
        "plugins { id 'base'; id 'dev.vspex.confluence-publish' }\n");
    var result = runner("build").build();
    assertNull(result.task(":confluenceImport"));
    assertFalse(Files.exists(project.resolve("documents")));
  }
}
