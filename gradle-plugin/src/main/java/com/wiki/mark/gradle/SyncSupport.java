package com.wiki.mark.gradle;

import com.wiki.mark.client.ConfluenceClient;
import com.wiki.mark.client.PageStore;
import com.wiki.mark.sync.Log;
import com.wiki.mark.sync.PageSyncer;
import com.wiki.mark.sync.SyncException;
import com.wiki.mark.sync.SyncOptions;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Gradle-free helpers behind the confluence tasks: file selection, validation, change digests,
 * state handling, and client creation. Plain {@code java.nio} plus core so unit tests cover it
 * without a Gradle runtime.
 */
final class SyncSupport {
  private SyncSupport() {}

  private static final Pattern DIAGRAM_REF = Pattern.compile("diagrams/[A-Za-z0-9_-]*\\.png");
  private static final Pattern MD_LINK = Pattern.compile("\\(([0-9][0-9A-Za-z_-]*\\.md)\\)");

  /**
   * Resolves {@code pagesDir} + includes to existing files, deduplicated and sorted by relative
   * path for deterministic order.
   */
  static List<Path> resolveFiles(Path pagesDir, List<String> includes) throws IOException {
    Set<Path> matched = new LinkedHashSet<>();
    for (String include : includes) {
      for (Path hit : PageSyncer.matchGlob(pagesDir, include)) {
        if (Files.isRegularFile(hit)) {
          matched.add(hit.toAbsolutePath().normalize());
        }
      }
    }
    List<Path> files = new ArrayList<>(matched);
    files.sort(
        (left, right) -> relativeKey(pagesDir, left).compareTo(relativeKey(pagesDir, right)));
    return files;
  }

  /** Resolves {@code --only} entries against {@code pagesDir}; unknown files fail the run. */
  static List<Path> resolveOnlyFiles(Path pagesDir, List<String> only) {
    Path base = pagesDir.toAbsolutePath().normalize();
    List<Path> files = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (String entry : only) {
      Path file = base.resolve(entry).normalize();
      if (!file.startsWith(base) || !Files.isRegularFile(file)) {
        missing.add(entry);
      } else {
        files.add(file);
      }
    }
    if (!missing.isEmpty()) {
      throw new SyncException(
          "--only file(s) not found under pagesDir '"
              + pagesDir
              + "': "
              + String.join(", ", missing));
    }
    files.sort(
        (left, right) -> relativeKey(pagesDir, left).compareTo(relativeKey(pagesDir, right)));
    return files;
  }

  /** POSIX-style pagesDir-relative key used for state entries, logs, and output mapping. */
  static String relativeKey(Path pagesDir, Path file) {
    Path base = pagesDir.toAbsolutePath().normalize();
    Path target = file.toAbsolutePath().normalize();
    if (!target.startsWith(base)) {
      return target.getFileName() == null ? target.toString() : target.getFileName().toString();
    }
    return base.relativize(target).toString().replace('\\', '/');
  }

  /**
   * Config-level validations; per-file header values are checked at use time by core. Never sees
   * the token.
   */
  static void validate(SyncOptions opts) {
    if (opts.titleFromH1 && opts.titleFromFilename) {
      throw new SyncException("titleFromH1 and titleFromFilename are mutually exclusive");
    }
    String appearance = opts.contentAppearance.strip();
    if (!appearance.isEmpty()
        && !appearance.equals("full-width")
        && !appearance.equals("fixed")
        && !appearance.equals("default")) {
      throw new SyncException(
          "invalid contentAppearance '"
              + appearance
              + "' (expected: full-width, fixed, or default)");
    }
    PageSyncer.resolveImageAlign(opts.imageAlign, "");
    PageSyncer.resolveImageDimension("image-width", opts.imageWidth, "");
    PageSyncer.resolveImageDimension("image-height", opts.imageHeight, "");
  }

  /** Root {@code parentsFromPath} measures from: the override, else the pages dir. */
  static String effectiveParentsRoot(Path pagesDir, String configured, boolean parentsFromPath) {
    if (parentsFromPath && (configured == null || configured.isEmpty())) {
      return pagesDir.toAbsolutePath().normalize().toString();
    }
    return configured == null ? "" : configured;
  }

  /**
   * {@code --changed} digest: SHA-256 over the raw page bytes (headers included) plus the text of
   * each embedded diagram's {@code .mmd}, sorted by diagram name. Missing {@code .mmd} files are
   * ignored; PNG bytes never participate.
   */
  static String digestPage(Path pagesDir, Path file) throws IOException {
    byte[] raw = Files.readAllBytes(file);
    Set<String> names = new TreeSet<>();
    Matcher refs = DIAGRAM_REF.matcher(new String(raw, StandardCharsets.UTF_8));
    while (refs.find()) {
      String ref = refs.group();
      String base = ref.substring(ref.lastIndexOf('/') + 1);
      names.add(base.substring(0, base.length() - ".png".length()));
    }
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      sha.update(raw);
      for (String name : names) {
        Path mmd = pagesDir.resolve("diagrams").resolve(name + ".mmd");
        if (Files.isRegularFile(mmd)) {
          sha.update(Files.readAllBytes(mmd));
        }
      }
      return hex(sha.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 missing from the JDK", impossible);
    }
  }

