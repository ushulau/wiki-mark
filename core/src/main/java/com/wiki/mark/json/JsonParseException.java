package com.wiki.mark.json;

/**
 * A JSON document is malformed. Unchecked: callers treat a bad payload as a failed request, not a
 * declared outcome.
 *
 * <p>Messages never echo the credential-bearing parts of a request; response bodies never contain
 * credentials, and only tiny fragments are quoted for debuggability.
 */
public class JsonParseException extends RuntimeException {
  /**
   * Creates a failure.
   *
   * @param message the failure message
   */
  public JsonParseException(String message) {
    super(message);
  }
}
