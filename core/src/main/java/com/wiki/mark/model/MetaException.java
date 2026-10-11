package com.wiki.mark.model;

/**
 * A document's metadata is malformed (typo header, bad value, ...).
 *
 * <p>Port of {@code mark.metadata.MetaError}. Unchecked so parser signatures stay clean.
 */
public class MetaException extends RuntimeException {
  public MetaException(String message) {
    super(message);
  }

  public MetaException(String message, Throwable cause) {
    super(message, cause);
  }
}
