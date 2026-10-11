package com.wiki.mark.client;

/**
 * A Confluence API call failed. Unchecked so sync code is not buried in catch blocks.
 *
 * <p>Port of {@code mark.confluence.ConfluenceError}. HTTP failures render as {@code {what}: HTTP
 * {status}: {detail}} where the detail prefers the response JSON {@code message} field and is
 * capped at 500 chars; the raw body snippet is capped at 2000 chars. Credentials never appear in
 * messages: the token travels in a header the client never logs.
 */
public class ConfluenceException extends RuntimeException {
  private final Integer status;
  private final String url;
  private final String body;

  public ConfluenceException(String message) {
    this(message, null, "", "");
  }

  public ConfluenceException(String message, Integer status, String url, String body) {
    super(message);
    this.status = status;
    this.url = url == null ? "" : url;
    this.body = body == null ? "" : body;
  }

  /** Builds the standard {@code {what}: HTTP {status}: {detail}} failure. */
  static ConfluenceException httpError(
      String what, int status, String url, String detail, String body) {
    return new ConfluenceException(
        what + ": HTTP " + status + ": " + truncate(detail, 500),
        status,
        url,
        truncate(body, 2000));
  }

  /** HTTP status of the failed call, or null when no response was received. */
  public Integer status() {
    return status;
  }

  /** Full request URL of the failed call, or "" when unknown. */
  public String url() {
    return url;
  }

  /** Response body snippet (up to 2000 chars), or "" when there was none. */
  public String body() {
    return body;
  }

  private static String truncate(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max);
  }
}
