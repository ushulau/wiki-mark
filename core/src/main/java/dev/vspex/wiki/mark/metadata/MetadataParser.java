package dev.vspex.wiki.mark.metadata;

import dev.vspex.wiki.mark.model.Meta;
import dev.vspex.wiki.mark.model.MetaException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Top-level document parsing: front matter + leading header run â†’ {@link Meta}.
 *
 * <p>Direct port of {@code mark.metadata.parse_document} and {@code warn_stranded_headers}.
 */
public final class MetadataParser {
  private MetadataParser() {}

  /** Options for {@link #parseDocument}; mirrors {@code parse_document} keyword arguments. */
  public static class ParseOptions {
    /** Creates options with defaults. */
    public ParseOptions() {}

    /** File name for messages and filename titles. */
    public String filename = "";

    /** Take the page title from the leading H1. */
    public boolean titleFromH1 = false;

    /** Take the page title from the file name. */
    public boolean titleFromFilename = false;

    /** Default space key when the document has none. */
    public String space = "";

    /** Parent pages prepended to the document's own parents. */
    public List<String> parents = new ArrayList<>();

    /** Default content appearance when the document has none. */
    public String contentAppearance = "";

    /** Append a short parents/space/title hash to the title. */
    public boolean titleAppendHash = false;

    /** Whether to read YAML front matter. */
    public boolean frontMatter = true;
  }

  /**
   * Result of {@link #parseDocument}: metadata, body, warnings.
   *
   * @param meta the parsed metadata
   * @param body the body with front matter and header run removed
   * @param warnings warnings for ignored keys and stranded headers
   */
  public record ParseResult(Meta meta, String body, List<String> warnings) {}

  /**
   * Extract metadata from a Markdown document.
   *
   * <p>Returns the meta (possibly empty â€” callers must still require a title and space), the body
   * with front matter and header run removed, and warnings.
   *
   * @param text the Markdown document
   * @param opts parse options
   * @return the parse result
   */
  public static ParseResult parseDocument(String text, ParseOptions opts) {
    List<String> warnings = new ArrayList<>();
    Meta meta = new Meta();
    boolean foundMetadata = false;
    String body = text;

    if (opts.frontMatter) {
      FrontMatterParser.FrontMatter fm = FrontMatterParser.splitFrontMatter(text);
      if (fm.yamlOrNull() != null) {
        final Map<String, Object> parsed;
        try {
          parsed = MiniYaml.parseSimpleYaml(fm.yamlOrNull());
        } catch (MetaException e) {
          throw new MetaException("decode YAML front matter: " + e.getMessage(), e);
        }
        warnings.addAll(FrontMatterParser.applyFrontMatter(meta, parsed, opts.filename));
        body = fm.body();
        foundMetadata = true;
      }
    }

    List<String> lines = TextLines.splitLinesKeepends(body);
    Set<Integer> consumed = new HashSet<>();
    boolean started = false;
    for (int idx = 0; idx < lines.size(); idx++) {
      String line = stripLineEnd(lines.get(idx));
      if (line.strip().isEmpty()) {
        continue;
      }
      HeaderParser.HeaderComment parsed = HeaderParser.parseHeaderComment(line);
      if (parsed == null) {
        // Not a comment line at all: a macro or raw HTML above the headers is skipped over,
        // anything once started ends the run.
        if (started) {
          break;
        }
        if (line.stripLeading().startsWith("<")) {
          continue;
        }
        break;
      }
      String key = parsed.key();
      String value = parsed.value();
      String header = HeaderParser.canonicalHeader(key);
      if (header == null) {
        String near = HeaderParser.nearestHeader(key);
        if (near != null) {
          throw new MetaException(
              "unknown header "
                  + FrontMatterParser.pyReprString(key)
                  + " in "
                  + FrontMatterParser.pyReprString(line.strip())
                  + " -- did you mean "
                  + FrontMatterParser.pyReprString(near)
                  + "?");
        }
        // Ordinary comment: kept in the body either way.
        continue;
      }
      if (header.equals(HeaderParser.HEADER_INCLUDE)) {
        // Includes are expanded from the body by a later pass.
        started = true;
        continue;
      }
      consumed.add(idx);
      started = true;
      foundMetadata = true;
      switch (header) {
        case HeaderParser.HEADER_PARENT -> {
          meta.parents.add(value);
          meta.declaredParents = true;
        }
        case HeaderParser.HEADER_FOLDER -> meta.folders.add(value);
        case HeaderParser.HEADER_SPACE -> meta.space = value.strip();
        case HeaderParser.HEADER_TYPE ->
            meta.type = value.strip().isEmpty() ? "page" : value.strip();
        case HeaderParser.HEADER_TITLE -> meta.title = value.strip();
        case HeaderParser.HEADER_LAYOUT -> meta.layout = value.strip();
        case HeaderParser.HEADER_SIDEBAR -> {
          meta.layout = "article";
          meta.sidebar = value.strip();
        }
        case HeaderParser.HEADER_EMOJI -> meta.emoji = value.strip();
        case HeaderParser.HEADER_ORDER -> {
          try {
            meta.order = Integer.parseInt(value.strip());
          } catch (NumberFormatException e) {
            throw new MetaException(
                "Order header must be a whole number, got "
                    + FrontMatterParser.pyReprString(value));
          }
        }
        case HeaderParser.HEADER_ATTACHMENT -> meta.attachments.add(value);
        case HeaderParser.HEADER_LABEL -> meta.labels.add(value);
        case HeaderParser.HEADER_CONTENT_APPEARANCE ->
            HeaderParser.setContentAppearance(meta, value);
        case HeaderParser.HEADER_IMAGE_ALIGN ->
            meta.imageAlign = value.strip().toLowerCase(Locale.ROOT);
        case HeaderParser.HEADER_IMAGE_WIDTH -> meta.imageWidth = value.strip();
        case HeaderParser.HEADER_IMAGE_HEIGHT -> meta.imageHeight = value.strip();
        case HeaderParser.HEADER_SYNCHRONIZED -> {
          String lowered = value.strip().toLowerCase(Locale.ROOT);
          if (lowered.equals("true")
              || lowered.equals("yes")
              || lowered.equals("on")
              || lowered.equals("1")) {
            meta.synchronizedFlag = true;
          } else if (lowered.equals("false")
              || lowered.equals("no")
              || lowered.equals("off")
              || lowered.equals("0")) {
            meta.synchronizedFlag = false;
          } else {
            throw new MetaException(
                "Synchronized header must be true or false, got "
                    + FrontMatterParser.pyReprString(value));
          }
        }
        case HeaderParser.HEADER_PROPERTY -> {
          int sep = value.indexOf('=');
          if (sep < 0) {
            throw new MetaException(
                "Property header must be written as key=value, got "
                    + FrontMatterParser.pyReprString(value));
          }
          String propKey = value.substring(0, sep).strip();
          if (propKey.isEmpty()) {
            throw new MetaException(
                "Property header has no name: " + FrontMatterParser.pyReprString(value));
          }
          meta.properties.put(propKey, value.substring(sep + 1).strip());
        }
        default ->
            throw new MetaException(
                "header " + FrontMatterParser.pyReprString(header) + " is known but not handled");
      }
    }

    if (!consumed.isEmpty()) {
      int first = Integer.MAX_VALUE;
      int last = Integer.MIN_VALUE;
      for (int c : consumed) {
        first = Math.min(first, c);
        last = Math.max(last, c);
      }
      StringBuilder kept = new StringBuilder();
      for (int idx = 0; idx < lines.size(); idx++) {
        if (consumed.contains(idx)) {
          continue;
        }
        if (first < idx && idx < last && lines.get(idx).strip().isEmpty()) {
          continue;
        }
        kept.append(lines.get(idx));
      }
      body = kept.toString();
    }

    if (foundMetadata) {
      warnings.addAll(warnStrandedHeaders(body));
    }

    if (opts.titleFromH1 && meta.title.isEmpty()) {
      meta.title = TitleResolver.extractLeadingH1(body);
    }
    if (opts.titleFromFilename && meta.title.isEmpty() && !opts.filename.isEmpty()) {
      meta.title = TitleResolver.titleFromName(stemOf(opts.filename));
    }
    if (!opts.space.isEmpty() && meta.space.isEmpty()) {
      meta.space = opts.space;
    }
    if (!opts.contentAppearance.isEmpty() && meta.contentAppearance.isEmpty()) {
      HeaderParser.setContentAppearance(meta, opts.contentAppearance);
    }
    if (meta.contentAppearance.isEmpty()) {
      meta.contentAppearance = HeaderParser.FULL_WIDTH_CONTENT_APPEARANCE;
    }
    if (meta.type.isEmpty()) {
      meta.type = "page";
    }

    List<String> cliParents = new ArrayList<>();
    for (String p : opts.parents) {
      if (!p.equals("")) {
        cliParents.add(p);
      }
    }
    if (!cliParents.isEmpty()) {
      List<String> merged = new ArrayList<>(cliParents);
      merged.addAll(meta.parents);
      meta.parents = merged;
    }

    if (opts.titleAppendHash && !meta.title.isEmpty()) {
      TitleResolver.appendGeneratedHash(meta);
    }

    meta.title = stripSpaces(meta.title);
    meta.space = stripSpaces(meta.space);
    return new ParseResult(meta, body, warnings);
  }

