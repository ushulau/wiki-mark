package dev.vspex.wiki.mark.client;

/**
 * A Confluence API call failed. Unchecked so sync code is not buried in catch blocks.
 *
 * <p>Port of {@code mark.confluence.ConfluenceError}. HTTP failures render as {@code {what}: HTTP
 * {status}: {detail}} where the detail prefers the response JSON {@code message} field and is
 * capped at 500 chars; the raw body snippet is capped at 2000 chars. Credentials never appear in
 * messages: the token travels in a header the client never logs.
 */
public class ConfluenceException extends RuntimeException {
  /** HTTP status of the failed call, or null when no response was received. */
  private final Integer status;

  /** Full request URL of the failed call, or "" when unknown. */
  private final String url;

  /** Response body snippet (up to 2000 chars), or "" when there was none. */
  private final String body;

  /**
   * Creates a failure without response details.
   *
   * @param message the failure message
   */
  public ConfluenceException(String message) {
    this(message, null, "", "");
  }

  /**
   * Creates a failure with response details.
   *
   * @param message the failure message
   * @param status HTTP status of the failed call, or null when no response was received
   * @param url full request URL of the failed call, or null when unknown
   * @param body response body snippet, or null when there was none
   */
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

  /**
   * HTTP status of the failed call, or null when no response was received.
   *
   * @return the HTTP status
   */
  public Integer status() {
    return status;
  }

  /**
   * Full request URL of the failed call, or "" when unknown.
   *
   * @return the request URL
   */
  public String url() {
    return url;
  }

  /**
   * Response body snippet (up to 2000 chars), or "" when there was none.
   *
   * @return the body snippet
   */
  public String body() {
    return body;
  }

  private static String truncate(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max);
  }
}
