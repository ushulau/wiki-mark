package dev.vspex.wiki.mark.sync;

import dev.vspex.wiki.mark.client.ConfluenceException;
import dev.vspex.wiki.mark.client.EnsureResult;
import dev.vspex.wiki.mark.client.PageStore;
import dev.vspex.wiki.mark.convert.LinkResolver;
import dev.vspex.wiki.mark.convert.MarkdownConverter;
import dev.vspex.wiki.mark.metadata.MetadataParser;
import dev.vspex.wiki.mark.metadata.TitleResolver;
import dev.vspex.wiki.mark.model.Meta;
import dev.vspex.wiki.mark.model.MetaException;
import dev.vspex.wiki.mark.model.RenderResult;
import dev.vspex.wiki.mark.model.SyncAction;
import dev.vspex.wiki.mark.model.SyncResult;
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

  /**
   * Directory parents of a file measured from a root, plus whether it is an index page.
   *
   * @param parents the parent titles in order
   * @param index whether the file stands for its own directory
   */
  public record PathParents(List<String> parents, boolean index) {}

  private static final Set<String> INDEX_STEMS = Set.of("index", "readme");
  private static final Pattern INCLUDE_RE = Pattern.compile("<!--\\s*Include:\\s*(.*?)\\s*-->");

  /**
   * Directory parents for {@code file} measured from {@code root}.
   *
   * <p>Port of {@code parents_from_path}: path segments Title-Cased, an {@code index.md} or {@code
   * README.md} standing for its own directory (last segment dropped), nothing when the file sits
   * outside the root. An empty root falls back to the last two directories.
   *
   * @param file the file
   * @param root the root to measure from
   * @return the parents plus the index flag
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

  /**
   * Effective image alignment (per-file header wins); anything but left/center/right errors.
   *
   * @param configAlign the configured alignment
   * @param metaAlign the per-file alignment
   * @return the effective alignment
   */
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

  /**
   * Effective image dimension (per-file header wins); must be a positive integer when set.
   *
   * @param name the option name for messages
   * @param configValue the configured value
   * @param metaValue the per-file value
   * @return the effective value
   */
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

  /**
   * Targets of the {@code <!-- Include: ... -->} markers in a body (unexpanded, warned about).
   *
   * @param body the document body
   * @return the include targets
   */
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

  /**
   * Warns, per file, about headers the publisher reads but never applies.
   *
   * @param path the file path for messages
   * @param meta the document metadata
   * @param body the document body
   * @param log the log
   */
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
   *
   * @param files the files to map
   * @param opts the sync options
   * @return the path to title map
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

  /**
   * Public page URL from the page links (base falls back to the client's base URL).
   *
   * @param baseUrl the fallback base URL
   * @param page the page map
   * @return the page URL
   */
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
   *
   * @param sourceDir the directory relative patterns resolve from
   * @param pattern the glob pattern
   * @return the matching paths, sorted
   * @throws IOException if a directory cannot be walked
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
   *
   * @param patterns the attachment patterns
   * @param sourceDir the directory patterns resolve from
   * @return the matched files, deduplicated
   * @throws IOException if a directory cannot be walked
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
   *
   * @param file the file to sync
   * @param opts the sync options
   * @param client the page store, or null for the compile branch
   * @param pageTitles the path to title map for cross-file links
   * @param log the log
   * @return the file outcome
   * @throws IOException if the file cannot be read
   */
  public static SyncResult syncFile(
      Path file, SyncOptions opts, PageStore client, Map<String, String> pageTitles, Log log)
      throws IOException {
    if (opts.baselineDirectory == null || opts.dryRun || opts.compileOnly || client == null)
      return syncFileLocked(file, opts, client, pageTitles, log);
    Path lockDirectory = opts.baselineDirectory.toAbsolutePath().getParent();
    Files.createDirectories(lockDirectory);
    try (var channel =
        java.nio.channels.FileChannel.open(
            lockDirectory.resolve(
                ".publish-"
                    + ContentHash.contentHash(
                        opts.baselineDirectory.toAbsolutePath().normalize().toString())
                    + ".lock"),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE)) {
      java.nio.channels.FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (java.nio.channels.OverlappingFileLockException e) {
        throw new SyncException("Another publish is using this baseline directory");
      }
      if (lock == null) throw new SyncException("Another publish is using this baseline directory");
      try (lock) {
        return syncFileLocked(file, opts, client, pageTitles, log);
      }
    }
  }

  private static SyncResult syncFileLocked(
      Path file, SyncOptions opts, PageStore client, Map<String, String> pageTitles, Log log)
      throws IOException {
    if (opts.merge && (opts.force || opts.compileOnly))
      throw new SyncException("--merge cannot be combined with --force or compile-only");
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
    renderOpts.attachmentPatterns = meta.attachments;
    RenderResult result = MarkdownConverter.render(body, renderOpts);
    warnUnsupportedHeaders(path, meta, body, log);

    if (opts.compileOnly || client == null) {
      String title = meta.title.isEmpty() ? "(no title)" : meta.title;
      log.storage(file, meta.space, title, result.storage());
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
      Path refPath = sourceDir.resolve(LinkResolver.decodeLocalPath(ref)).normalize();
      if (Files.isRegularFile(refPath)) {
        if (!attachments.contains(refPath)) {
          attachments.add(refPath);
        }
      } else {
        log.warn(path + ": referenced file '" + ref + "' does not exist; skipped");
      }
    }

    String storage = Layout.applyLayout(result.storage(), meta.layout, meta.sidebar);
    String digest = ContentHash.contentHash(storage);
    Map<String, Object> page = client.findPage(meta.space, meta.title, meta.type);
    Map<String, Object> current =
        page == null ? null : client.getPageById(String.valueOf(page.get("id")));
    String snapshotContext =
        ContentHash.contentHash(
            file.toAbsolutePath().normalize().toString()
                + "\n"
                + opts.dropH1
                + "\n"
                + imageAlign
                + "\n"
                + imageWidth
                + "\n"
                + imageHeight
                + "\n"
                + meta.layout
                + "\n"
                + meta.sidebar
                + "\nrenderer-0.5");
    String localInput = storage;
    String identity = current == null ? "" : String.valueOf(current.get("id"));
    MergeSnapshot snapshot =
        opts.baselineDirectory == null || current == null
            ? null
            : MergeSnapshot.read(opts.baselineDirectory, client.baseUrl(), identity);
    if (opts.merge && current != null) {
      String remote = String.valueOf(currentStorageValue(current));
      if (!storage.equals(remote)) {
        if (snapshot == null
            || !snapshotContext.equals(snapshot.context())
            || snapshot.version() > versionNumber(current))
          throw new SyncException(
              "Merge unavailable: establish matching local/remote snapshots first");
        String tag = ContentHash.readContentHash(versionMessage(current));
        if (snapshot.version() == versionNumber(current) && !snapshot.remote().equals(remote))
          throw new SyncException("Merge unavailable: contradictory snapshot version");
        if (versionNumber(current) > snapshot.version()
            && !tag.isEmpty()
            && !tag.equals(ContentHash.contentHash(snapshot.remote())))
          throw new SyncException(
              "Merge unavailable: newer publication supersedes snapshot history");
        storage =
            StorageMerge.merge(
                snapshot.remote(),
                StorageMerge.merge(snapshot.local(), localInput, snapshot.remote()),
                remote);
      }
      // Existing-page merge deliberately leaves auxiliary resources untouched.
      Set<String> requiredNames = StorageMerge.attachmentNames(storage);
      requiredNames.removeAll(StorageMerge.attachmentNames(remote));
      for (Path attachment : attachments) requiredNames.add(attachment.getFileName().toString());
      if (!requiredNames.isEmpty()) {
        Set<String> names = new java.util.HashSet<>();
        for (var attachment : client.getAttachments(identity))
          names.add(String.valueOf(attachment.get("title")));
        if (!names.containsAll(requiredNames))
          throw new SyncException(
              "Body-only merge cannot upload missing attachment; publish assets separately first");
        log.warn(
            "Body-only merge preserves existing attachments; local attachment changes deferred");
      }
      if (!meta.labels.isEmpty())
        log.warn("Body-only merge preserves remote labels; local labels deferred");
      if (opts.dryRun) {
        log.info(
            "merge preview "
                + path
                + "\n"
                + StorageDiff.diffStorage(
                    redact(remote, opts), redact(storage, opts), "remote", "merged"));
        return new SyncResult(SyncAction.DRY_RUN, path, meta.space, meta.title, identity, "", "");
      }
      Map<String, Object> accepted = current;
      if (!storage.equals(remote)) {
        accepted =
            client.updateBody(
                current,
                storage,
                opts.minorEdit,
                ContentHash.formatVersionMessage(
                    opts.versionMessage, ContentHash.contentHash(storage)));
        try {
          long expected = versionNumber(accepted);
          accepted = client.getPageById(identity);
          if (versionNumber(accepted) != expected)
            throw new SyncException("readback version changed");
        } catch (RuntimeException e) {
          throw new SyncException(
              "Merge published but verification failed; snapshots not advanced. Reconcile the remote page before retrying.",
              e);
        }
      }
      String actual = String.valueOf(currentStorageValue(accepted));
      try {
        new MergeSnapshot(localInput, actual, versionNumber(accepted), snapshotContext)
            .save(opts.baselineDirectory, client.baseUrl(), identity);
        new PublishBaseline(ContentHash.contentHash(actual), versionNumber(accepted))
            .save(opts.baselineDirectory, client.baseUrl(), identity);
      } catch (IOException e) {
        throw new SyncException(
            "Merge accepted remotely but local tracking failed; reconcile before retrying.", e);
      }
      log.info(
          (storage.equals(remote) ? "remote retained " : "merged ")
              + path
              + ": "
              + pageUrl(client.baseUrl(), accepted));
      return new SyncResult(
          storage.equals(remote) ? SyncAction.UNCHANGED : SyncAction.UPDATED,
          path,
          meta.space,
          meta.title,
          identity,
          pageUrl(client.baseUrl(), accepted),
          "Merged body; auxiliary resources preserved");
    }
    boolean retainedRemote = snapshot != null && !snapshot.local().equals(snapshot.remote());
    boolean equal = current != null && storage.equals(currentStorageValue(current));
    boolean drift = retainedRemote && !equal;
    String diagnostic = "";
    if (current != null && !equal) {
      String remote = String.valueOf(currentStorageValue(current));
      PublishBaseline state =
          PublishBaseline.read(
              opts.baselineDirectory, client.baseUrl(), String.valueOf(current.get("id")));
      String baseline =
          PublishBaseline.resolve(
              ContentHash.readContentHash(versionMessage(current)),
              versionNumber(current),
              state,
              ContentHash.contentHash(remote));
      drift = drift || baseline.isEmpty() || !ContentHash.contentHash(remote).equals(baseline);
      // A newer remote revision is drift even when its body was restored to the accepted hash.
      drift = drift || (state != null && state.versionNumber() != versionNumber(current));
      if (drift) {
        String kind =
            baseline.isEmpty()
                ? "unknown baseline"
                : digest.equals(baseline) ? "remote-only drift" : "conflict";
        String detail =
            "Remote version "
                + versionNumber(current)
                + " (baseline version "
                + (state == null ? "unknown" : String.valueOf(state.versionNumber()))
                + "), last edited by "
                + versionAuthor(current)
                + " at "
                + versionWhen(current)
                + ".";
        String diff =
            StorageDiff.diffStorage(redact(remote, opts), redact(storage, opts), "remote", "local");
        if (diff.isEmpty())
          diff = "Raw storage differs; display normalization hides the differences.\n";
        diagnostic = redact("Publish stopped: " + kind + "\n" + detail + "\n" + diff, opts);
      }
    }
    if (opts.dryRun) {
      if (drift) log.warn(diagnostic);
      log.info(
          "dry-run "
              + path
              + ": "
              + (equal ? "unchanged" : "would publish '")
              + (equal ? "" : meta.title + "' in space " + meta.space));
      log.info(redact(storage, opts));
      return new SyncResult(SyncAction.DRY_RUN, path, meta.space, meta.title, "", "", "");
    }
    if (equal) {
      String id = String.valueOf(current.get("id"));
      String url = pageUrl(client.baseUrl(), current);
      if (opts.baselineDirectory != null)
        new MergeSnapshot(localInput, storage, versionNumber(current), snapshotContext)
            .save(opts.baselineDirectory, client.baseUrl(), id);
      log.info("unchanged " + path + ": " + url);
      return new SyncResult(SyncAction.UNCHANGED, path, meta.space, meta.title, id, url, "");
    }
    if (drift) {
      log.warn(diagnostic);
      if (!opts.force) {
        if (opts.confirmOverwrite == null)
          throw new SyncException(
              diagnostic
                  + "Run with --force after reviewing the diff, or re-import to refresh the"
                  + " baseline.");
        if (!opts.confirmOverwrite.test(diagnostic)) {
          return new SyncResult(
              SyncAction.SKIPPED,
              path,
              meta.space,
              meta.title,
              String.valueOf(current.get("id")),
              pageUrl(client.baseUrl(), current),
              "Kept remote storage");
        }
      }
    }
    // No mutations, including creating ancestors, occur before the drift decision.
    String parentId =
        ParentChain.ensureParentChain(client, meta.space, meta.type, meta.parents, false, log);
    String message = ContentHash.formatVersionMessage(opts.versionMessage, digest);
    SyncAction action;
    if (current == null) {
      page = client.createPage(meta.space, meta.title, storage, meta.type, parentId, message);
      action = SyncAction.CREATED;
    } else {
      page =
          client.updatePage(
              current, storage, opts.minorEdit, message, appearanceOrDefault(meta), meta.emoji);
      action = SyncAction.UPDATED;
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

    if (opts.baselineDirectory != null) {
      Map<String, Object> verified = client.getPageById(pageId);
      if (versionNumber(verified) != versionNumber(page))
        throw new SyncException(
            "Published but verification version changed; snapshots not advanced");
      new MergeSnapshot(
              localInput,
              String.valueOf(currentStorageValue(verified)),
              versionNumber(verified),
              snapshotContext)
          .save(opts.baselineDirectory, client.baseUrl(), pageId);
    }
    new PublishBaseline(digest, versionNumber(page))
        .save(opts.baselineDirectory, client.baseUrl(), pageId);
    String url = pageUrl(client.baseUrl(), page);
    log.info(action.wireName() + " " + path + ": " + url);
    return new SyncResult(action, path, meta.space, meta.title, pageId, url, "");
  }

  private static long versionNumber(Map<String, Object> page) {
    if (page.get("version") instanceof Map<?, ?> version
        && version.get("number") instanceof Number number) return number.longValue();
    throw new SyncException("Page response has no version number; cannot safely track publication");
  }

  private static String redact(String value, SyncOptions opts) {
    for (String secret : opts.redactions)
      if (secret != null && !secret.isEmpty()) value = value.replace(secret, "[redacted]");
    return value;
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

  /**
   * Display name of the version author, or a placeholder when the response omits it. Expanded
   * {@code version} objects carry {@code by.displayName}; older or partial responses may not.
   */
  private static String versionAuthor(Map<String, Object> page) {
    if (page.get("version") instanceof Map<?, ?> version
        && version.get("by") instanceof Map<?, ?> by
        && by.get("displayName") instanceof String name
        && !name.isEmpty()) {
      return name;
    }
    return "unknown editor";
  }

  /** Timestamp of the version, or a placeholder when the response omits it. */
  private static String versionWhen(Map<String, Object> page) {
    if (page.get("version") instanceof Map<?, ?> version
        && version.get("when") instanceof String when
        && !when.isEmpty()) {
      return when;
    }
    return "unknown time";
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
   * results; the caller fails the run with {@link RunReport#throwIfFailed}. Cross-file links
   * resolve against the batch itself.
   *
   * @param files the files to sync
   * @param opts the sync options
   * @param client the page store, or null for the compile branch
   * @param log the log
   * @return the run report
   */
  public static RunReport run(List<Path> files, SyncOptions opts, PageStore client, Log log) {
    return run(files, buildPageTitleMap(files, opts), opts, client, log);
  }

  /**
   * Syncs {@code files}, resolving cross-file links against {@code linkUniverse} instead of the
   * batch. Lets changed-only runs keep working links to unchanged pages: the map covers every page
   * while only the selection syncs.
   *
   * @param files the files to sync
   * @param linkUniverse the files to resolve link titles from
   * @param opts the sync options
   * @param client the page store, or null for the compile branch
   * @param log the log
   * @return the run report
   */
  public static RunReport run(
      List<Path> files, List<Path> linkUniverse, SyncOptions opts, PageStore client, Log log) {
    return run(files, buildPageTitleMap(linkUniverse, opts), opts, client, log);
  }

  private static RunReport run(
      List<Path> files,
      Map<String, String> pageTitles,
      SyncOptions opts,
      PageStore client,
      Log log) {
    List<SyncResult> results = new ArrayList<>();
    if (files.isEmpty()) {
      log.info("No changed pages to publish.");
      return new RunReport(results);
    }
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
