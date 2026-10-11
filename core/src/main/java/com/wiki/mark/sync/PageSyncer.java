package com.wiki.mark.sync;

import com.wiki.mark.client.ConfluenceException;
import com.wiki.mark.client.EnsureResult;
import com.wiki.mark.client.PageStore;
import com.wiki.mark.convert.MarkdownConverter;
import com.wiki.mark.metadata.MetadataParser;
import com.wiki.mark.metadata.TitleResolver;
import com.wiki.mark.model.Meta;
import com.wiki.mark.model.MetaException;
import com.wiki.mark.model.RenderResult;
import com.wiki.mark.model.SyncAction;
import com.wiki.mark.model.SyncResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sync orchestration: one file at a time over a {@link PageStore}, plus batch runs.
 *
 * <p>Direct port of {@code mark.cli.sync_file} and {@code run_sync} minus the dropped options: no
 * {@code target-url} mode, no glob selection (the caller passes files), no output formats (the
 * report is a plain {@link RunReport}). Step order below mirrors {@code sync_file} exactly: the
 * order carries quirks (layout skipped in dry-run output, title/space required only after the
 * compile-only branch).
 */
public final class PageSyncer {
  private PageSyncer() {}

  /** Directory parents of a file measured from a root, plus whether it is an index page. */
  public record PathParents(List<String> parents, boolean index) {}

  private static final Set<String> INDEX_STEMS = Set.of("index", "readme");
  private static final Pattern INCLUDE_RE = Pattern.compile("<!--\\s*Include:\\s*(.*?)\\s*-->");

  /**
   * Directory parents for {@code file} measured from {@code root}.
   *
   * <p>Port of {@code parents_from_path}: path segments Title-Cased, an {@code index.md} or {@code
   * README.md} standing for its own directory (last segment dropped), nothing when the file sits
   * outside the root. An empty root falls back to the last two directories.
   */
  public static PathParents parentsFromPath(Path file, String root) {
    Path directory = file.toAbsolutePath().normalize().getParent();
    List<String> parts = new ArrayList<>();
    if (root != null && !root.isEmpty()) {
      Path absRoot = Path.of(root).toAbsolutePath().normalize();
      try {
        String text = absRoot.relativize(directory).toString();
        if (!text.isEmpty() && !text.equals(".") && !text.startsWith("..")) {
          for (Path part : absRoot.relativize(directory)) {
            String name = part.toString();
            if (!name.isEmpty() && !name.equals(".")) {
              parts.add(name);
            }
          }
        }
      } catch (IllegalArgumentException outside) {
        // Different roots (Windows drives): no parents, like Python's ValueError path.
      }
    } else {
      for (Path part : directory) {
        parts.add(part.toString());
      }
      if (parts.size() > 2) {
        parts = new ArrayList<>(parts.subList(parts.size() - 2, parts.size()));
      }
    }
    String filename = file.getFileName() == null ? "" : file.getFileName().toString();
    int dot = filename.lastIndexOf('.');
    String stem = (dot >= 0 ? filename.substring(0, dot) : filename).toLowerCase(Locale.ROOT);
    boolean index = INDEX_STEMS.contains(stem);
    if (index && !parts.isEmpty()) {
      parts = new ArrayList<>(parts.subList(0, parts.size() - 1));
    }
    List<String> parents = new ArrayList<>(parts.size());
    for (String part : parts) {
      parents.add(TitleResolver.titleFromName(part));
    }
    return new PathParents(parents, index);
  }

  /** Effective image alignment (per-file header wins); anything but left/center/right errors. */
  public static String resolveImageAlign(String configAlign, String metaAlign) {
    String align = firstNonEmpty(metaAlign, configAlign).strip().toLowerCase(Locale.ROOT);
    if (!align.isEmpty()
        && !align.equals("left")
        && !align.equals("center")
        && !align.equals("right")) {
      throw new MetaException(
          "unknown image-align '" + align + "', expected one of: left, center, right");
    }
    return align;
  }

