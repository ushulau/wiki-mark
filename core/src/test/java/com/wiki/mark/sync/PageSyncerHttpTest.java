package com.wiki.mark.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wiki.mark.client.ConfluenceClient;
import com.wiki.mark.client.StubServer;
import com.wiki.mark.model.SyncAction;
import com.wiki.mark.model.SyncResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end sync flows through the real {@link ConfluenceClient} against a scripted {@link
 * StubServer}: request sequences, read-only dry-run, and the unchanged comparison over HTTP.
 */
class PageSyncerHttpTest {

  static Map<String, Object> remotePage(String base, String id, String title, String storage) {
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("id", id);
    page.put("type", "page");
    page.put("title", title);
    Map<String, Object> version = new LinkedHashMap<>();
    version.put("number", 1L);
    version.put("message", "");
    page.put("version", version);
    page.put("ancestors", new ArrayList<>());
    Map<String, Object> links = new LinkedHashMap<>();
    links.put("base", base);
    links.put("webui", "/pages/" + id);
    page.put("_links", links);
    Map<String, Object> storageMap = new LinkedHashMap<>();
    storageMap.put("value", storage);
    page.put("body", new LinkedHashMap<>(Map.of("storage", storageMap)));
    return page;
  }

  @Test
  void createFlowOverHttp(@TempDir Path tmp) throws Exception {
    Files.createDirectories(tmp.resolve("assets"));
    Files.write(tmp.resolve("assets/a.png"), new byte[] {1, 2, 3});
    Path path = tmp.resolve("page.md");
    Files.writeString(
        path,
        "<!-- Space: DOC -->\n<!-- Title: Hello -->\n<!-- Label: l1 -->\n"
            + "<!-- Attachment: assets/a.png -->\n\nBody ![](assets/a.png).\n",
        StandardCharsets.UTF_8);
    try (StubServer server = new StubServer()) {
      String base = server.baseUrl();
      server.script(
          StubServer.Stub.json(Map.of("results", List.of())),
          StubServer.Stub.json(remotePage(base, "101", "P1", "")),
          StubServer.Stub.json(Map.of("results", List.of())),
          StubServer.Stub.json(remotePage(base, "102", "Hello", "<p>Body.</p>")),
          StubServer.Stub.json(Map.of("results", List.of(), "_links", Map.of())),
          StubServer.Stub.json(Map.of("results", List.of(Map.of("id", "a1")))),
          StubServer.Stub.json(Map.of("results", List.of())),
          StubServer.Stub.json(Map.of()));
      ConfluenceClient client = new ConfluenceClient(base, "", "tok");
      SyncOptions opts = new SyncOptions();
      opts.parents = new ArrayList<>(List.of("P1"));
      PageSyncerTest.RecordingLog log = new PageSyncerTest.RecordingLog();
      SyncResult result = PageSyncer.syncFile(path, opts, client, Map.of(), log);
      assertEquals(SyncAction.CREATED, result.action());
      assertEquals("102", result.pageId());
      assertEquals(base + "/pages/102", result.url());
      assertTrue(log.infoContains("attachment a.png uploaded"));
      List<String> methods = server.requests().stream().map(request -> request.method).toList();
      assertEquals(List.of("GET", "POST", "GET", "POST", "GET", "POST", "GET", "POST"), methods);
      StubServer.Captured labelsPost = server.requests().get(7);
      assertEquals("/rest/api/content/102/label", labelsPost.path);
      assertEquals(List.of(Map.of("prefix", "global", "name", "l1")), labelsPost.bodyJson());
      server.assertDone();
    }
  }

  @Test
  void dryRunReadOnlyOverHttp(@TempDir Path tmp) throws Exception {
    Path path = tmp.resolve("page.md");
    Files.writeString(path, PageSyncerTest.DOC, StandardCharsets.UTF_8);
    try (StubServer server = new StubServer()) {
      server.script(
          StubServer.Stub.json(Map.of("results", List.of())),
          StubServer.Stub.json(Map.of("results", List.of())));
      ConfluenceClient client = new ConfluenceClient(server.baseUrl(), "", "tok");
      SyncOptions opts = new SyncOptions();
      opts.dryRun = true;
      opts.parents = new ArrayList<>(List.of("P1"));
      SyncResult result =
          PageSyncer.syncFile(path, opts, client, Map.of(), new PageSyncerTest.RecordingLog());
      assertEquals(SyncAction.DRY_RUN, result.action());
      assertEquals(2, server.requests().size());
      assertTrue(server.requests().stream().allMatch(request -> request.method.equals("GET")));
      server.assertDone();
    }
  }

  @Test
  void unchangedOverHttp(@TempDir Path tmp) throws Exception {
    Path path = tmp.resolve("page.md");
    Files.writeString(path, PageSyncerTest.DOC, StandardCharsets.UTF_8);
    SyncOptions compile = new SyncOptions();
    compile.compileOnly = true;
    PageSyncerTest.RecordingLog compiled = new PageSyncerTest.RecordingLog();
    PageSyncer.syncFile(path, compile, null, Map.of(), compiled);
    String storage = compiled.infos.get(1);
    try (StubServer server = new StubServer()) {
      String base = server.baseUrl();
      Map<String, Object> found = remotePage(base, "102", "Hello", storage);
      server.script(
          StubServer.Stub.json(Map.of("results", List.of(found))),
          StubServer.Stub.json(remotePage(base, "102", "Hello", storage)),
          StubServer.Stub.json(Map.of("results", List.of())));
      ConfluenceClient client = new ConfluenceClient(base, "", "tok");
      SyncResult result =
          PageSyncer.syncFile(
              path, new SyncOptions(), client, Map.of(), new PageSyncerTest.RecordingLog());
      assertEquals(SyncAction.UNCHANGED, result.action());
      assertEquals("102", result.pageId());
      assertTrue(
          server.requests().stream()
              .noneMatch(
                  request ->
                      request.method.equals("PUT")
                          || request.method.equals("POST")
                          || request.method.equals("DELETE")));
      server.assertDone();
    }
  }
}
