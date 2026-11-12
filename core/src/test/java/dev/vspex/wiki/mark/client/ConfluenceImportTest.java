package dev.vspex.wiki.mark.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.vspex.wiki.mark.importer.ConfluenceImportSource;
import dev.vspex.wiki.mark.importer.ImportException;
import dev.vspex.wiki.mark.importer.ImportOptions;
import dev.vspex.wiki.mark.importer.PageImporter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfluenceImportTest {
  @TempDir Path project;

  private static Map<String, Object> page(String storage) {
    return Map.of(
        "id",
        "7",
        "type",
        "page",
        "title",
        "Guide",
        "space",
        Map.of("key", "ENG"),
        "version",
        Map.of("number", 4),
        "ancestors",
        List.of(Map.of("title", "Parent")),
        "body",
        Map.of("storage", Map.of("value", storage)));
  }

  @Test
  void streamingDownloadUsesDiskAndHonorsRemainingBudget() throws Exception {
    try (var server = new StubServer()) {
      server.script(StubServer.Stub.text("asset", 200), StubServer.Stub.text("too large", 200));
      var client = new ConfluenceClient(server.baseUrl(), "", "token");
      client.setMaxRetries(0);
      Path asset = project.resolve("asset");
      assertEquals(5, client.downloadForImport("/download", asset, 5));
      assertEquals("asset", Files.readString(asset));
      assertThrows(
          ConfluenceException.class, () -> client.downloadForImport("/download", asset, 5));
      server.assertDone();
    }
  }

  @Test
  void importsPageWithAssetUsingOnlyGetsAndExplicitExpansions() throws Exception {
    try (var server = new StubServer()) {
      server.script(
          StubServer.Stub.json(
              page("<p>Body</p><ac:image><ri:attachment ri:filename=\"Image.png\"/></ac:image>")),
          StubServer.Stub.json(Map.of("results", List.of(Map.of("name", "demo")))),
          StubServer.Stub.json(
              Map.of(
                  "results",
                  List.of(
                      Map.of(
                          "id",
                          "att8",
                          "title",
                          "Image.png",
                          "_links",
                          Map.of("download", "/download/attachments/7/Image.png"))))),
          StubServer.Stub.text("image bytes", 200));
      var client = new ConfluenceClient(server.baseUrl() + "/wiki", "email", "secret");
      var report =
          PageImporter.run(
              project,
              new ImportOptions("7", "", "guide.md", false, false),
              new ConfluenceImportSource(client));
      assertTrue(Files.exists(report.document()));
      assertEquals(4, server.requests().size());
      assertTrue(server.requests().stream().allMatch(r -> r.method.equals("GET")));
      var first = server.requests().get(0);
      assertEquals("/wiki/rest/api/content/7", first.path);
      assertTrue(
          first.rawQuery.contains("body.storage%2Cspace%2Cancestors%2Cversion"), first.rawQuery);
      assertEquals(
          "Basic "
              + Base64.getEncoder().encodeToString("email:secret".getBytes(StandardCharsets.UTF_8)),
          first.header("Authorization"));
      assertEquals("/wiki/download/attachments/7/Image.png", server.requests().get(3).path);
      assertFalse(Files.exists(project.resolve(".gradle")));
      server.assertDone();
    }
  }

  @Test
  void sourcePaginatesLabelsAndAttachmentsAndUsesBearerAuth() throws Exception {
    try (var server = new StubServer()) {
      server.script(
          StubServer.Stub.json(page("")),
          StubServer.Stub.json(
              Map.of("results", List.of(Map.of("name", "a")), "_links", Map.of("next", "next"))),
          StubServer.Stub.json(Map.of("results", List.of(Map.of("name", "b")))),
          StubServer.Stub.json(
              Map.of(
                  "results",
                  List.of(Map.of("id", "1", "title", "one")),
                  "_links",
                  Map.of("next", "next"))),
          StubServer.Stub.json(Map.of("results", List.of(Map.of("id", "2", "title", "two")))));
      var source = new ConfluenceImportSource(new ConfluenceClient(server.baseUrl(), "", "token"));
      assertEquals(List.of("a", "b"), source.readPage("7").labels());
      assertEquals(2, source.attachments("7").size());
      assertEquals("Bearer token", server.requests().get(0).header("Authorization"));
      assertTrue(server.requests().get(2).rawQuery.contains("start=1"));
      assertTrue(server.requests().get(4).rawQuery.contains("start=1"));
      server.assertDone();
    }
  }

  @Test
  void missingStorageAndPermissionsHaveSafeErrors() throws Exception {
    for (int status : List.of(401, 403, 404, 429, 503)) {
      try (var server = new StubServer()) {
        server.script(
            StubServer.Stub.json(Map.of("message", "secret token echoed by server"), status));
        var source =
            new ConfluenceImportSource(
                new ConfluenceClient(server.baseUrl(), "", "secret").setMaxRetries(0));
        var error = assertThrows(ImportException.class, () -> source.readPage("7"));
        assertTrue(error.getMessage().contains("HTTP " + status));
        assertFalse(error.getMessage().contains("secret"));
      }
    }
    try (var server = new StubServer()) {
      var response = new java.util.LinkedHashMap<>(page(""));
      response.put("body", Map.of());
      server.script(StubServer.Stub.json(response));
      var source = new ConfluenceImportSource(new ConfluenceClient(server.baseUrl(), "", ""));
      assertThrows(ImportException.class, () -> source.readPage("7"));
    }
  }

  @Test
  void transientReadsRetryWithoutMutatingRequests() throws Exception {
    try (var server = new StubServer()) {
      server.script(
          StubServer.Stub.of(429, new byte[0], Map.of("Retry-After", "0")),
          StubServer.Stub.json(page("")),
          StubServer.Stub.json(Map.of("results", List.of())));
      var client = new ConfluenceClient(server.baseUrl(), "", "");
      client.setSleeper(seconds -> {});
      assertEquals("", new ConfluenceImportSource(client).readPage("7").storage());
      assertTrue(server.requests().stream().allMatch(r -> r.method.equals("GET")));
      server.assertDone();
    }
  }

  @Test
  void downloadsResolveContextOnceAndFollowSameOriginRedirects() throws Exception {
    try (var server = new StubServer()) {
      server.script(
          StubServer.Stub.of(302, new byte[0], Map.of("Location", "/wiki/final")),
          StubServer.Stub.text("bytes", 200));
      var client = new ConfluenceClient(server.baseUrl() + "/wiki", "", "secret");
      assertArrayEquals(
          "bytes".getBytes(StandardCharsets.UTF_8), client.downloadForImport("/wiki/download/one"));
      assertEquals("/wiki/download/one", server.requests().get(0).path);
      assertEquals("/wiki/final", server.requests().get(1).path);
      assertEquals("Bearer secret", server.requests().get(1).header("Authorization"));
      server.assertDone();
    }
    try (var server = new StubServer()) {
      server.script(StubServer.Stub.text("bytes", 200));
      new ConfluenceClient(server.baseUrl() + "/confluence", "", "")
          .downloadForImport("/download/one");
      assertEquals("/confluence/download/one", server.requests().get(0).path);
    }
  }

  @Test
  void downloadRetriesRateLimitAndRejectsCrossOriginInitialOrHttpRedirect() throws Exception {
    try (var server = new StubServer();
        var other = new StubServer()) {
      var client = new ConfluenceClient(server.baseUrl(), "", "secret");
      assertThrows(
          ConfluenceException.class, () -> client.downloadForImport(other.baseUrl() + "/asset"));
      assertTrue(other.requests().isEmpty());
      client.setSleeper(seconds -> {});
      server.script(
          StubServer.Stub.of(503, new byte[0], Map.of("Retry-After", "0")),
          StubServer.Stub.text("ok", 200),
          StubServer.Stub.of(302, new byte[0], Map.of("Location", other.baseUrl() + "/asset")));
      assertEquals("ok", new String(client.downloadForImport("/asset"), StandardCharsets.UTF_8));
      assertThrows(ConfluenceException.class, () -> client.downloadForImport("/asset"));
      assertTrue(other.requests().isEmpty());
      server.assertDone();
    }
  }

  @Test
  void restRedirectIsNotFollowedAndImportModeRejectsWrites() throws Exception {
    try (var server = new StubServer();
        var other = new StubServer()) {
      server.script(
          StubServer.Stub.of(302, new byte[0], Map.of("Location", other.baseUrl() + "/asset")));
      var client = new ConfluenceClient(server.baseUrl(), "", "secret");
      var source = new ConfluenceImportSource(client);
      assertThrows(ImportException.class, () -> source.readPage("7"));
      assertTrue(other.requests().isEmpty());
      assertThrows(
          ConfluenceException.class, () -> client.createPage("ENG", "Bad", "", "page", null));
      assertEquals(1, server.requests().size());
    }
  }

  @Test
  void downloadSubscriberCancelsAtSizeLimit() {
    var subscriber = new LimitedBodySubscriber();
    boolean[] cancelled = {false};
    subscriber.onSubscribe(
        new Flow.Subscription() {
          public void request(long n) {}

          public void cancel() {
            cancelled[0] = true;
          }
        });
    byte[] block = new byte[1024 * 1024];
    for (int i = 0; i < 51; i++) subscriber.onNext(List.of(ByteBuffer.wrap(block)));
    assertTrue(cancelled[0]);
    assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
  }

  @Test
  void httpsCdnRedirectNeverReceivesCredentialsEvenWhenReturningToOrigin() throws Exception {
    Path store = project.resolve("test.jks");
    Path keytool =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
    var process =
        new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias",
                "local",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "1",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore",
                store.toString(),
                "-storetype",
                "JKS",
                "-storepass",
                "testpass",
                "-keypass",
                "testpass")
            .redirectErrorStream(true)
            .start();
    process.getInputStream().readAllBytes();
    assertEquals(0, process.waitFor());
    var keys = java.security.KeyStore.getInstance("JKS");
    try (var input = Files.newInputStream(store)) {
      keys.load(input, "testpass".toCharArray());
    }
    var managers =
        javax.net.ssl.KeyManagerFactory.getInstance(
            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
    managers.init(keys, "testpass".toCharArray());
    var context = javax.net.ssl.SSLContext.getInstance("TLS");
    context.init(managers.getKeyManagers(), null, null);
    var origin =
        com.sun.net.httpserver.HttpsServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var cdn =
        com.sun.net.httpserver.HttpsServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    origin.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(context));
    cdn.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(context));
    String base = "https://127.0.0.1:" + origin.getAddress().getPort();
    String other = "https://127.0.0.1:" + cdn.getAddress().getPort();
    var headers = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
    origin.createContext(
        "/",
        exchange -> {
          headers.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          if (exchange.getRequestURI().getPath().equals("/asset")) {
            exchange.getResponseHeaders().set("Location", other + "/signed");
            exchange.sendResponseHeaders(302, -1);
          } else {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write(new byte[] {1, 2});
          }
          exchange.close();
        });
    cdn.createContext(
        "/",
        exchange -> {
          headers.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          exchange.getResponseHeaders().set("Location", base + "/final");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    origin.start();
    cdn.start();
    try {
      var client = new ConfluenceClient(base, "", "secret").setTrustStore(store, "testpass");
      assertArrayEquals(new byte[] {1, 2}, client.downloadForImport("/asset"));
      assertEquals(List.of("Bearer secret", "null", "null"), headers);
    } finally {
      origin.stop(0);
      cdn.stop(0);
    }
  }
}