  private static String hex(byte[] digest) {
    StringBuilder out = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return out.toString();
  }

  /** Change-tracking state file: inside {@code .gradle} so it survives {@code clean}. */
  static Path stateFile(Path projectDir) {
    return projectDir.resolve(".gradle").resolve("confluence-publish").resolve("state.properties");
  }

  /** Loads the state file; a missing file is an empty state. */
  static Map<String, String> loadState(Path stateFile) throws IOException {
    Map<String, String> state = new LinkedHashMap<>();
    if (!Files.isRegularFile(stateFile)) {
      return state;
    }
    Properties props = new Properties();
    props.load(
        new StringReader(new String(Files.readAllBytes(stateFile), StandardCharsets.ISO_8859_1)));
    for (String key : props.stringPropertyNames()) {
      state.put(key, props.getProperty(key));
    }
    return state;
  }

  /** Writes the state sorted by key, atomically (temp file + move). */
  static void saveState(Path stateFile, Map<String, String> state) throws IOException {
    Files.createDirectories(stateFile.getParent());
    StringBuilder out = new StringBuilder("# confluence-publish change tracking; do not edit\n");
    for (Map.Entry<String, String> entry : new TreeMap<>(state).entrySet()) {
      out.append(escapeKey(entry.getKey())).append('=').append(entry.getValue()).append('\n');
    }
    Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
    Files.writeString(
        tmp,
        out.toString(),
        StandardCharsets.ISO_8859_1,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
    try {
      Files.move(
          tmp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException fallback) {
      Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * One-time import of the legacy script's {@code .published-hashes} ({@code <sha> <basename>}).
   */
  static Map<String, String> importLegacyHashes(Path pagesDir) throws IOException {
    Map<String, String> imported = new LinkedHashMap<>();
    Path legacy = pagesDir.resolve(".published-hashes");
    if (!Files.isRegularFile(legacy)) {
      return imported;
    }
    for (String line : Files.readAllLines(legacy, StandardCharsets.UTF_8)) {
      String[] parts = line.strip().split("\\s+", 2);
      if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
        continue;
      }
      String key = legacyKey(pagesDir, parts[1]);
      if (key != null) {
        imported.putIfAbsent(key, parts[0]);
      }
    }
    return imported;
  }

  private static String legacyKey(Path pagesDir, String basename) throws IOException {
    if (Files.isRegularFile(pagesDir.resolve(basename))) {
      return basename;
    }
    List<Path> hits;
    try (Stream<Path> walk = Files.walk(pagesDir)) {
      hits =
          walk.filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().equals(basename))
              .toList();
    }
    return hits.size() == 1 ? relativeKey(pagesDir, hits.get(0)) : null;
  }

  private static String escapeKey(String key) {
    StringBuilder out = new StringBuilder(key.length() + 8);
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      switch (c) {
        case '\\', '=', ':', '#', '!' -> out.append('\\').append(c);
        case '\t' -> out.append("\\t");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\f' -> out.append("\\f");
        case ' ' -> {
          if (i == 0) {
            out.append('\\');
          }
          out.append(' ');
        }
        default -> {
          if (c < 0x20 || c > 0x7E) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  /**
   * Warns for each selected page linking to an {@code NN-*.md} file that exists under {@code
   * pagesDir} but is not selected: the cross-file link map covers the selected batch only.
   */
  static List<String> outOfBatchWarnings(Path pagesDir, List<Path> selected) throws IOException {
    Set<String> keys = new LinkedHashSet<>();
    for (Path file : selected) {
      keys.add(relativeKey(pagesDir, file));
    }
    List<String> warnings = new ArrayList<>();
    for (Path file : selected) {
      Set<String> targets = new LinkedHashSet<>();
      Matcher links = MD_LINK.matcher(Files.readString(file, StandardCharsets.UTF_8));
      while (links.find()) {
        targets.add(links.group(1));
      }
      for (String target : targets) {
        if (!keys.contains(target) && Files.isRegularFile(pagesDir.resolve(target))) {
          warnings.add(
              "WARNING: "
                  + relativeKey(pagesDir, file)
                  + " links to "
                  + target
                  + ", which is not being published; the link will be dead."
                  + " Run a full confluencePublish after adding/renaming pages.");
        }
      }
    }
    return warnings;
  }

  /** Creates the Confluence client; credentials come from Gradle providers, never from disk. */
  static PageStore createClient(
      String baseUrl,
      String username,
      String token,
      Path trustStore,
      String trustStorePassword,
      boolean insecureSkipTlsVerify,
      Log log) {
    ConfluenceClient client = new ConfluenceClient(baseUrl, username, token);
    if (insecureSkipTlsVerify) {
      log.warn(
          "TLS certificate verification is DISABLED (insecureSkipTlsVerify); use only for testing");
      client.setInsecureSkipTlsVerify(true);
    } else if (trustStore != null) {
      client.setTrustStore(trustStore, trustStorePassword == null ? "" : trustStorePassword);
    }
    return client;
  }
}
