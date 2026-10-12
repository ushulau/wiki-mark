package dev.vspex.wiki.mark.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Confluence client tests against a scripted JDK-{@code HttpServer} (no network, real HTTP through
 * the client).
 *
 * <p>Ports {@code test_confluence.py} except {@code test_update_page_respects_concurrent_edits}:
 * the {@code keep_concurrent_edits} flag is dropped (no caller ever enabled it), so a 409 always
 * re-reads once and retries. Extra cases pin Java-side behavior (query encoding, base-link
 * injection, trust-store loading, the insecure warning).
 */
class ConfluenceClientTest {

  private static final String ROCKET = new String(Character.toChars(0x1F680));

  private static final class RecordingSleeper implements ConfluenceClient.Sleeper {
    final List<Double> delays = new ArrayList<>();

    @Override
    public void sleep(double seconds) {
      delays.add(seconds);
    }
  }

  private static ConfluenceClient client(StubServer server, RecordingSleeper sleeper) {
    ConfluenceClient client = new ConfluenceClient(server.baseUrl(), "", "");
    client.setSleeper(sleeper);
    return client;
  }

  private static Map<String, Object> page() {
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("id", "7");
    page.put("type", "page");
    page.put("title", "T");
    Map<String, Object> version = new LinkedHashMap<>();
    version.put("number", Long.valueOf(3));
    version.put("message", "");
    page.put("version", version);
    List<Object> ancestors = new ArrayList<>();
    ancestors.add(new LinkedHashMap<>(Map.of("id", "1")));
    page.put("ancestors", ancestors);
    Map<String, Object> links = new LinkedHashMap<>();
    links.put("base", "https://x");
    links.put("webui", "/pages/7");
    page.put("_links", links);
    Map<String, Object> storage = new LinkedHashMap<>();
    storage.put("value", "<p>old</p>");
    page.put("body", new LinkedHashMap<>(Map.of("storage", storage)));
    return page;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static Object probe(ConfluenceClient client) {
    return client.request("GET", "/content/", null, null, null, null, "probe");
  }

  private static Map<String, Object> createTop(ConfluenceClient client) {
    return client.createPage("S", "T", "<p/>", "page", null);
  }

  private static Map<String, Object> update(ConfluenceClient client, Map<String, Object> page) {
    return client.updatePage(page, "<p>new</p>", false, "", "full-width", "");
  }

  @Test
  void baseUrlRequired() {
    ConfluenceException e =
        assertThrows(ConfluenceException.class, () -> new ConfluenceClient("", "", ""));
    assertEquals("base-url is required", e.getMessage());
  }

  @Test
  void authBasicBearerAndNone() {
    ConfluenceClient basic = new ConfluenceClient("https://x", "user", "tok");
    byte[] credentials = "user:tok".getBytes(StandardCharsets.UTF_8);
    String encoded = Base64.getEncoder().encodeToString(credentials);
    assertEquals(Map.of("Authorization", "Basic " + encoded), basic.authHeaders());
    ConfluenceClient bearer = new ConfluenceClient("https://x", "", "pat");
    assertEquals(Map.of("Authorization", "Bearer " + "pat"), bearer.authHeaders());
    ConfluenceClient none = new ConfluenceClient("https://x", "", "");
    assertEquals(Map.of(), none.authHeaders());
  }

  @Test
  void usernameWithEmptyPasswordStillUsesBasic() {
    ConfluenceClient client = new ConfluenceClient("https://x", "user", "");
    byte[] credentials = "user:".getBytes(StandardCharsets.UTF_8);
    String encoded = Base64.getEncoder().encodeToString(credentials);
    assertEquals(Map.of("Authorization", "Basic " + encoded), client.authHeaders());
  }

  @Test
  void authHeaderSentOnRequest() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = new ConfluenceClient(server.baseUrl(), "user", "tok");
      client.setSleeper(sleeper);
      server.script(StubServer.Stub.json(Map.of("results", List.of(page()))));
      client.findPage("S", "T");
      StubServer.Captured sent = server.requests().get(0);
      byte[] credentials = "user:tok".getBytes(StandardCharsets.UTF_8);
      String encoded = Base64.getEncoder().encodeToString(credentials);
      assertEquals("Basic " + encoded, sent.header("Authorization"));
      server.assertDone();
    }
  }

  @Test
  void gatewayFailsFast() {
    ConfluenceClient client = new ConfluenceClient("https://tenant.api.atlassian.com/ex/1", "", "");
    ConfluenceException find =
        assertThrows(ConfluenceException.class, () -> client.findPage("S", "T"));
    assertTrue(find.getMessage().contains("v2 API"));
    ConfluenceException create = assertThrows(ConfluenceException.class, () -> createTop(client));
    assertTrue(create.getMessage().contains("v2 API"));
  }

  @Test
  void requestRetries429ThenSucceeds() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      client.setMaxRetries(2);
      byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
      server.script(
          StubServer.Stub.of(429, body, Map.of("Retry-After", "0")),
          StubServer.Stub.json(Map.of("ok", Boolean.TRUE)));
      ConfluenceClient.HttpResult result =
          client.request("GET", "/content/", null, null, null, null, "probe");
      assertEquals(200, result.status());
      assertEquals(Map.of("ok", Boolean.TRUE), result.body());
      assertEquals(2, server.requests().size());
      assertEquals(List.of(Double.valueOf(0)), sleeper.delays);
      server.assertDone();
    }
  }

  @Test
  void requestRetries503WithUnparseableRetryAfter() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
      server.script(
          StubServer.Stub.of(503, body, Map.of("Retry-After", "soon")),
          StubServer.Stub.json(Map.of("ok", Boolean.TRUE)));
      ConfluenceClient.HttpResult result =
          client.request("GET", "/content/", null, null, null, null, "probe");
      assertEquals(200, result.status());
      assertEquals(List.of(Double.valueOf(1)), sleeper.delays);
      server.assertDone();
    }
  }

  @Test
  void requestHttpErrorWraps() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      client.setMaxRetries(0);
      server.script(StubServer.Stub.json(Map.of("message", "forbidden"), 403));
      ConfluenceException e = assertThrows(ConfluenceException.class, () -> probe(client));
      assertEquals("probe: HTTP 403: forbidden", e.getMessage());
      assertEquals(Integer.valueOf(403), e.status());
      assertEquals(server.baseUrl() + "/rest/api/content/", e.url());
      assertTrue(e.body().contains("forbidden"));
      server.assertDone();
    }
  }

  @Test
  void requestPlainTextErrorKeepsBodyText() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      client.setMaxRetries(0);
      server.script(StubServer.Stub.text("boom", 500));
      ConfluenceException e = assertThrows(ConfluenceException.class, () -> probe(client));
      assertEquals("probe: HTTP 500: boom", e.getMessage());
      server.assertDone();
    }
  }

  @Test
  void requestIoErrorRetriesThenRaises() throws Exception {
    StubServer server = new StubServer();
    String baseUrl = server.baseUrl();
    server.close();
    RecordingSleeper sleeper = new RecordingSleeper();
    ConfluenceClient client = new ConfluenceClient(baseUrl, "", "");
    client.setSleeper(sleeper);
    client.setMaxRetries(1);
    ConfluenceException e = assertThrows(ConfluenceException.class, () -> probe(client));
    assertTrue(e.getMessage().contains("probe failed"));
    assertEquals(List.of(Double.valueOf(1)), sleeper.delays);
    assertNull(e.status());
    assertEquals(baseUrl + "/rest/api/content/", e.url());
  }

  @Test
  void nonJsonBodyReturnsBytes() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      server.script(StubServer.Stub.text("raw", 200));
      ConfluenceClient.HttpResult result =
          client.request("GET", "/x", null, null, null, null, "probe");
      assertEquals(200, result.status());
      byte[] expected = "raw".getBytes(StandardCharsets.UTF_8);
      assertEquals(List.of(expected[0], expected[1], expected[2]), bytes(result.body()));
      server.assertDone();
    }
  }

  private static List<Byte> bytes(Object value) {
    byte[] raw = (byte[]) value;
    List<Byte> out = new ArrayList<>();
    for (byte b : raw) {
      out.add(b);
    }
    return out;
  }

  @Test
  void findPageCaches() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      server.script(StubServer.Stub.json(Map.of("results", List.of(page()))));
      Map<String, Object> first = client.findPage("S", "T");
      Map<String, Object> second = client.findPage("S", "T");
      assertEquals("7", first.get("id"));
      assertEquals(first, second);
      assertNotSame(first, second);
      assertEquals(1, server.requests().size());
      String expected = "spaceKey=S&expand=ancestors%2Cversion&type=page&status=current&title=T";
      assertEquals(expected, server.requests().get(0).rawQuery);
      server.assertDone();
      client.invalidate("S", "T");
      server.script(StubServer.Stub.json(Map.of("results", List.of(page()))));
      client.findPage("S", "T");
      assertEquals(2, server.requests().size());
      server.assertDone();
    }
  }

  @Test
  void findPageNoneOn404AndEmpty() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      client.setMaxRetries(0);
      server.script(
          StubServer.Stub.json(Map.of("message", "nope"), 404),
          StubServer.Stub.json(Map.of("results", List.of())));
      assertNull(client.findPage("S", "Missing"));
      assertNull(client.findPage("S", "Empty"));
      assertNull(client.findPage("S", "Missing"));
      assertEquals(2, server.requests().size());
      server.assertDone();
    }
  }

  @Test
  void findPageOmitsEmptyTitle() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      server.script(StubServer.Stub.json(Map.of("results", List.of())));
      assertNull(client.findPage("S", ""));
      assertFalse(server.requests().get(0).rawQuery.contains("title="));
      server.assertDone();
    }
  }

  @Test
  void findPageEncodesTitle() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      server.script(StubServer.Stub.json(Map.of("results", List.of())));
      assertNull(client.findPage("S", "My Title"));
      assertTrue(server.requests().get(0).rawQuery.contains("title=My+Title"));
      server.assertDone();
    }
  }

  @Test
  void findPageCanonicalAlias() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      server.script(StubServer.Stub.json(Map.of("results", List.of(page()))));
      assertEquals("7", client.findPage("S", "t").get("id"));
      assertEquals("7", client.findPage("S", "T").get("id"));
      assertEquals(1, server.requests().size());
      server.assertDone();
    }
  }

  @Test
  void findPageAddsMissingBaseLink() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      Map<String, Object> bare = page();
      bare.put("_links", new LinkedHashMap<>());
      server.script(StubServer.Stub.json(Map.of("results", List.of(bare))));
      Map<String, Object> found = client.findPage("S", "T");
      assertEquals(server.baseUrl(), map(found.get("_links")).get("base"));
      server.assertDone();
    }
  }

  @Test
  void createPagePayloadAndCache() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      server.script(StubServer.Stub.json(page()));
      Map<String, Object> created = client.createPage("S", "T", "<p>new</p>", "page", "1");
      assertEquals("7", created.get("id"));
      StubServer.Captured sent = server.requests().get(0);
      assertEquals("POST", sent.method);
      Map<String, Object> payload = map(sent.bodyJson());
      assertEquals(List.of(Map.of("id", "1")), payload.get("ancestors"));
      Map<String, Object> storage = map(map(payload.get("body")).get("storage"));
      assertEquals("<p>new</p>", storage.get("value"));
      Map<String, Object> props = map(map(payload.get("metadata")).get("properties"));
      assertEquals(Map.of("value", "v2"), props.get("editor"));
      assertEquals("7", client.findPage("S", "T").get("id"));
      assertEquals(1, server.requests().size());
      server.assertDone();
    }
  }

  @Test
  void createPageWithoutParentOmitsAncestors() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      server.script(StubServer.Stub.json(page()));
      client.createPage("S", "T", "<p>new</p>", "page", null);
      Map<String, Object> payload = map(server.requests().get(0).bodyJson());
      assertFalse(payload.containsKey("ancestors"));
      server.assertDone();
    }
  }

  @Test
  void updatePagePayloadWithEmoji() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      server.script(StubServer.Stub.json(Map.of("id", "7")));
      Map<String, Object> page = page();
      client.updatePage(page, "<p>new</p>", true, "msg", "fixed", ROCKET);
      StubServer.Captured sent = server.requests().get(0);
      assertEquals("PUT", sent.method);
      assertEquals("/rest/api/content/7", sent.path);
      Map<String, Object> payload = map(sent.bodyJson());
      Map<String, Object> version = map(payload.get("version"));
      assertEquals(Long.valueOf(4), version.get("number"));
      assertEquals(Boolean.TRUE, version.get("minorEdit"));
      assertEquals("msg", version.get("message"));
      Map<String, Object> props = map(map(payload.get("metadata")).get("properties"));
      assertEquals(Map.of("value", "fixed"), props.get("content-appearance-published"));
      assertEquals(Map.of("value", "1f680"), props.get("emoji-title-draft"));
      assertEquals(Map.of("value", "1f680"), props.get("emoji-title-published"));
      assertEquals(List.of(Map.of("id", "1")), payload.get("ancestors"));
      server.assertDone();
    }
  }

  @Test
  void updatePageBlogpostOmitsAncestors() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      server.script(StubServer.Stub.json(Map.of("id", "7")));
      Map<String, Object> post = page();
      post.put("type", "blogpost");
      client.updatePage(post, "<p>new</p>", false, "", "full-width", "");
      Map<String, Object> payload = map(server.requests().get(0).bodyJson());
      assertFalse(payload.containsKey("ancestors"));
      server.assertDone();
    }
  }

  @Test
  void updatePageRetriesConflictOnce() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      client.setMaxRetries(0);
      Map<String, Object> current = new LinkedHashMap<>();
      current.put("id", "7");
      current.put("version", Map.of("number", Long.valueOf(9)));
      server.script(
          StubServer.Stub.json(Map.of("message", "conflict"), 409),
          StubServer.Stub.json(current),
          StubServer.Stub.json(Map.of("id", "7")));
      client.updatePage(page(), "<p>new</p>", false, "", "full-width", "");
      assertEquals(3, server.requests().size());
      StubServer.Captured reread = server.requests().get(1);
      assertEquals("GET", reread.method);
      assertEquals("expand=version", reread.rawQuery);
      StubServer.Captured retry = server.requests().get(2);
      Map<String, Object> payload = map(retry.bodyJson());
      assertEquals(Long.valueOf(10), map(payload.get("version")).get("number"));
      server.assertDone();
    }
  }

  @Test
  void updatePageDoubleConflictFriendly() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      client.setMaxRetries(0);
      Map<String, Object> current = new LinkedHashMap<>();
      current.put("id", "7");
      current.put("version", Map.of("number", Long.valueOf(9)));
      server.script(
          StubServer.Stub.json(Map.of("message", "c1"), 409),
          StubServer.Stub.json(current),
          StubServer.Stub.json(Map.of("message", "c2"), 409));
      Map<String, Object> page = page();
      ConfluenceException e = assertThrows(ConfluenceException.class, () -> update(client, page));
      assertTrue(e.getMessage().contains("conflicted twice"));
      server.assertDone();
    }
  }

  @Test
  void deletePageStatuses() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      byte[] empty = new byte[0];
      server.script(
          StubServer.Stub.of(204, empty, Map.of()), StubServer.Stub.of(200, empty, Map.of()));
      client.deletePage("7");
      client.deletePage("8");
      server.assertDone();
    }
  }

  @Test
  void getPageByIdUnexpectedStatus() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      // 201, not 302: the client follows redirects (like Python urllib), so a 302
      // never reaches the status check; any other non-200 status must error here.
      server.script(StubServer.Stub.json(Map.of(), 201));
      ConfluenceException e =
          assertThrows(ConfluenceException.class, () -> client.getPageById("7"));
      assertTrue(e.getMessage().contains("unexpected response HTTP 201"));
      server.assertDone();
    }
  }

  @Test
  void getPageByIdFollowsRedirect() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      server.script(
          StubServer.Stub.of(302, new byte[0], Map.of("Location", "/redirect-target")),
          StubServer.Stub.json(page()));
      Map<String, Object> found = client.getPageById("7");
      assertEquals("7", found.get("id"));
      assertEquals(2, server.requests().size());
      server.assertDone();
    }
  }

  @Test
  void getAttachmentsPaginates() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      Map<String, Object> first = new LinkedHashMap<>();
      first.put("results", List.of(Map.of("id", "a1", "title", "f.png")));
      first.put("_links", Map.of("next", "/more"));
      Map<String, Object> second = new LinkedHashMap<>();
      second.put("results", List.of(Map.of("id", "a2", "title", "g.png")));
      second.put("_links", Map.of());
      server.script(StubServer.Stub.json(first), StubServer.Stub.json(second));
      List<Map<String, Object>> found = client.getAttachments("7");
      assertEquals(List.of("a1", "a2"), List.of(found.get(0).get("id"), found.get(1).get("id")));
      List<StubServer.Captured> sent = server.requests();
      assertEquals(2, sent.size());
      assertEquals("expand=version%2Ccontainer&limit=100&start=0", sent.get(0).rawQuery);
      assertEquals("expand=version%2Ccontainer&limit=100&start=1", sent.get(1).rawQuery);
      server.assertDone();
    }
  }

  @Test
  void createAttachmentMultipart() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      Map<String, Object> created = Map.of("results", List.of(Map.of("id", "a1")));
      server.script(StubServer.Stub.json(created));
      byte[] data = "payload-bytes".getBytes(StandardCharsets.UTF_8);
      Map<String, Object> info = client.createAttachment("7", "f.png", "note", data);
      assertEquals(Map.of("id", "a1"), info);
      StubServer.Captured sent = server.requests().get(0);
      assertEquals("no-check", sent.header("X-Atlassian-Token"));
      String contentType = sent.header("Content-Type");
      String prefix = "multipart/form-data; boundary=----mark";
      assertTrue(contentType.startsWith(prefix));
      String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
      String text = new String(sent.body, StandardCharsets.UTF_8);
      assertTrue(text.startsWith("--" + boundary + "\r\n"));
      assertTrue(text.endsWith("--" + boundary + "--\r\n"));
      assertTrue(text.contains("name=\"comment\"\r\n\r\nnote\r\n"));
      assertTrue(text.contains("name=\"minorEdit\"\r\n\r\ntrue\r\n"));
      assertTrue(text.contains("name=\"file\"; filename=\"f.png\""));
      assertTrue(text.contains("Content-Type: image/png"));
      assertTrue(text.contains("payload-bytes"));
      int commentAt = text.indexOf("name=\"comment\"");
      int minorAt = text.indexOf("name=\"minorEdit\"");
      int fileAt = text.indexOf("name=\"file\"");
      assertTrue(commentAt < minorAt && minorAt < fileAt);
      server.assertDone();
    }
  }

  @Test
  void shortAttachmentResponseReturnedAsIs() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      server.script(StubServer.Stub.json(Map.of("id", "a1")));
      byte[] data = "x".getBytes(StandardCharsets.UTF_8);
      Map<String, Object> info = client.createAttachment("7", "f.png", "", data);
      assertEquals(Map.of("id", "a1"), info);
      server.assertDone();
    }
  }

  @Test
  void downloadAttachmentNeedsLink() {
    ConfluenceClient client = new ConfluenceClient("https://x", "", "");
    Map<String, Object> attachment = new LinkedHashMap<>();
    attachment.put("title", "f.png");
    ConfluenceException e =
        assertThrows(ConfluenceException.class, () -> client.downloadAttachment(attachment));
    assertTrue(e.getMessage().contains("no download link"));
  }

  @Test
  void downloadAttachmentRelativeAndAbsolute() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      byte[] first = "one".getBytes(StandardCharsets.UTF_8);
      byte[] second = "two".getBytes(StandardCharsets.UTF_8);
      server.script(
          StubServer.Stub.of(200, first, Map.of()), StubServer.Stub.of(200, second, Map.of()));
      Map<String, Object> relative = Map.of("_links", Map.of("download", "/dl/f.png"));
      byte[] firstBytes = client.downloadAttachment(relative);
      assertEquals("one", new String(firstBytes, StandardCharsets.UTF_8));
      String absolute = server.baseUrl() + "/dl/g.png";
      Map<String, Object> direct = Map.of("_links", Map.of("download", absolute));
      byte[] secondBytes = client.downloadAttachment(direct);
      assertEquals("two", new String(secondBytes, StandardCharsets.UTF_8));
      assertEquals("/dl/f.png", server.requests().get(0).path);
      assertEquals("/dl/g.png", server.requests().get(1).path);
      server.assertDone();
    }
  }

  @Test
  void ensureAttachmentSkipsIdentical(@TempDir Path dir) throws Exception {
    Path local = dir.resolve("f.png");
    Files.write(local, "same".getBytes(StandardCharsets.UTF_8));
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      Map<String, Object> existing = new LinkedHashMap<>();
      existing.put("id", "a1");
      existing.put("title", "f.png");
      existing.put("_links", Map.of("download", "/dl/f.png"));
      Map<String, Object> list = new LinkedHashMap<>();
      list.put("results", List.of(existing));
      list.put("_links", Map.of());
      byte[] remote = "same".getBytes(StandardCharsets.UTF_8);
      server.script(StubServer.Stub.json(list), StubServer.Stub.of(200, remote, Map.of()));
      EnsureResult result = client.ensureAttachment("7", local, "");
      assertFalse(result.changed());
      assertEquals("a1", result.info().get("id"));
      assertEquals(2, server.requests().size());
      server.assertDone();
    }
  }

  @Test
  void ensureAttachmentUpdatesOnDifference(@TempDir Path dir) throws Exception {
    Path local = dir.resolve("f.png");
    Files.write(local, "new".getBytes(StandardCharsets.UTF_8));
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      Map<String, Object> existing = new LinkedHashMap<>();
      existing.put("id", "a1");
      existing.put("title", "f.png");
      existing.put("_links", Map.of("download", "/dl/f.png"));
      Map<String, Object> list = new LinkedHashMap<>();
      list.put("results", List.of(existing));
      list.put("_links", Map.of());
      byte[] remote = "old".getBytes(StandardCharsets.UTF_8);
      server.script(
          StubServer.Stub.json(list),
          StubServer.Stub.of(200, remote, Map.of()),
          StubServer.Stub.json(Map.of("id", "a1")));
      EnsureResult result = client.ensureAttachment("7", local, "");
      assertTrue(result.changed());
      StubServer.Captured upload = server.requests().get(2);
      assertEquals("POST", upload.method);
      assertEquals("/rest/api/content/7/child/attachment/a1/data", upload.path);
      server.assertDone();
    }
  }

  @Test
  void ensureAttachmentCreatesWhenAbsent(@TempDir Path dir) throws Exception {
    Path local = dir.resolve("f.png");
    Files.write(local, "new".getBytes(StandardCharsets.UTF_8));
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      Map<String, Object> list = new LinkedHashMap<>();
      list.put("results", List.of());
      list.put("_links", Map.of());
      Map<String, Object> created = Map.of("results", List.of(Map.of("id", "a2")));
      server.script(StubServer.Stub.json(list), StubServer.Stub.json(created));
      EnsureResult result = client.ensureAttachment("7", local, "");
      assertTrue(result.changed());
      assertEquals("a2", result.info().get("id"));
      StubServer.Captured upload = server.requests().get(1);
      assertEquals("/rest/api/content/7/child/attachment", upload.path);
      server.assertDone();
    }
  }

  @Test
  void ensureAttachmentReplacesWhenDownloadFails(@TempDir Path dir) throws Exception {
    Path local = dir.resolve("f.png");
    Files.write(local, "new".getBytes(StandardCharsets.UTF_8));
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      Map<String, Object> existing = new LinkedHashMap<>();
      existing.put("id", "a1");
      existing.put("title", "f.png");
      existing.put("_links", Map.of("download", "/dl/f.png"));
      Map<String, Object> list = new LinkedHashMap<>();
      list.put("results", List.of(existing));
      list.put("_links", Map.of());
      server.script(
          StubServer.Stub.json(list),
          StubServer.Stub.text("gone", 500),
          StubServer.Stub.json(Map.of("id", "a1")));
      EnsureResult result = client.ensureAttachment("7", local, "");
      assertTrue(result.changed());
      assertEquals(3, server.requests().size());
      server.assertDone();
    }
  }

  @Test
  void syncLabelsReplaceAndAppend() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      Map<String, Object> list = new LinkedHashMap<>();
      List<Object> names = List.of(Map.of("name", "keep"), Map.of("name", "drop"));
      list.put("results", names);
      list.put("_links", Map.of());
      server.script(
          StubServer.Stub.json(list),
          StubServer.Stub.json(Map.of()),
          StubServer.Stub.of(204, new byte[0], Map.of()));
      client.syncLabels("7", List.of("keep", "add"), false);
      List<StubServer.Captured> sent = server.requests();
      assertEquals(
          List.of("GET", "POST", "DELETE"),
          List.of(sent.get(0).method, sent.get(1).method, sent.get(2).method));
      Object posted = sent.get(1).bodyJson();
      assertEquals(List.of(Map.of("prefix", "global", "name", "add")), posted);
      assertTrue(sent.get(2).rawQuery.contains("name=drop"));
      server.assertDone();
    }
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      Map<String, Object> list = new LinkedHashMap<>();
      list.put("results", List.of(Map.of("name", "keep")));
      list.put("_links", Map.of());
      server.script(StubServer.Stub.json(list), StubServer.Stub.json(Map.of()));
      client.syncLabels("7", List.of("keep", "add"), true);
      List<StubServer.Captured> sent = server.requests();
      assertEquals(List.of("GET", "POST"), List.of(sent.get(0).method, sent.get(1).method));
      server.assertDone();
    }
  }

  @Test
  void addLabelsEmptyIsNoop() throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      List<String> none = new ArrayList<>();
      none.add("");
      none.add(null);
      client.addLabels("7", List.of());
      client.addLabels("7", none);
      assertTrue(server.requests().isEmpty());
      server.assertDone();
    }
  }

  @Test
  void trustStoreMissingFileFails(@TempDir Path dir) throws Exception {
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      Path missing = dir.resolve("nope.p12");
      client.setTrustStore(missing, null);
      server.script(StubServer.Stub.json(Map.of()));
      ConfluenceException e =
          assertThrows(ConfluenceException.class, () -> client.getPageById("7"));
      assertTrue(e.getMessage().contains("nope.p12"));
      assertTrue(server.requests().isEmpty());
    }
  }

  @Test
  void trustStoreLoadsJksAndPkcs12(@TempDir Path dir) throws Exception {
    Path jks = dir.resolve("custom.jks");
    writeEmptyStore(jks, "JKS", "pw");
    Path p12 = dir.resolve("custom.p12");
    writeEmptyStore(p12, "PKCS12", "pw");
    for (Path store : List.of(jks, p12)) {
      try (StubServer server = new StubServer()) {
        RecordingSleeper sleeper = new RecordingSleeper();
        ConfluenceClient client = client(server, sleeper);
        client.setTrustStore(store, "pw");
        server.script(StubServer.Stub.json(page()));
        assertEquals("7", client.getPageById("7").get("id"));
        server.assertDone();
      }
    }
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      client.setTrustStore(p12, "wrong");
      ConfluenceException e =
          assertThrows(ConfluenceException.class, () -> client.getPageById("7"));
      assertTrue(e.getMessage().contains("custom.p12"));
      assertTrue(server.requests().isEmpty());
    }
  }

  @Test
  void trustStoreUnknownExtensionUsesDefaultType(@TempDir Path dir) throws Exception {
    Path store = dir.resolve("custom.store");
    writeEmptyStore(store, "PKCS12", "pw");
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = client(server, sleeper);
      client.setTrustStore(store, "pw");
      server.script(StubServer.Stub.json(page()));
      assertEquals("7", client.getPageById("7").get("id"));
      server.assertDone();
    }
  }

  private static void writeEmptyStore(Path file, String type, String password) throws Exception {
    KeyStore store = KeyStore.getInstance(type);
    store.load(null, null);
    try (OutputStream out = Files.newOutputStream(file)) {
      store.store(out, password.toCharArray());
    }
  }

  @Test
  void insecureWarnsOnceWithoutToken() throws Exception {
    Logger logger = Logger.getLogger(ConfluenceClient.class.getName());
    List<String> warnings = new ArrayList<>();
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            warnings.add(record.getMessage());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(capture);
    logger.setUseParentHandlers(false);
    try (StubServer server = new StubServer()) {
      RecordingSleeper sleeper = new RecordingSleeper();
      ConfluenceClient client = new ConfluenceClient(server.baseUrl(), "user", "s3cret-tok");
      client.setSleeper(sleeper);
      client.setInsecureSkipTlsVerify(true);
      Map<String, Object> empty = Map.of("results", List.of());
      server.script(StubServer.Stub.json(empty), StubServer.Stub.json(empty));
      assertNull(client.findPage("S", "A"));
      assertNull(client.findPage("S", "B"));
      assertEquals(1, warnings.size());
      assertTrue(warnings.get(0).contains("DISABLED"));
      assertFalse(warnings.get(0).contains("s3cret-tok"));
      server.assertDone();
    } finally {
      logger.removeHandler(capture);
      logger.setUseParentHandlers(true);
    }
  }
}