  /**
   * Headers left in the body (below other content) that will not apply.
   *
   * @param body the document body
   * @return one warning per stranded header
   */
  public static List<String> warnStrandedHeaders(String body) {
    List<String> warnings = new ArrayList<>();
    List<String> lines = TextLines.splitLines(body);
    Set<Integer> hidden = TitleResolver.codeFenceSpans(lines);
    for (int idx = 0; idx < lines.size(); idx++) {
      if (hidden.contains(idx)) {
        continue;
      }
      HeaderParser.HeaderComment parsed = HeaderParser.parseHeaderComment(lines.get(idx));
      if (parsed != null && HeaderParser.canonicalHeader(parsed.key()) != null) {
        warnings.add(
            "line "
                + (idx + 1)
                + ": header "
                + FrontMatterParser.pyReprString(parsed.key())
                + " is below page content and was ignored (headers must lead the document)");
      }
    }
    return warnings;
  }

  private static String stripLineEnd(String raw) {
    int end = raw.length();
    while (end > 0 && (raw.charAt(end - 1) == '\r' || raw.charAt(end - 1) == '\n')) {
      end--;
    }
    return raw.substring(0, end);
  }

  /** Python {@code str.strip(" ")}: strips ASCII spaces only (not tabs). */
  private static String stripSpaces(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && s.charAt(start) == ' ') {
      start++;
    }
    while (end > start && s.charAt(end - 1) == ' ') {
      end--;
    }
    return s.substring(start, end);
  }

  /** Filename stem like {@code os.path.splitext(os.path.basename(filename))[0]}. */
  private static String stemOf(String filename) {
    String base = filename.replace('\\', '/');
    int slash = base.lastIndexOf('/');
    if (slash >= 0) {
      base = base.substring(slash + 1);
    }
    int dot = base.lastIndexOf('.');
    if (dot > 0) {
      return base.substring(0, dot);
    }
    return base;
  }
}
