package dev.vspex.wiki.mark.importer;

/** An import could not be completed. Messages are safe to display to the caller. */
public class ImportException extends RuntimeException {
  public ImportException(String message) {
    super(message);
  }
}
