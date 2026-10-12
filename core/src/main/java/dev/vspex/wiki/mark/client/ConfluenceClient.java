package dev.vspex.wiki.mark.client;

import dev.vspex.wiki.mark.json.Json;
import dev.vspex.wiki.mark.json.JsonParseException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal Confluence v1 REST client. Port of {@code mark.confluence.ConfluenceClient} over {@link
 * HttpClient} (HTTP/1.1, default JVM trust).
 *
 * <p>Call flow mirrors the Python client: find/get/create/update/delete pages, paginated
 * attachments and labels, multipart upload, and label sync. The {@code api.atlassian.com} gateway
 * fails fast (v2 API out of scope). The Python {@code keep_concurrent_edits} flag is dropped: no
 * caller ever enabled it, so a 409 conflict is always re-read once and retried.
 *
 * <p>Options must be set before the first request; the {@link HttpClient} is built lazily. The
 * client itself is thread-safe once configured. Instances are cheap to create but hold a page cache
 * for the run, so callers keep one client per sync run.
 */
public class ConfluenceClient implements PageStore {
  private static final Set<Integer> RETRY_STATUSES = Set.of(429, 503);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Logger LOG = Logger.getLogger(ConfluenceClient.class.getName());

  /** Low-level request outcome: status, decoded body (JSON value or raw bytes), headers. */
  record HttpResult(int status, Object body, HttpHeaders headers) {}

  /** Sleeps between retries; tests substitute a recorder. */
  interface Sleeper {
    void sleep(double seconds) throws InterruptedException;
  }

  private final String baseUrl;
  private final String v1;
  private final String username;
  private final String password;
  private final boolean gateway;
  private final Map<String, Map<String, Object>> pageCache = new HashMap<>();

  private Duration timeout = Duration.ofSeconds(30);
  private int maxRetries = 3;
  private boolean insecureSkipTlsVerify;
  private Path trustStore;
  private String trustStorePassword;

  private volatile HttpClient http;
  private Sleeper sleeper = seconds -> Thread.sleep((long) (seconds * 1000));
  private boolean insecureWarned;

  /**
   * Creates a client. Options must be set before the first request.
   *
   * @param baseUrl site base URL, trailing slashes stripped
   * @param username account email for Cloud, or empty for PAT auth
   * @param password API token or personal access token
   */
  public ConfluenceClient(String baseUrl, String username, String password) {
    if (baseUrl == null || baseUrl.isEmpty()) {
      throw new ConfluenceException("base-url is required");
    }
    this.baseUrl = stripTrailingSlashes(baseUrl);
    this.v1 = this.baseUrl + "/rest/api";
    this.username = username == null ? "" : username;
    this.password = password == null ? "" : password;
    this.gateway = this.baseUrl.contains("api.atlassian.com");
  }

  /** Base URL of the wiki (trailing slashes stripped). */
  @Override
  public String baseUrl() {
    return baseUrl;
  }

  /**
   * Per-request timeout (connect stays 10s); default 30s.
   *
   * @param timeout the timeout
   * @return this client
   */
  public ConfluenceClient setTimeout(Duration timeout) {
    if (timeout == null || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be non-negative");
    }
    this.timeout = timeout;
    return this;
  }

