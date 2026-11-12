package dev.vspex.wiki.mark.gradle;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.vspex.wiki.mark.json.Json;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishDriftFunctionalTest {
  @TempDir Path project;

  GradleRunner runner(String... args) {
    return GradleRunner.create()
        .withProjectDir(project.toFile())
        .withPluginClasspath(List.of(Path.of(System.getProperty("plugin.jar")).toFile()))
        .withArguments(args);
  }

  @Test
  void driftPreviewForceAndChangedSelectionThroughPackagedPlugin() throws Exception {
    AtomicReference<String> body = new AtomicReference<>("<p>remote secret-token</p>");
    AtomicReference<String> message = new AtomicReference<>("");
    AtomicInteger version = new AtomicInteger(1), writes = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
          if (!method.equals("GET")) writes.incrementAndGet();
          if (method.equals("PUT")) {
            var payload =
                (Map<?, ?>)
                    Json.parse(
                        new String(
                            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            body.set(
                (String)
                    ((Map<?, ?>) ((Map<?, ?>) payload.get("body")).get("storage")).get("value"));
            var v = (Map<?, ?>) payload.get("version");
            version.set(((Number) v.get("number")).intValue());
            message.set((String) v.get("message"));
          }
          Map<String, Object> page =
              Map.of(
                  "id",
                  "7",
                  "type",
                  "page",
                  "title",
                  "Example",
                  "version",
                  Map.of("number", version.get(), "message", message.get()),
                  "ancestors",
                  List.of(),
                  "body",
                  Map.of("storage", Map.of("value", body.get())),
                  "_links",
                  Map.of("webui", "/pages/7"));
          Object result =
              path.endsWith("/7")
                  ? page
                  : path.endsWith("/label")
                      ? Map.of("results", List.of())
                      : Map.of("results", List.of(page));
          byte[] bytes = Json.write(result).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.start();
    try {
      Files.writeString(project.resolve("settings.gradle"), "");
      Files.writeString(
          project.resolve("build.gradle"),
          "plugins { id 'dev.vspex.confluence-publish' }\nconfluence { baseUrl = 'http://127.0.0.1:"
              + server.getAddress().getPort()
              + "'; authToken = 'secret-token' }\n");
      Path wiki = project.resolve("documentation/wiki");
      Files.createDirectories(wiki);
      Path file = wiki.resolve("page.md");
      Files.writeString(file, "<!-- Space: DEMO -->\n<!-- Title: Example -->\n\nlocal");
      String failed = runner("confluencePublish").buildAndFail().getOutput();
      assertTrue(failed.contains("--force"));
      assertTrue(failed.contains("--- a/remote"));
      assertFalse(failed.contains("remote secret-token"));
      assertEquals(0, writes.get());
      String preview = runner("confluencePublish", "--preview").build().getOutput();
      assertTrue(preview.contains("unknown baseline"));
      assertEquals(0, writes.get());
      assertFalse(Files.exists(project.resolve(".gradle/confluence-publish/baselines")));
      runner("confluencePublish", "--force").build();
      assertTrue(message.get().startsWith("[v"));
      assertEquals(1, writes.get());
      Files.writeString(file, "<!-- Space: DEMO -->\n<!-- Title: Example -->\n\nlocal edit");
      runner("confluencePublish").build();
      assertEquals(2, writes.get());
      body.set("<p>wiki edit</p>");
      version.incrementAndGet();
      message.set("");
      String drift = runner("confluencePublish", "--changed").buildAndFail().getOutput();
      assertTrue(drift.contains("remote-only drift"));
      assertEquals(2, writes.get());
      body.set("<p>local edit</p><p>wiki addition</p>");
      version.incrementAndGet();
      Files.writeString(file, "<!-- Space: DEMO -->\n<!-- Title: Example -->\n\nlocal revision");
      runner("confluencePublish", "--merge", "--preview").build();
      assertEquals(2, writes.get());
      runner("confluencePublish", "--merge").build();
      assertEquals("<p>local revision</p><p>wiki addition</p>", body.get());
      assertEquals(3, writes.get());
      runner("confluencePublish", "--merge").build();
      assertEquals(3, writes.get());
      runner("confluencePublish").buildAndFail();
      assertEquals(3, writes.get());
      runner("confluencePublish", "--merge", "--force").buildAndFail();
      assertEquals(3, writes.get());
    } finally {
      server.stop(0);
    }
  }
}