  /** Effective image dimension (per-file header wins); must be a positive integer when set. */
  public static String resolveImageDimension(String name, String configValue, String metaValue) {
    String value = firstNonEmpty(metaValue, configValue).strip();
    if (!value.isEmpty() && !isPositiveInteger(value)) {
      throw new MetaException(
          "invalid " + name + " '" + value + "', expected a positive number of pixels");
    }
    return value;
  }

  private static String firstNonEmpty(String first, String second) {
    if (first != null && !first.isEmpty()) {
      return first;
    }
    if (second != null && !second.isEmpty()) {
      return second;
    }
    return "";
  }

  private static boolean isPositiveInteger(String value) {
    if (!value.matches("[0-9]+")) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) != '0') {
        return true;
      }
    }
    return false;
  }

  /** Targets of the {@code <!-- Include: ... -->} markers in a body (unexpanded, warned about). */
  public static List<String> findIncludes(String body) {
    List<String> includes = new ArrayList<>();
    Matcher matcher = INCLUDE_RE.matcher(body);
    while (matcher.find()) {
      String target = matcher.group(1).strip();
      if (!target.isEmpty()) {
        includes.add(target);
      }
    }
    return includes;
  }

  /** Warns, per file, about headers the publisher reads but never applies. */
  public static void warnUnsupportedHeaders(String path, Meta meta, String body, Log log) {
    if (meta.folders != null && !meta.folders.isEmpty()) {
      log.warn(path + ": Folder headers are not supported and were ignored");
    }
    if (meta.properties != null && !meta.properties.isEmpty()) {
      log.warn(path + ": Property/properties are not applied (content properties unsupported)");
    }
    if (meta.order != null) {
      log.warn(path + ": Order header is recorded but sibling ordering is not applied");
    }
    for (String include : findIncludes(body)) {
      log.warn(path + ": Include '" + include + "' is not expanded (includes unsupported)");
    }
  }

  /**
   * Maps each file's normalized absolute path to its resolved page title, for cross-file links.
   *
   * <p>Port of {@code build_page_title_map}: unreadable or untitled files are silently skipped
   * (links to them fall back to a plain {@code <a>}).
   */
  public static Map<String, String> buildPageTitleMap(List<Path> files, SyncOptions opts) {
    Map<String, String> titles = new LinkedHashMap<>();
    for (Path file : files) {
      final Meta meta;
      try {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        meta = MetadataParser.parseDocument(text, parseOptions(file, opts)).meta();
      } catch (IOException | MetaException unreadable) {
        continue;
      }
      if (!meta.title.isEmpty()) {
        titles.put(file.toAbsolutePath().normalize().toString(), meta.title);
      }
    }
    return titles;
  }

  private static MetadataParser.ParseOptions parseOptions(Path file, SyncOptions opts) {
    MetadataParser.ParseOptions parse = new MetadataParser.ParseOptions();
    parse.filename = file.toString();
    parse.titleFromH1 = opts.titleFromH1;
    parse.titleFromFilename = opts.titleFromFilename;
    parse.space = opts.space;
    parse.parents = new ArrayList<>(opts.parents);
    parse.contentAppearance = opts.contentAppearance;
    parse.titleAppendHash = opts.titleAppendHash;
    return parse;
  }

  /** Public page URL from the page links (base falls back to the client's base URL). */
  public static String pageUrl(String baseUrl, Map<String, Object> page) {
    String base = "";
    String webui = "";
    if (page.get("_links") instanceof Map<?, ?> links) {
      if (links.get("base") instanceof String text) {
        base = text;
      }
      if (links.get("webui") instanceof String text) {
        webui = text;
      }
    }
    if (base.isEmpty()) {
      base = baseUrl;
    }
    if (webui.isEmpty()) {
      return base;
    }
    int end = base.length();
    while (end > 0 && base.charAt(end - 1) == '/') {
      end--;
    }
    return base.substring(0, end) + webui;
  }

  /**
   * All existing paths matching {@code pattern} under {@code sourceDir} (files and directories),
   * sorted by path. An absolute pattern ignores the source dir, like {@code os.path.join}.
   *
   * <p>Python-{@code glob} semantics, hand-matched so behavior is identical on every platform: a
   * pattern naming a directory matches it without error; {@code **} crosses directories; matching
   * is case-insensitive on Windows; a literal name containing glob metacharacters (which classes
   * never match) falls back to the file itself.
   */
  public static List<Path> matchGlob(Path sourceDir, String pattern) throws IOException {
    String text = WINDOWS ? pattern.replace('\\', '/') : pattern;
    Path anchor = sourceDir;
    String rest = text;
    if (!WINDOWS && text.startsWith("/")) {
      anchor = Path.of("/");
      rest = text.substring(1);
    } else if (WINDOWS && text.length() >= 3 && isDriveRooted(text)) {
      anchor = Path.of(text.substring(0, 2) + "\\");
      rest = text.substring(3);
    } else if (WINDOWS && text.startsWith("//")) {
      List<String> unc = new ArrayList<>(List.of(text.substring(2).split("/", 4)));
      if (unc.size() < 2 || unc.get(0).isEmpty() || unc.get(1).isEmpty()) {
        return List.of();
      }
      try {
        anchor = Path.of("\\\\" + unc.get(0) + "\\" + unc.get(1));
      } catch (InvalidPathException badShare) {
        return List.of();
      }
      rest = unc.size() > 2 ? String.join("/", unc.subList(2, unc.size())) : "";
    } else if (WINDOWS && text.startsWith("/")) {
      anchor = Path.of("").toAbsolutePath().getRoot();
      rest = text.substring(1);
    }
    List<String> segments = new ArrayList<>();
    if (!rest.isEmpty()) {
      segments.addAll(List.of(rest.split("/", -1)));
    }
    List<Path> hits = new ArrayList<>();
    walkGlob(anchor, segments, 0, hits);
    hits.sort((left, right) -> left.toString().compareTo(right.toString()));
    if (hits.isEmpty()) {
      try {
        Path literal = anchor;
        for (String segment : segments) {
          literal = literal.resolve(segment);
        }
        if (Files.isRegularFile(literal)) {
          hits.add(literal);
        }
      } catch (InvalidPathException unresolvable) {
        // A pattern no path can spell matches nothing.
      }
    }
    return hits;
  }

  private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

  private static boolean isDriveRooted(String text) {
    char drive = text.charAt(0);
    boolean letter = (drive >= 'A' && drive <= 'Z') || (drive >= 'a' && drive <= 'z');
    return letter && text.charAt(1) == ':' && text.charAt(2) == '/';
  }

  private static void walkGlob(Path dir, List<String> segments, int index, List<Path> hits)
      throws IOException {
    if (index == segments.size()) {
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        hits.add(dir);
      }
      return;
    }
    String segment = segments.get(index);
    if (segment.equals("**")) {
      walkGlob(dir, segments, index + 1, hits);
      if (Files.isDirectory(dir)) {
        for (Path kid : listQuietly(dir)) {
          if (Files.isDirectory(kid)) {
            walkGlob(kid, segments, index, hits);
          }
        }
      }
      return;
    }
    if (hasMeta(segment)) {
      if (!Files.isDirectory(dir)) {
        return;
      }
      for (Path kid : listQuietly(dir)) {
        Path name = kid.getFileName();
        if (name != null && segmentMatches(segment, name.toString())) {
          walkGlob(kid, segments, index + 1, hits);
        }
      }
      return;
    }
    final Path next;
    try {
      next = dir.resolve(segment);
    } catch (InvalidPathException badName) {
      return;
    }
    walkGlob(next, segments, index + 1, hits);
  }

  /** Directory entries, silently empty when the directory cannot be listed (like glob). */
  private static List<Path> listQuietly(Path dir) {
    try (Stream<Path> kids = Files.list(dir)) {
      return kids.toList();
    } catch (IOException unreadable) {
      return List.of();
    }
  }

  private static boolean hasMeta(String segment) {
    return segment.indexOf('*') >= 0 || segment.indexOf('?') >= 0 || segment.indexOf('[') >= 0;
  }

  /**
   * Matches one path segment against a glob segment: {@code *}, {@code ?} and {@code [...]} classes
   * (with {@code [!...]} negation, {@code ]}-first literal, unterminated-{@code [} literal);
   * everything else (including braces) is literal. Case-insensitive on Windows, like {@code
   * fnmatch.fnmatch}.
   */
  static boolean segmentMatches(String pattern, String name) {
    if (WINDOWS) {
      pattern = pattern.toLowerCase(Locale.ROOT);
      name = name.toLowerCase(Locale.ROOT);
    }
    return matchHere(pattern, 0, name, 0);
  }

  private static boolean matchHere(String pattern, int pi, String name, int ni) {
    while (pi < pattern.length()) {
      char c = pattern.charAt(pi);
      if (c == '*') {
        while (pi < pattern.length() && pattern.charAt(pi) == '*') {
          pi++;
        }
        if (pi == pattern.length()) {
          return true;
        }
        for (int k = ni; k <= name.length(); k++) {
          if (matchHere(pattern, pi, name, k)) {
            return true;
          }
        }
        return false;
      }
      if (ni >= name.length()) {
        return false;
      }
      if (c == '?') {
        pi++;
        ni++;
        continue;
      }
      if (c == '[') {
        int close = classEnd(pattern, pi);
        if (close < 0) {
          if (name.charAt(ni) != '[') {
            return false;
          }
          pi++;
          ni++;
          continue;
        }
        if (!classMatches(pattern, pi, close, name.charAt(ni))) {
          return false;
        }
        pi = close + 1;
        ni++;
        continue;
      }
      if (c != name.charAt(ni)) {
        return false;
      }
      pi++;
      ni++;
    }
    return ni == name.length();
  }

  /** Index of the closing bracket of the class opened at {@code pi}, or -1 when unterminated. */
  private static int classEnd(String pattern, int pi) {
    int j = pi + 1;
    if (j < pattern.length() && pattern.charAt(j) == '!') {
      j++;
    }
    if (j < pattern.length() && pattern.charAt(j) == ']') {
      j++;
    }
    while (j < pattern.length() && pattern.charAt(j) != ']') {
      j++;
    }
    return j < pattern.length() ? j : -1;
  }

  private static boolean classMatches(String pattern, int pi, int close, char c) {
    int k = pi + 1;
    boolean negated = k < close && pattern.charAt(k) == '!';
    if (negated) {
      k++;
    }
    boolean matched = false;
    while (k < close) {
      if (k + 2 < close
          && pattern.charAt(k + 1) == '-'
          && pattern.charAt(k) <= pattern.charAt(k + 2)) {
        if (pattern.charAt(k) <= c && c <= pattern.charAt(k + 2)) {
          matched = true;
        }
        k += 3;
      } else {
        if (pattern.charAt(k) == c) {
          matched = true;
        }
        k++;
      }
    }
    return negated != matched;
  }

  /**
   * Header attachment patterns resolved against the page directory, deduplicated. Every pattern
   * must match at least one file; a missing one fails the file.
   */
  public static List<Path> attachmentPaths(List<String> patterns, Path sourceDir)
      throws IOException {
    Set<Path> paths = new LinkedHashSet<>();
    for (String pattern : patterns) {
      List<Path> hits = matchGlob(sourceDir, pattern);
      if (hits.isEmpty()) {
        throw new SyncException("attachment '" + pattern + "' does not exist");
      }
      for (Path hit : hits) {
        if (Files.isRegularFile(hit)) {
          paths.add(hit);
        }
      }
    }
    return new ArrayList<>(paths);
  }

  /**
   * Syncs one file; returns the action taken. Raises on failure.
   *
   * <p>A null client (or {@code compileOnly}) takes the compile branch: no network, no token.
   * Target-URL mode does not exist in v0.1, so there is no page-id parameter.
   */
  public static SyncResult syncFile(
      Path file, SyncOptions opts, PageStore client, Map<String, String> pageTitles, Log log)
      throws IOException {
    String path = file.toString();
    String text = Files.readString(file, StandardCharsets.UTF_8);
    Path sourceDir = file.toAbsolutePath().getParent();
    if (sourceDir == null) {
      sourceDir = Path.of(".");
    }

    MetadataParser.ParseResult parsed =
        MetadataParser.parseDocument(text, parseOptions(file, opts));
    Meta meta = parsed.meta();
    String body = parsed.body();
    for (String warning : parsed.warnings()) {
      log.warn(path + ": " + warning);
    }

    if (opts.parentsFromPath && !meta.declaredParents) {
      PathParents extra = parentsFromPath(file, opts.parentsFromPathRoot);
      if (!extra.parents().isEmpty()) {
        List<String> docParents =
            opts.parents.isEmpty()
                ? new ArrayList<>(meta.parents)
                : new ArrayList<>(
                    meta.parents.subList(
                        Math.min(opts.parents.size(), meta.parents.size()), meta.parents.size()));
        List<String> merged = new ArrayList<>(opts.parents);
        merged.addAll(extra.parents());
        merged.addAll(docParents);
        meta.parents = merged;
        log.debug(path + ": parents from path: " + quoted(extra.parents()));
      }
    }

    if (Boolean.FALSE.equals(meta.synchronizedFlag)) {
      log.info("skipped " + path + ": Synchronized is false");
      return new SyncResult(SyncAction.SKIPPED, path, "", "", "", "", "Synchronized is false");
    }

    String imageAlign = resolveImageAlign(opts.imageAlign, meta.imageAlign);
    String imageWidth = resolveImageDimension("image-width", opts.imageWidth, meta.imageWidth);
    String imageHeight = resolveImageDimension("image-height", opts.imageHeight, meta.imageHeight);
    MarkdownConverter.RenderOptions renderOpts = new MarkdownConverter.RenderOptions();
    renderOpts.dropH1 = opts.dropH1;
    renderOpts.stripLinebreaks = opts.stripLinebreaks;
    renderOpts.imageAlign = imageAlign;
    renderOpts.imageMaxWidth = imageWidth;
    renderOpts.imageMaxHeight = imageHeight;
    renderOpts.imageBaseDir = sourceDir.toString();
    renderOpts.pageLinks = pageTitles;
    renderOpts.attachReferenced = opts.attachReferenced;
    RenderResult result = MarkdownConverter.render(body, renderOpts);
    warnUnsupportedHeaders(path, meta, body, log);

    if (opts.compileOnly || client == null) {
      String title = meta.title.isEmpty() ? "(no title)" : meta.title;
      log.info("--- " + path + " -> " + meta.space + " / " + title + " ---");
      log.info(result.storage().isEmpty() ? "(empty page)" : result.storage());
      return new SyncResult(SyncAction.COMPILED, path, meta.space, meta.title, "", "", "");
    }

    if (meta.title.isEmpty()) {
      throw new MetaException(
          path
              + ": page title is not set (add a Title header,"
              + " --title-from-h1 or --title-from-filename)");
    }
    if (meta.space.isEmpty()) {
      throw new MetaException(path + ": space is not set (add a Space header or --space)");
    }

    List<Path> attachments = attachmentPaths(meta.attachments, sourceDir);
    for (String ref : result.attachments()) {
      Path refPath = sourceDir.resolve(ref).normalize();
      if (Files.isRegularFile(refPath)) {
        if (!attachments.contains(refPath)) {
          attachments.add(refPath);
        }
      } else {
        log.warn(path + ": referenced file '" + ref + "' does not exist; skipped");
      }
    }

    if (opts.dryRun) {
      ParentChain.ensureParentChain(client, meta.space, meta.type, meta.parents, true, log);
      Map<String, Object> page = client.findPage(meta.space, meta.title, meta.type);
      String where =
          meta.parents.isEmpty()
              ? ""
              : "under '" + meta.parents.get(meta.parents.size() - 1) + "' ";
      log.info(
          "dry-run "
              + path
              + ": would publish '"
              + meta.title
              + "' ("
              + (page == null ? "new" : String.valueOf(page.get("id")))
              + ") "
              + where
              + "in space "
              + meta.space);
      log.info(result.storage().isEmpty() ? "(empty page)" : result.storage());
      return new SyncResult(SyncAction.DRY_RUN, path, meta.space, meta.title, "", "", "");
    }

    String parentId =
        ParentChain.ensureParentChain(client, meta.space, meta.type, meta.parents, false, log);
    Map<String, Object> page = client.findPage(meta.space, meta.title, meta.type);

    String storage = Layout.applyLayout(result.storage(), meta.layout, meta.sidebar);
    String digest = opts.changesOnly ? ContentHash.contentHash(storage) : "";

    SyncAction action;
    if (page == null) {
      page = client.createPage(meta.space, meta.title, storage, meta.type, parentId);
      action = SyncAction.CREATED;
    } else {
      Map<String, Object> current = client.getPageById(String.valueOf(page.get("id")));
      if (opts.changesOnly) {
        String previous = ContentHash.readContentHash(versionMessage(current));
        if (previous.equals(digest)) {
          action = SyncAction.UNCHANGED;
        } else {
          client.updatePage(
              page,
              storage,
              opts.minorEdit,
              ContentHash.formatVersionMessage(opts.versionMessage, digest),
              appearanceOrDefault(meta),
              meta.emoji);
          action = SyncAction.UPDATED;
        }
      } else if (storage.equals(currentStorageValue(current))) {
        action = SyncAction.UNCHANGED;
      } else {
        client.updatePage(
            page,
            storage,
            opts.minorEdit,
            opts.versionMessage,
            appearanceOrDefault(meta),
            meta.emoji);
        action = SyncAction.UPDATED;
      }
    }

    String pageId = String.valueOf(page.get("id"));
    for (Path localPath : attachments) {
      EnsureResult ensured = client.ensureAttachment(pageId, localPath, "");
      if (ensured.changed()) {
        log.info("  attachment " + localPath.getFileName() + " uploaded");
      }
    }

    if (!meta.labels.isEmpty() || !opts.appendLabels) {
      client.syncLabels(pageId, meta.labels, opts.appendLabels);
    }

    String url = pageUrl(client.baseUrl(), page);
    log.info(action.wireName() + " " + path + ": " + url);
    return new SyncResult(action, path, meta.space, meta.title, pageId, url, "");
  }

  private static String appearanceOrDefault(Meta meta) {
    return meta.contentAppearance.isEmpty() ? "full-width" : meta.contentAppearance;
  }

  private static Object currentStorageValue(Map<String, Object> page) {
    if (!(page.get("body") instanceof Map<?, ?> body)) {
      return "";
    }
    if (!(body.get("storage") instanceof Map<?, ?> storage)) {
      return "";
    }
    Object value = storage.get("value");
    // Compared with String.equals: only a string can equal the new storage.
    return value == null ? "" : value;
  }

  private static String versionMessage(Map<String, Object> page) {
    if (!(page.get("version") instanceof Map<?, ?> version)) {
      return "";
    }
    return version.get("message") instanceof String text ? text : "";
  }

  private static String quoted(List<String> values) {
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        out.append(", ");
      }
      out.append("'").append(values.get(i)).append("'");
    }
    return out.append("]").toString();
  }

  /**
   * Syncs a batch of files as one run (one title map, sequential), collecting per-file failures. An
   * empty selection succeeds with an informational message. Per-file failures become {@code FAILED}
   * results; the caller fails the run with {@link RunReport#throwIfFailed}.
   */
  public static RunReport run(List<Path> files, SyncOptions opts, PageStore client, Log log) {
    List<SyncResult> results = new ArrayList<>();
    if (files.isEmpty()) {
      log.info("No changed pages to publish.");
      return new RunReport(results);
    }
    Map<String, String> pageTitles = buildPageTitleMap(files, opts);
    for (Path file : files) {
      try {
        results.add(syncFile(file, opts, client, pageTitles, log));
      } catch (MetaException | SyncException | ConfluenceException | IOException e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        log.error(file + ": " + message);
        results.add(new SyncResult(SyncAction.FAILED, file.toString(), "", "", "", "", message));
        if (!opts.continueOnError) {
          break;
        }
      }
    }
    return new RunReport(results);
  }
}
