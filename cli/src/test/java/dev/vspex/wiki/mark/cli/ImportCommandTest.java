package dev.vspex.wiki.mark.cli;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportCommandTest {
  @TempDir Path project;

  @Test
  void verifyWithoutLosslessFailsBeforeNetwork() {
    var capture = new MainTest.Capture();
    assertEquals(
        1,
        ImportCommand.run(
            new String[] {"--page-id", "7", "--verify", "--base-url", "https://example.invalid"},
            capture.out,
            capture.err,
            Map.of("CONFLUENCE_TOKEN", "secret-token"),
            project));
    assertTrue(capture.errText().contains("require --lossless"));
    assertFalse(capture.errText().contains("secret-token"));
  }

  @Test
  void helpDispatchesWithoutCredentialsAndSyncStillWorks() {
    var capture = new MainTest.Capture();
    assertEquals(0, MainTest.run(capture, "import", "--help"));
    assertTrue(capture.outText().contains("--page-id"));
    assertEquals(0, MainTest.run(new MainTest.Capture(), "sync", "--help"));
  }

  @Test
  void unknownFlagsAndConflictingSelectorsFail() {
    for (String[] args :
        List.of(
            new String[] {"--files", "x.md"},
            new String[] {"--overwrite=true"},
            new String[] {"--output"},
            new String[] {
              "--page-id",
              "7",
              "--page-url",
              "https://wiki.example/wiki/pages/7",
              "-b",
              "https://wiki.example/wiki"
            })) {
      var capture = new MainTest.Capture();
      assertEquals(
          1,
          ImportCommand.run(
              args, capture.out, capture.err, Map.of("CONFLUENCE_TOKEN", "token"), project));
      assertTrue(capture.errText().contains("error:"));
    }
  }

  @Test
  void bothSelectorsAndPreviewUseSharedImporter() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger writes = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          if (!exchange.getRequestMethod().equals("GET")) writes.incrementAndGet();
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
                      Map.of("number", 1),
                      "space",
                      Map.of("key", "ENG"),
                      "ancestors",
                      List.of(),
                      "body",
                      Map.of("storage", Map.of("value", "<p>Body &copy;</p>")))
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
      String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/wiki";
      var preview = new MainTest.Capture();
      assertEquals(
          0,
          ImportCommand.run(
              new String[] {"--page-id=7", "--preview", "-b", base},
              preview.out,
              preview.err,
              Map.of("CONFLUENCE_TOKEN", "token"),
              project),
          preview.errText());
      assertFalse(Files.exists(project.resolve("documents")));
      assertTrue(preview.outText().contains("Body ©"));
      var capture = new MainTest.Capture();
      assertEquals(
          0,
          ImportCommand.run(
              new String[] {
                "--page-url",
                base + "/pages/viewpage.action?pageId=7",
                "--output=team/guide.md",
                "-b",
                base
              },
              capture.out,
              capture.err,
              Map.of("CONFLUENCE_TOKEN", "token"),
              project),
          capture.errText());
      assertTrue(Files.exists(project.resolve("documents/wiki/team/guide.md")));
      assertEquals(0, writes.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void losslessPreviewKeepsRawStorageAndHeaders() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String storage =
        "<ac:layout><p>Hi</p>\n\n<p>there</p><ac:structured-macro ac:name=\"toc\"/></ac:layout>"
            + "<ac:image><ri:attachment ri:filename=\"Pic.png\"/></ac:image>";
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          Object payload;
          if (path.endsWith("/7")) {
            payload =
                Map.of(
                    "id",
                    "7",
                    "title",
                    "Guide",
                    "type",
                    "page",
                    "version",
                    Map.of("number", 1),
                    "space",
                    Map.of("key", "ENG"),
                    "ancestors",
                    List.of(Map.of("title", "Top")),
                    "body",
                    Map.of("storage", Map.of("value", storage)));
          } else if (path.endsWith("/attachment")) {
            payload =
                Map.of(
                    "results",
                    List.of(
                        Map.of(
                            "id",
                            "att1",
                            "title",
                            "Pic.png",
                            "_links",
                            Map.of("download", "/download/pic"))));
          } else {
            payload = Map.of("results", List.of());
          }
          byte[] data = Json.write(payload).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, data.length);
          try (var output = exchange.getResponseBody()) {
            output.write(data);
          }
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/wiki";
      var preview = new MainTest.Capture();
      assertEquals(
          0,
          ImportCommand.run(
              new String[] {"--page-id=7", "--lossless", "--verify", "--preview", "-b", base},
              preview.out,
              preview.err,
              Map.of("CONFLUENCE_TOKEN", "token"),
              project),
          preview.errText());
      assertTrue(preview.outText().contains("<!-- Space: ENG -->"), preview.outText());
      assertTrue(preview.outText().contains("<!-- Parent: Top -->"), preview.outText());
      assertTrue(preview.outText().contains(storage), preview.outText());
      assertTrue(preview.errText().contains("toc"), preview.errText());
      assertFalse(Files.exists(project.resolve("documents")));
    } finally {
      server.stop(0);
    }
  }
}
