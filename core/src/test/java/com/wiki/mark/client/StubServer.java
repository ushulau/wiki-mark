package com.wiki.mark.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wiki.mark.json.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executors;

/**
 * Scripted JDK-{@link HttpServer} stub recording every request. Replaces the Python harness's
 * monkeypatched {@code urlopen}: tests queue responses, run real HTTP through the client, then
 * assert the captured requests and that the script was fully consumed.
 */
public final class StubServer implements AutoCloseable {
  /** One scripted response. */
  public static final class Stub {
    final int status;
    final byte[] body;
    final Map<String, String> headers;

    Stub(int status, byte[] body, Map<String, String> headers) {
      this.status = status;
      this.body = body;
      this.headers = headers;
    }

    public static Stub json(Object payload) {
      return json(payload, 200);
    }

    public static Stub json(Object payload, int status) {
      byte[] body = Json.write(payload).getBytes(StandardCharsets.UTF_8);
      return new Stub(status, body, Map.of("Content-Type", "application/json"));
    }

    public static Stub text(String text, int status) {
      byte[] body = text.getBytes(StandardCharsets.UTF_8);
      return new Stub(status, body, Map.of("Content-Type", "text/plain"));
    }

    public static Stub of(int status, byte[] body, Map<String, String> headers) {
      return new Stub(status, body, headers);
    }
  }

  /** One captured request. */
  public static final class Captured {
    public final String method;
    public final String path;
    public final String rawQuery;
    public final Map<String, List<String>> headers;
    public final byte[] body;

    Captured(
        String method,
        String path,
        String rawQuery,
        Map<String, List<String>> headers,
        byte[] body) {
      this.method = method;
      this.path = path;
      this.rawQuery = rawQuery == null ? "" : rawQuery;
      this.headers = headers;
      this.body = body;
    }

    /** First request header value, looked up case-insensitively. */
    public String header(String name) {
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        String key = entry.getKey();
        if (key == null || !key.equalsIgnoreCase(name) || entry.getValue().isEmpty()) {
          continue;
        }
        return entry.getValue().get(0);
      }
      return "";
    }

    public String bodyText() {
      return new String(body, StandardCharsets.UTF_8);
    }

    public Object bodyJson() {
      return Json.parse(bodyText());
    }
  }

  private final HttpServer server;
  private final Queue<Stub> script = new ArrayDeque<>();
  private final List<Captured> requests = Collections.synchronizedList(new ArrayList<>());
  private volatile AssertionError failure;

  public StubServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.setExecutor(
        Executors.newCachedThreadPool(
            r -> {
              Thread thread = new Thread(r);
              thread.setDaemon(true);
              return thread;
            }));
    server.start();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void script(Stub... stubs) {
    script.addAll(Arrays.asList(stubs));
  }

  public List<Captured> requests() {
    synchronized (requests) {
      return List.copyOf(requests);
    }
  }

  public void assertDone() {
    if (failure != null) {
      throw failure;
    }
    assertTrue(script.isEmpty(), "script has " + script.size() + " unconsumed responses");
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    requests.add(
        new Captured(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getPath(),
            exchange.getRequestURI().getRawQuery(),
            exchange.getRequestHeaders(),
            requestBody));
    Stub stub = script.poll();
    if (stub == null) {
      if (failure == null) {
        failure = new AssertionError("unexpected extra HTTP request");
      }
      byte[] message = "unexpected extra HTTP request".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(500, message.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(message);
      }
      return;
    }
    stub.headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
    if (stub.body.length == 0) {
      exchange.sendResponseHeaders(stub.status, -1);
      exchange.getResponseBody().close();
      return;
    }
    exchange.sendResponseHeaders(stub.status, stub.body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(stub.body);
    }
  }
}