  /**
   * Retries after the initial attempt for 429/503 and transport errors; default 3.
   *
   * @param maxRetries the retry count
   * @return this client
   */
  public ConfluenceClient setMaxRetries(int maxRetries) {
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must be non-negative");
    }
    this.maxRetries = maxRetries;
    return this;
  }

  /**
   * Trust any certificate and skip hostname verification. Default off; logs a loud warning.
   *
   * @param insecureSkipTlsVerify whether to skip verification
   * @return this client
   */
  public ConfluenceClient setInsecureSkipTlsVerify(boolean insecureSkipTlsVerify) {
    this.insecureSkipTlsVerify = insecureSkipTlsVerify;
    return this;
  }

  /**
   * Extra trust store AUGMENTING the default JVM store (corporate CA plus public CAs).
   *
   * @param trustStore the trust store file
   * @param password the trust store password
   * @return this client
   */
  public ConfluenceClient setTrustStore(Path trustStore, String password) {
    this.trustStore = trustStore;
    this.trustStorePassword = password;
    return this;
  }

  void setSleeper(Sleeper sleeper) {
    this.sleeper = sleeper;
  }

  // -- low-level HTTP ------------------------------------------------------

  Map<String, String> authHeaders() {
    if (!username.isEmpty()) {
      byte[] credentials = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
      String token = Base64.getEncoder().encodeToString(credentials);
      return Map.of("Authorization", "Basic " + token);
    }
    if (!password.isEmpty()) {
      return Map.of("Authorization", "Bearer " + password);
    }
    return Map.of();
  }

  HttpResult request(
      String method,
      String path,
      Map<String, String> params,
      Object jsonBody,
      byte[] data,
      Map<String, String> headers,
      String what) {
    String url = v1 + path;
    if (params != null && !params.isEmpty()) {
      StringJoiner query = new StringJoiner("&");
      params.forEach((key, value) -> query.add(enc(key) + "=" + enc(value)));
      url += "?" + query;
    }
    byte[] body = data;
    Map<String, String> allHeaders = headers;
    if (jsonBody != null) {
      body = Json.write(jsonBody).getBytes(StandardCharsets.UTF_8);
      allHeaders = new LinkedHashMap<>();
      if (headers != null) {
        allHeaders.putAll(headers);
      }
      allHeaders.put("Content-Type", "application/json");
    }
    return sendWithRetry(method, url, body, allHeaders, what);
  }

  private HttpResult sendWithRetry(
      String method, String url, byte[] body, Map<String, String> headers, String what) {
    ConfluenceException lastError = null;
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      HttpResponse<byte[]> response;
      try {
        response = sendOnce(method, url, body, headers);
      } catch (IOException e) {
        lastError = new ConfluenceException(what + " failed: " + e, null, url, "");
        if (attempt < maxRetries) {
          nap(what, Math.pow(2, attempt));
          continue;
        }
        throw lastError;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ConfluenceException(what + " failed: interrupted", null, url, "");
      }
      int status = response.statusCode();
      HttpHeaders responseHeaders = response.headers();
      if (RETRY_STATUSES.contains(status) && attempt < maxRetries) {
        nap(what, retryDelay(responseHeaders, attempt));
        continue;
      }
      if (status >= 400) {
        throw asError(status, response.body(), url, what, responseHeaders);
      }
      return new HttpResult(status, decode(response.body(), responseHeaders), responseHeaders);
    }
    throw lastError != null ? lastError : new ConfluenceException(what + " failed", null, url, "");
  }

  private HttpResponse<byte[]> sendOnce(
      String method, String url, byte[] body, Map<String, String> headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
    HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
    if (body != null) {
      publisher = HttpRequest.BodyPublishers.ofByteArray(body);
    }
    builder.method(method, publisher);
    authHeaders().forEach(builder::header);
    if (headers != null) {
      headers.forEach(builder::setHeader);
    }
    return http().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private void nap(String what, double seconds) {
    try {
      sleeper.sleep(seconds);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ConfluenceException(what + " failed: interrupted", null, "", "");
    }
  }

  private static double retryDelay(HttpHeaders headers, int attempt) {
    String retryAfter = headers.firstValue("Retry-After").orElse("");
    if (!retryAfter.isEmpty()) {
      try {
        return Math.max(0.0, Double.parseDouble(retryAfter));
      } catch (NumberFormatException unparseable) {
        // Fall through to exponential backoff.
      }
    }
    return Math.pow(2, attempt);
  }

  private static Object decode(byte[] payload, HttpHeaders headers) {
    String contentType = headers.firstValue("Content-Type").orElse("");
    if (contentType.contains("json")) {
      try {
        return Json.parse(new String(payload, StandardCharsets.UTF_8));
      } catch (JsonParseException bad) {
        return null;
      }
    }
    return payload;
  }

  private static ConfluenceException asError(
      int status, byte[] payload, String url, String what, HttpHeaders headers) {
    String text = new String(payload, StandardCharsets.UTF_8);
    String detail = text;
    String contentType = headers.firstValue("Content-Type").orElse("");
    if (contentType.toLowerCase(Locale.ROOT).contains("json")) {
      try {
        Object parsed = Json.parse(text);
        if (parsed instanceof Map<?, ?> map) {
          Object message = map.get("message");
          detail = message == null ? text : String.valueOf(message);
        }
      } catch (JsonParseException bad) {
        // Keep the raw text as the detail.
      }
    }
    return ConfluenceException.httpError(what, status, url, detail, text);
  }

  private void requireV1(String what) {
    if (gateway) {
      throw new ConfluenceException(
          what
              + " through the api.atlassian.com gateway needs the v2 API, "
              + "which this client does not implement yet; use a site base URL "
              + "(https://<tenant>.atlassian.net/wiki) with an API token instead");
    }
  }

  // -- pages ---------------------------------------------------------------

  /**
   * Live page by title, or null. Results are cached per run.
   *
   * @param space the space key
   * @param title the page title
   * @return the page map, or null when absent
   */
  public Map<String, Object> findPage(String space, String title) {
    return findPage(space, title, "page");
  }

  /** Live page by title and type, or null. Results are cached per run. */
  public Map<String, Object> findPage(String space, String title, String type) {
    String what = "find page '" + title + "' in space " + space;
    requireV1(what);
    String key = cacheKey(space, title, type);
    if (pageCache.containsKey(key)) {
      Map<String, Object> cached = pageCache.get(key);
      return cached == null ? null : new LinkedHashMap<>(cached);
    }
    Map<String, String> params = new LinkedHashMap<>();
    params.put("spaceKey", space);
    params.put("expand", "ancestors,version");
    params.put("type", type);
    params.put("status", "current");
    if (!title.isEmpty()) {
      params.put("title", title);
    }
    HttpResult result;
    try {
      result = request("GET", "/content/", params, null, null, null, what);
    } catch (ConfluenceException e) {
      if (e.status() != null && e.status() == 404) {
        pageCache.put(key, null);
        return null;
      }
      throw e;
    }
    // request() throws on every 4xx/5xx, so a 404 never arrives here.
    List<?> results = listAt(result.body(), "results");
    if (results.isEmpty() || !(results.get(0) instanceof Map)) {
      pageCache.put(key, null);
      return null;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> page = (Map<String, Object>) results.get(0);
    Map<String, Object> links = mapOrNew(page, "_links");
    if (!(links.get("base") instanceof String base) || base.isEmpty()) {
      links.put("base", baseUrl);
    }
    pageCache.put(key, page);
    // Canonical key: Confluence matches titles case-insensitively.
    pageCache.putIfAbsent(
        cacheKey(space, str(page, "title", title), str(page, "type", type)), page);
    return new LinkedHashMap<>(page);
  }

  /**
   * Drops one cached page.
   *
   * @param space the space key
   * @param title the page title
   */
  public void invalidate(String space, String title) {
    invalidate(space, title, "page");
  }

  /**
   * Drops one cached page.
   *
   * @param space the space key
   * @param title the page title
   * @param type the page type
   */
  public void invalidate(String space, String title, String type) {
    pageCache.remove(cacheKey(space, title, type));
  }

  public Map<String, Object> getPageById(String pageId) {
    return getPageById(pageId, "ancestors,version,body.storage");
  }

  /**
   * Page by id with the given expansions.
   *
   * @param pageId the remote page id
   * @param expand the expand parameter
   * @return the page map
   */
  public Map<String, Object> getPageById(String pageId, String expand) {
    String what = "read page " + pageId;
    requireV1(what);
    HttpResult result =
        request("GET", "/content/" + pageId, Map.of("expand", expand), null, null, null, what);
    if (result.status() != 200 || !(result.body() instanceof Map)) {
      throw unexpected(what, result.status(), "/content/" + pageId);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> page = (Map<String, Object>) result.body();
    return page;
  }

  public Map<String, Object> createPage(
      String space, String title, String body, String type, String parentId) {
    String what = "create page '" + title + "' in space " + space;
    requireV1(what);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("type", type);
    payload.put("title", title);
    payload.put("space", Map.of("key", space));
    payload.put("body", Map.of("storage", Map.of("representation", "storage", "value", body)));
    payload.put("metadata", Map.of("properties", Map.of("editor", Map.of("value", "v2"))));
    if (parentId != null && !parentId.isEmpty()) {
      payload.put("ancestors", List.of(Map.of("id", parentId)));
    }
    HttpResult result = request("POST", "/content/", null, payload, null, null, what);
    if (result.status() != 200 || !(result.body() instanceof Map)) {
      throw unexpected(what, result.status(), "/content/");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> page = (Map<String, Object>) result.body();
    pageCache.put(cacheKey(space, title, type), page);
    return page;
  }

  /**
   * Updates a page's content; retries a 409 conflict once, afresh (re-reading the version, which
   * may already include someone else's concurrent edit).
   */
  public Map<String, Object> updatePage(
      Map<String, Object> page,
      String content,
      boolean minorEdit,
      String versionMessage,
      String appearance,
      String emoji) {
    String what = "update page '" + page.get("title") + "' (" + page.get("id") + ")";
    requireV1(what);
    long version = versionNumber(page);
    try {
      return putPage(page, content, version + 1, minorEdit, versionMessage, appearance, emoji);
    } catch (ConfluenceException e) {
      if (e.status() == null || e.status() != 409) {
        throw e;
      }
      Map<String, Object> current = getPageById(String.valueOf(page.get("id")), "version");
      long currentVersion = versionNumber(current);
      try {
        return putPage(
            page, content, currentVersion + 1, minorEdit, versionMessage, appearance, emoji);
      } catch (ConfluenceException retry) {
        if (retry.status() != null && retry.status() == 409) {
          throw new ConfluenceException(
              "update of page '"
                  + page.get("title")
                  + "' ("
                  + page.get("id")
                  + ") "
                  + "conflicted twice; something else is writing to the page, "
                  + "run mark again once it has stopped");
        }
        throw retry;
      }
    }
  }

  private Map<String, Object> putPage(
      Map<String, Object> page,
      String content,
      long nextVersion,
      boolean minorEdit,
      String versionMessage,
      String appearance,
      String emoji) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("content-appearance-published", Map.of("value", appearance));
    if (!emoji.isEmpty()) {
      String codepoint = Integer.toHexString(emoji.codePointAt(0));
      properties.put("emoji-title-draft", Map.of("value", codepoint));
      properties.put("emoji-title-published", Map.of("value", codepoint));
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", page.get("id"));
    payload.put("type", str(page, "type", "page"));
    payload.put("title", page.get("title"));
    Map<String, Object> version = new LinkedHashMap<>();
    version.put("number", nextVersion);
    version.put("minorEdit", minorEdit);
    version.put("message", versionMessage);
    payload.put("version", version);
    payload.put("body", Map.of("storage", Map.of("value", content, "representation", "storage")));
    payload.put("metadata", Map.of("properties", properties));
    List<?> ancestors = page.get("ancestors") instanceof List<?> list ? list : List.of();
    Object last = ancestors.isEmpty() ? null : ancestors.get(ancestors.size() - 1);
    if (!"blogpost".equals(str(page, "type")) && last instanceof Map<?, ?> parent) {
      payload.put("ancestors", List.of(Map.of("id", String.valueOf(parent.get("id")))));
    }
    String what = "update page '" + page.get("title") + "' (" + page.get("id") + ")";
    HttpResult result =
        request("PUT", "/content/" + page.get("id"), null, payload, null, null, what);
    if (result.status() != 200) {
      throw unexpected(what, result.status(), "/content/" + page.get("id"));
    }
    if (result.body() instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> updated = (Map<String, Object>) result.body();
      return updated;
    }
    return Map.of();
  }

  /**
   * Moves a page to the trash. Exists only for tests/cleanup; never exposed as a task.
   *
   * @param pageId the remote page id
   */
  public void deletePage(String pageId) {
    String what = "delete page " + pageId;
    requireV1(what);
    HttpResult result = request("DELETE", "/content/" + pageId, null, null, null, null, what);
    if (result.status() != 200 && result.status() != 204) {
      throw unexpected(what, result.status(), "/content/" + pageId);
    }
  }

  // -- attachments ---------------------------------------------------------

  /**
   * All attachments of a page, paginated.
   *
   * @param pageId the remote page id
   * @return the attachment maps
   */
  public List<Map<String, Object>> getAttachments(String pageId) {
    String what = "list attachments of page " + pageId;
    requireV1(what);
    List<Map<String, Object>> found = new ArrayList<>();
    int start = 0;
    int pageSize = 100;
    while (true) {
      Map<String, String> params = new LinkedHashMap<>();
      params.put("expand", "version,container");
      params.put("limit", String.valueOf(pageSize));
      params.put("start", String.valueOf(start));
      String path = "/content/" + pageId + "/child/attachment";
      HttpResult result = request("GET", path, params, null, null, null, what);
      if (result.status() != 200 || !(result.body() instanceof Map)) {
        throw unexpected(what, result.status(), path);
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> body = (Map<String, Object>) result.body();
      List<?> results = body.get("results") instanceof List<?> list ? list : List.of();
      for (Object item : results) {
        if (item instanceof Map) {
          @SuppressWarnings("unchecked")
          Map<String, Object> attachment = (Map<String, Object>) item;
          found.add(attachment);
        }
      }
      if (results.isEmpty() || (!hasNext(body) && results.size() < pageSize)) {
        break;
      }
      start += results.size();
    }
    return found;
  }

  private Map<String, Object> attachmentRequest(
      String path, String filename, String comment, byte[] data, String what) {
    String base = basename(filename);
    String contentType = URLConnection.guessContentTypeFromName(base);
    if (contentType == null) {
      contentType = "application/octet-stream";
    }
    Multipart multipart = encodeMultipart(comment, base, data, contentType);

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", multipart.contentType());
    headers.put("X-Atlassian-Token", "no-check");
    HttpResult result = request("POST", path, null, null, multipart.body(), headers, what);
    if (result.status() != 200) {
      throw unexpected(what, result.status(), path);
    }
    if (result.body() instanceof Map<?, ?> map) {
      Object results = map.get("results");
      if (results instanceof List<?> list
          && !list.isEmpty()
          && list.get(0) instanceof Map<?, ?> first) {
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) first;
        return info;
      }
      // Short variant some instances return (no "results" wrapper).
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) map;
      return info;
    }
    throw new ConfluenceException(
        what + ": the Confluence REST API returned 0 json objects, expected at least 1",
        result.status(),
        v1 + path,
        "");
  }

  /**
   * Uploads a new attachment.
   *
   * @param pageId the remote page id
   * @param filename the attachment file name
   * @param comment the attachment comment
   * @param data the attachment bytes
   * @return the attachment info
   */
  public Map<String, Object> createAttachment(
      String pageId, String filename, String comment, byte[] data) {
    String what = "attach '" + filename + "' to page " + pageId;
    requireV1(what);
    return attachmentRequest(
        "/content/" + pageId + "/child/attachment", filename, comment, data, what);
  }

  /**
   * Uploads a new version of an attachment.
   *
   * @param pageId the remote page id
   * @param attachmentId the attachment id
   * @param filename the attachment file name
   * @param comment the attachment comment
   * @param data the attachment bytes
   * @return the attachment info
   */
  public Map<String, Object> updateAttachment(
      String pageId, String attachmentId, String filename, String comment, byte[] data) {
    String what = "upload a new version of attachment '" + filename + "' of page " + pageId;
    requireV1(what);
    return attachmentRequest(
        "/content/" + pageId + "/child/attachment/" + attachmentId + "/data",
        filename,
        comment,
        data,
        what);
  }

  /**
   * Follows {@code _links.download} (absolute or base-relative) with the same auth. No retry.
   *
   * @param attachment the attachment map
   * @return the bytes
   */
  public byte[] downloadAttachment(Map<String, Object> attachment) {
    String title = str(attachment, "title");
    Object links = attachment.get("_links");
    String download = links instanceof Map<?, ?> map ? str(castMap(map), "download") : "";
    if (download.isEmpty()) {
      throw new ConfluenceException("attachment '" + title + "' has no download link");
    }
    String url = download.startsWith("http") ? download : baseUrl + download;
    HttpResponse<byte[]> response;
    try {
      response = sendOnce("GET", url, null, null);
    } catch (IOException e) {
      throw new ConfluenceException("download attachment '" + title + "': " + e, null, url, "");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ConfluenceException(
          "download attachment '" + title + "': interrupted", null, url, "");
    }
    if (response.statusCode() >= 400) {
      throw new ConfluenceException(
          "download attachment '" + title + "': HTTP " + response.statusCode(),
          response.statusCode(),
          url,
          "");
    }
    return response.body();
  }

  /**
   * Uploads {@code localPath} unless an identical attachment exists. Returns the remote info plus
   * whether anything changed; a failed download counts as a mismatch, so the file is replaced.
   */
  public EnsureResult ensureAttachment(String pageId, Path localPath, String comment) {
    byte[] data;
    try {
      data = Files.readAllBytes(localPath);
    } catch (IOException e) {
      throw new ConfluenceException("read attachment '" + localPath + "': " + e, null, "", "");
    }
    String filename = basename(localPath.toString());
    for (Map<String, Object> existing : getAttachments(pageId)) {
      if (!filename.equals(str(existing, "title"))) {
        continue;
      }
      byte[] remote;
      try {
        remote = downloadAttachment(existing);
      } catch (ConfluenceException failed) {
        remote = null;
      }
      if (remote != null && MessageDigest.isEqual(sha256(remote), sha256(data))) {
        return new EnsureResult(existing, false);
      }
      return new EnsureResult(
          updateAttachment(pageId, str(existing, "id"), filename, comment, data), true);
    }
    return new EnsureResult(createAttachment(pageId, filename, comment, data), true);
  }

  // -- labels --------------------------------------------------------------

  /**
   * Global labels of a page.
   *
   * @param pageId the remote page id
   * @return the label names
   */
  public List<String> getLabels(String pageId) {
    return getLabels(pageId, "global");
  }

  /**
   * Labels of a page for one prefix, paginated.
   *
   * @param pageId the remote page id
   * @param prefix the label prefix
   * @return the label names
   */
  public List<String> getLabels(String pageId, String prefix) {
    String what = "read labels of page " + pageId;
    requireV1(what);
    List<String> names = new ArrayList<>();
    int start = 0;
    int pageSize = 50;
    while (true) {
      Map<String, String> params = new LinkedHashMap<>();
      params.put("prefix", prefix);
      params.put("limit", String.valueOf(pageSize));
      params.put("start", String.valueOf(start));
      HttpResult result =
          request("GET", "/content/" + pageId + "/label", params, null, null, null, what);
      if (result.status() != 200 || !(result.body() instanceof Map)) {
        throw unexpected(what, result.status(), "/content/" + pageId + "/label");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> body = (Map<String, Object>) result.body();
      List<?> results = body.get("results") instanceof List<?> list ? list : List.of();
      for (Object item : results) {
        if (item instanceof Map<?, ?> label) {
          names.add(str(castMap(label), "name"));
        }
      }
      if (results.isEmpty() || (!hasNext(body) && results.size() < pageSize)) {
        break;
      }
      start += results.size();
    }
    List<String> kept = new ArrayList<>();
    for (String name : names) {
      if (!name.isEmpty()) {
        kept.add(name);
      }
    }
    return kept;
  }

  /**
   * Adds global labels, skipping blanks.
   *
   * @param pageId the remote page id
   * @param labels the labels to add
   */
  public void addLabels(String pageId, List<String> labels) {
    List<String> kept = new ArrayList<>();
    for (String label : labels) {
      if (label != null && !label.isEmpty()) {
        kept.add(label);
      }
    }
    if (kept.isEmpty()) {
      return;
    }
    String what = "add labels to page " + pageId;
    requireV1(what);
    List<Map<String, String>> payload = new ArrayList<>();
    for (String label : kept) {
      payload.add(Map.of("prefix", "global", "name", label));
    }
    HttpResult result =
        request("POST", "/content/" + pageId + "/label", null, payload, null, null, what);
    if (result.status() != 200) {
      throw unexpected(what, result.status(), "/content/" + pageId + "/label");
    }
  }

  /**
   * Removes one label.
   *
   * @param pageId the remote page id
   * @param label the label to remove
   */
  public void deleteLabel(String pageId, String label) {
    String what = "remove label '" + label + "' from page " + pageId;
    requireV1(what);
    String path = "/content/" + pageId + "/label";
    HttpResult result = request("DELETE", path, Map.of("name", label), null, null, null, what);
    if (result.status() != 200 && result.status() != 204) {
      throw unexpected(what, result.status(), path);
    }
  }

  /** Makes the page carry exactly {@code labels} (or adds them with append). */
  public void syncLabels(String pageId, List<String> labels, boolean append) {
    Set<String> wanted = new LinkedHashSet<>();
    for (String label : labels) {
      if (label != null && !label.isEmpty()) {
        wanted.add(label);
      }
    }
    List<String> current = getLabels(pageId);
    List<String> missing = new ArrayList<>();
    for (String label : wanted) {
      if (!current.contains(label)) {
        missing.add(label);
      }
    }
    addLabels(pageId, missing);
    if (!append) {
      for (String label : current) {
        if (!wanted.contains(label)) {
          deleteLabel(pageId, label);
        }
      }
    }
  }

  // -- HTTP plumbing --------------------------------------------------------

  private HttpClient http() {
    HttpClient client = http;
    if (client == null) {
      synchronized (this) {
        client = http;
        if (client == null) {
          client = buildHttp();
          http = client;
        }
      }
    }
    return client;
  }

  private HttpClient buildHttp() {
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL);
    if (insecureSkipTlsVerify) {
      if (!insecureWarned) {
        insecureWarned = true;
        LOG.warning("TLS certificate verification DISABLED for " + baseUrl);
      }
      builder.sslContext(trustAllContext());
      SSLParameters insecure = new SSLParameters();
      insecure.setEndpointIdentificationAlgorithm(null);
      builder.sslParameters(insecure);
    } else if (trustStore != null) {
      builder.sslContext(augmentingContext(trustStore, trustStorePassword));
    }
    return builder.build();
  }

  private static SSLContext trustAllContext() {
    TrustManager[] trustAll =
        new TrustManager[] {
          new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }
          }
        };
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trustAll, new SecureRandom());
      return context;
    } catch (GeneralSecurityException e) {
      throw new ConfluenceException("cannot create insecure TLS context: " + e, null, "", "");
    }
  }

  /** Default JVM trust plus the custom store (tried in that order). */
  private static SSLContext augmentingContext(Path file, String password) {
    if (!Files.isRegularFile(file)) {
      throw new ConfluenceException(
          "trust store '" + file + "' does not exist or is not a file", null, "", "");
    }
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    String type;
    if (name.endsWith(".jks")) {
      type = "JKS";
    } else if (name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".pkcs12")) {
      type = "PKCS12";
    } else {
      type = KeyStore.getDefaultType();
    }
    try (InputStream in = Files.newInputStream(file)) {
      KeyStore custom = KeyStore.getInstance(type);
      custom.load(in, password == null ? null : password.toCharArray());
      TrustManagerFactory customFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      customFactory.init(custom);
      TrustManagerFactory defaultFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      defaultFactory.init((KeyStore) null);
      X509TrustManager first = x509Only(defaultFactory);
      X509TrustManager second = x509Only(customFactory);
      TrustManager composite =
          new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
              first.checkClientTrusted(chain, authType);
            }

            public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
              try {
                first.checkServerTrusted(chain, authType);
              } catch (CertificateException e) {
                second.checkServerTrusted(chain, authType);
              }
            }

            public X509Certificate[] getAcceptedIssuers() {
              X509Certificate[] a = first.getAcceptedIssuers();
              X509Certificate[] b = second.getAcceptedIssuers();
              X509Certificate[] both = new X509Certificate[a.length + b.length];
              System.arraycopy(a, 0, both, 0, a.length);
              System.arraycopy(b, 0, both, a.length, b.length);
              return both;
            }
          };
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {composite}, null);
      return context;
    } catch (IOException | GeneralSecurityException e) {
      throw new ConfluenceException("load trust store '" + file + "': " + e, null, "", "");
    }
  }

  private static X509TrustManager x509Only(TrustManagerFactory factory) {
    for (TrustManager manager : factory.getTrustManagers()) {
      if (manager instanceof X509TrustManager x509) {
        return x509;
      }
    }
    throw new ConfluenceException("no X509TrustManager available", null, "", "");
  }

  // -- multipart -----------------------------------------------------------

  private record Multipart(byte[] body, String contentType) {}

  private static Multipart encodeMultipart(
      String comment, String filename, byte[] data, String contentType) {
    byte[] random = new byte[12];
    new SecureRandom().nextBytes(random);
    StringBuilder hex = new StringBuilder(24);
    for (byte b : random) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    String boundary = "----mark" + hex;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeAscii(out, "--" + boundary + "\r\n");
    writeAscii(out, "Content-Disposition: form-data; name=\"comment\"\r\n\r\n");
    writeAscii(out, comment + "\r\n");
    writeAscii(out, "--" + boundary + "\r\n");
    writeAscii(out, "Content-Disposition: form-data; name=\"minorEdit\"\r\n\r\n");
    writeAscii(out, "true\r\n");
    writeAscii(out, "--" + boundary + "\r\n");
    String fileHead =
        "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n";
    writeAscii(out, fileHead);
    writeAscii(out, "Content-Type: " + contentType + "\r\n\r\n");
    out.write(data, 0, data.length);
    writeAscii(out, "\r\n--" + boundary + "--\r\n");
    return new Multipart(out.toByteArray(), "multipart/form-data; boundary=" + boundary);
  }

  private static void writeAscii(ByteArrayOutputStream out, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    out.write(bytes, 0, bytes.length);
  }

  // -- small helpers --------------------------------------------------------

  private ConfluenceException unexpected(String what, int status, String path) {
    return new ConfluenceException(
        what + ": unexpected response HTTP " + status, status, v1 + path, "");
  }

  private static String cacheKey(String space, String title, String type) {
    return space + "\0" + title + "\0" + type;
  }

  private static String stripTrailingSlashes(String url) {
    int end = url.length();
    while (end > 0 && url.charAt(end - 1) == '/') {
      end--;
    }
    return url.substring(0, end);
  }

  private static String enc(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }

  private static String basename(String path) {
    int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return cut < 0 ? path : path.substring(cut + 1);
  }

  private static String str(Map<String, Object> map, String key) {
    return str(map, key, "");
  }

  private static String str(Map<String, Object> map, String key, String fallback) {
    Object value = map.get(key);
    return value instanceof String s ? s : fallback;
  }

  private static boolean hasNext(Map<String, Object> body) {
    Object links = body.get("_links");
    Object next = links instanceof Map<?, ?> map ? map.get("next") : null;
    return next instanceof String s && !s.isEmpty();
  }

  private static List<?> listAt(Object body, String key) {
    if (body instanceof Map<?, ?> map && map.get(key) instanceof List<?> list) {
      return list;
    }
    return List.of();
  }

  private static Map<String, Object> mapOrNew(Map<String, Object> page, String key) {
    if (page.get(key) instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> existing = (Map<String, Object>) map;
      return existing;
    }
    Map<String, Object> created = new LinkedHashMap<>();
    page.put(key, created);
    return created;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }

  private static long versionNumber(Map<String, Object> page) {
    Object version = page.get("version");
    Object number = version instanceof Map<?, ?> map ? map.get("number") : null;
    return number instanceof Number n ? n.longValue() : 0;
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
