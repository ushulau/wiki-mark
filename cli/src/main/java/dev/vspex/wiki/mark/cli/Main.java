package dev.vspex.wiki.mark.cli;

import dev.vspex.wiki.mark.client.ConfluenceClient;
import dev.vspex.wiki.mark.client.ConfluenceException;
import dev.vspex.wiki.mark.client.PageStore;
import dev.vspex.wiki.mark.model.MetaException;
import dev.vspex.wiki.mark.sync.Log;
import dev.vspex.wiki.mark.sync.PageSyncer;
import dev.vspex.wiki.mark.sync.RunReport;
import dev.vspex.wiki.mark.sync.SyncException;
import dev.vspex.wiki.mark.sync.SyncOptions;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Thin stdlib-only command line over core, for parity debugging against the Python tool.
 *
 * <p>Not the primary interface (that is the Gradle plugin); ships optionally. Only the retained
 * options exist here: no config file, no {@code MARK_*} variables, no {@code target-url}, no
 * accepted-but-unimplemented flags (unknown options are an error, not a silent no-op). The token
 * comes from {@code --password} or the {@code CONFLUENCE_TOKEN} environment variable and is never
 * printed.
 */
public final class Main {
  /** Must match the project version in the root build file. */
  static final String VERSION = "0.2.0";

  private static final String USAGE =
      """
      Usage: mark [sync] --files PATTERN... [options]

      Sync Markdown files to Confluence. Options:
        --files, -f PATTERN...     files to sync (glob patterns allowed, quote them)
        --space SPACE              Confluence space key
        --base-url, -b URL         Confluence base URL (not needed for --compile-only)
        --username, -u NAME        username (present: Basic auth; absent: Bearer token)
        --password, -p TOKEN       API token (or set CONFLUENCE_TOKEN)
        --parents P1/P2            parent pages prepended to each document's own
        --parents-delimiter D      delimiter for --parents (default '/')
        --parents-from-path        place each page under its directories' pages
        --parents-from-path-root R directory --parents-from-path measures from
        --title-from-h1            take the page title from the leading H1
        --title-from-filename      take the page title from the file name
        --title-append-generated-hash  append a short parents/space/title hash
        --drop-h1                  do not publish the first H1 heading
        --strip-linebreaks         accepted no-op (the renderer emits none)
        --compile-only             print storage HTML without touching Confluence
        --dry-run                  resolve pages/ancestry, print storage, write nothing
        --minor-edit               do not send notifications on update
        --version-message MSG      page version message
        --changes-only             skip pages whose content hash already matches
        --ci                       do not fail when patterns match nothing
        --continue-on-error        keep processing remaining files after a failure
        --insecure-skip-tls-verify skip TLS certificate verification
        --image-align A            left, center or right (header wins per file)
        --image-width PX           max image width in pixels (header wins)
        --image-height PX          max image height in pixels (header wins)
        --content-appearance A     full-width, fixed or default
        --append-labels            add labels without removing existing ones
        --attach-referenced        upload local files that links point at
        --trust-store PATH         extra trust store (augments the JVM store)
        --trust-store-password PW  trust store password
        --log-level LEVEL          debug, info or warning (default info)
        --help, -h                 print this help
        --version                  print the version
      """;

  private Main() {}

  /**
   * Runs the CLI.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err, System.getenv()));
  }

  /** Runs the CLI; returns the process exit code. Separated from {@code main} for tests. */
  static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env) {
    final CliConfig config;
    try {
      config = parse(List.of(args));
    } catch (SyncException e) {
      err.println("error: " + e.getMessage());
      return 1;
    }
    if (config.help) {
      out.print(USAGE);
      return 0;
    }
    if (config.version) {
      out.println("mark " + VERSION);
      return 0;
    }
    Log log = new CliLog(out, err, config.logLevel);
    try {
      validate(config);
      if (config.patterns.isEmpty()) {
        throw new SyncException("no files to sync (use --files)");
      }
      List<Path> files = resolveFiles(config.patterns, config.ci);
      PageStore client = null;
      if (!config.opts.compileOnly) {
        client = connect(config, env);
      }
      if (config.opts.parentsFromPathRoot.isEmpty()) {
        config.opts.parentsFromPathRoot = runRoot(config.patterns, "");
      }
      RunReport report = PageSyncer.run(files, config.opts, client, log);
      try {
        report.throwIfFailed();
      } catch (SyncException summary) {
        log.error(summary.getMessage());
        return 1;
      }
      return 0;
    } catch (SyncException | MetaException | ConfluenceException | IOException e) {
      log.error(e.getMessage() == null ? e.toString() : e.getMessage());
      return 1;
    }
  }

  private static PageStore connect(CliConfig config, Map<String, String> env) {
    if (config.baseUrl.isEmpty()) {
      throw new SyncException("confluence base URL is required (pass --base-url)");
    }
    String token =
        config.password.isEmpty() ? env.getOrDefault("CONFLUENCE_TOKEN", "") : config.password;
    if (token.equals("-")) {
      throw new SyncException(
          "password '-' (read from stdin) is not supported;"
              + " pass --password <token> or set CONFLUENCE_TOKEN");
    }
    if (token.isEmpty()) {
      throw new SyncException(
          "confluence token is required (pass --password or set CONFLUENCE_TOKEN)");
    }
    ConfluenceClient client = new ConfluenceClient(config.baseUrl, config.username, token);
    if (config.insecureSkipTlsVerify) {
      client.setInsecureSkipTlsVerify(true);
    } else if (!config.trustStore.isEmpty()) {
      client.setTrustStore(Path.of(config.trustStore), config.trustStorePassword);
    }
    return client;
  }

  /** Retained validations (ported from {@code validate_config}, adapted to this flag set). */
  static void validate(CliConfig config) {
    if (config.opts.titleFromH1 && config.opts.titleFromFilename) {
      throw new SyncException("--title-from-h1 and --title-from-filename are mutually exclusive");
    }
    String appearance = config.opts.contentAppearance.strip();
    if (!appearance.isEmpty()
        && !appearance.equals("full-width")
        && !appearance.equals("fixed")
        && !appearance.equals("default")) {
      throw new SyncException(
          "invalid --content-appearance '"
              + appearance
              + "' (expected: full-width, fixed, or default)");
    }
    // Align/dimensions reuse the sync-level checks (header-aware, same messages).
    PageSyncer.resolveImageAlign(config.opts.imageAlign, "");
    PageSyncer.resolveImageDimension("image-width", config.opts.imageWidth, "");
    PageSyncer.resolveImageDimension("image-height", config.opts.imageHeight, "");
    if (!config.logLevel.equals("debug")
        && !config.logLevel.equals("info")
        && !config.logLevel.equals("warning")) {
      throw new SyncException(
          "invalid --log-level '" + config.logLevel + "' (expected: debug, info, or warning)");
    }
  }

  /** Expands glob patterns to files (sorted per pattern, deduplicated). */
  static List<Path> resolveFiles(List<String> patterns, boolean ci) throws IOException {
    Path cwd = Path.of("").toAbsolutePath();
    Set<Path> matched = new LinkedHashSet<>();
    for (String pattern : patterns) {
      List<Path> files = new ArrayList<>();
      for (Path hit : PageSyncer.matchGlob(cwd, pattern)) {
        if (Files.isRegularFile(hit)) {
          files.add(hit);
        }
      }
      if (files.isEmpty() && !ci) {
        throw new SyncException("no files match pattern '" + pattern + "'");
      }
      matched.addAll(files);
    }
    return new ArrayList<>(matched);
  }

  /** Everything before the first wildcard segment (upstream page.GlobRoot). */
  static String globRoot(String pattern) {
    if (pattern.isEmpty()) {
      return "";
    }
    String[] segments = pattern.replace('\\', '/').split("/", -1);
    List<String> fixed = new ArrayList<>();
    for (String segment : segments) {
      if (segment.indexOf('*') >= 0
          || segment.indexOf('?') >= 0
          || segment.indexOf('[') >= 0
          || segment.indexOf('{') >= 0) {
        break;
      }
      fixed.add(segment);
    }
    if (fixed.size() == segments.length && !fixed.isEmpty()) {
      fixed = fixed.subList(0, fixed.size() - 1);
    }
    return String.join("/", fixed);
  }

  /** Directory parents-from-path measures from: the override, else the first glob root. */
  static String runRoot(List<String> patterns, String override) {
    if (!override.isEmpty()) {
      return override;
    }
    for (String pattern : patterns) {
      String root = globRoot(pattern);
      if (!root.isEmpty()) {
        return root;
      }
    }
    return "";
  }

  // -- argument parsing -------------------------------------------------------

  /** Parsed CLI state: effective sync options plus connection/selection details. */
  static final class CliConfig {
    final SyncOptions opts = new SyncOptions();
    final List<String> patterns = new ArrayList<>();
    String baseUrl = "";
    String username = "";
    String password = "";
    String rawParents = "";
    String parentsDelimiter = "/";
    String trustStore = "";
    String trustStorePassword = "";
    String logLevel = "info";
    boolean ci;
    boolean insecureSkipTlsVerify;
    boolean help;
    boolean version;
  }

  private interface Setter {
    void set(CliConfig config, String value);
  }

  private record Option(boolean takesValue, Setter setter) {}

  private static final Map<String, Option> OPTIONS = options();

  private static Map<String, Option> options() {
    Map<String, Option> options = new LinkedHashMap<>();
    options.put("--help", new Option(false, (config, value) -> config.help = true));
    options.put("-h", new Option(false, (config, value) -> config.help = true));
    options.put("--version", new Option(false, (config, value) -> config.version = true));
    options.put("--space", new Option(true, (config, value) -> config.opts.space = value));
    options.put("--base-url", new Option(true, (config, value) -> config.baseUrl = value));
    options.put("-b", new Option(true, (config, value) -> config.baseUrl = value));
    options.put("--username", new Option(true, (config, value) -> config.username = value));
    options.put("-u", new Option(true, (config, value) -> config.username = value));
    options.put("--password", new Option(true, (config, value) -> config.password = value));
    options.put("-p", new Option(true, (config, value) -> config.password = value));
    options.put("--parents", new Option(true, (config, value) -> config.rawParents = value));
    options.put(
        "--parents-delimiter",
        new Option(true, (config, value) -> config.parentsDelimiter = value));
    options.put(
        "--parents-from-path",
        new Option(false, (config, value) -> config.opts.parentsFromPath = true));
    options.put(
        "--parents-from-path-root",
        new Option(true, (config, value) -> config.opts.parentsFromPathRoot = value));
    options.put(
        "--title-from-h1", new Option(false, (config, value) -> config.opts.titleFromH1 = true));
    options.put(
        "--title-from-filename",
        new Option(false, (config, value) -> config.opts.titleFromFilename = true));
    options.put(
        "--title-append-generated-hash",
        new Option(false, (config, value) -> config.opts.titleAppendHash = true));
    options.put("--drop-h1", new Option(false, (config, value) -> config.opts.dropH1 = true));
    options.put(
        "--strip-linebreaks",
        new Option(false, (config, value) -> config.opts.stripLinebreaks = true));
    options.put(
        "--compile-only", new Option(false, (config, value) -> config.opts.compileOnly = true));
    options.put("--dry-run", new Option(false, (config, value) -> config.opts.dryRun = true));
    options.put("--minor-edit", new Option(false, (config, value) -> config.opts.minorEdit = true));
    options.put(
        "--version-message",
        new Option(true, (config, value) -> config.opts.versionMessage = value));
    options.put(
        "--changes-only", new Option(false, (config, value) -> config.opts.changesOnly = true));
    options.put("--ci", new Option(false, (config, value) -> config.ci = true));
    options.put(
        "--continue-on-error",
        new Option(false, (config, value) -> config.opts.continueOnError = true));
    options.put(
        "--insecure-skip-tls-verify",
        new Option(false, (config, value) -> config.insecureSkipTlsVerify = true));
    options.put(
        "--image-align", new Option(true, (config, value) -> config.opts.imageAlign = value));
    options.put(
        "--image-width", new Option(true, (config, value) -> config.opts.imageWidth = value));
    options.put(
        "--image-height", new Option(true, (config, value) -> config.opts.imageHeight = value));
    options.put(
        "--content-appearance",
        new Option(true, (config, value) -> config.opts.contentAppearance = value));
    options.put(
        "--append-labels", new Option(false, (config, value) -> config.opts.appendLabels = true));
    options.put(
        "--attach-referenced",
        new Option(false, (config, value) -> config.opts.attachReferenced = true));
    options.put("--trust-store", new Option(true, (config, value) -> config.trustStore = value));
    options.put(
        "--trust-store-password",
        new Option(true, (config, value) -> config.trustStorePassword = value));
    options.put("--log-level", new Option(true, (config, value) -> config.logLevel = value));
    // --files is multi-value and handled inline in parse, not through the table.
    return options;
  }

  static CliConfig parse(List<String> args) {
    CliConfig config = new CliConfig();
    List<String> tokens = new ArrayList<>(args);
    if (!tokens.isEmpty() && tokens.get(0).equals("sync")) {
      tokens.remove(0);
    }
    int i = 0;
    while (i < tokens.size()) {
      String token = tokens.get(i);
      String name = token;
      String inlineValue = null;
      int eq = token.indexOf('=');
      if (token.startsWith("--") && eq >= 0) {
        name = token.substring(0, eq);
        inlineValue = token.substring(eq + 1);
      }
      if (name.equals("--files") || name.equals("-f")) {
        if (inlineValue != null) {
          config.patterns.add(inlineValue);
          i++;
          continue;
        }
        int j = i + 1;
        while (j < tokens.size() && !isOption(tokens.get(j))) {
          config.patterns.add(tokens.get(j));
          j++;
        }
        if (j == i + 1) {
          throw new SyncException("option '--files' needs at least one value");
        }
        i = j;
        continue;
      }
      Option option = OPTIONS.get(name);
      if (option == null) {
        if (isOption(token)) {
          throw new SyncException("unknown option '" + name + "'");
        }
        throw new SyncException("unexpected argument '" + token + "'");
      }
      if (!option.takesValue()) {
        if (inlineValue != null) {
          throw new SyncException("option '" + name + "' takes no value");
        }
        option.setter().set(config, "");
        i++;
        continue;
      }
      if (inlineValue != null) {
        option.setter().set(config, inlineValue);
        i++;
        continue;
      }
      if (i + 1 >= tokens.size() || isOption(tokens.get(i + 1))) {
        throw new SyncException("option '" + name + "' needs a value");
      }
      option.setter().set(config, tokens.get(i + 1));
      i += 2;
    }
    if (!config.rawParents.isEmpty()) {
      String delimiter = config.parentsDelimiter.isEmpty() ? "/" : config.parentsDelimiter;
      List<String> parents = new ArrayList<>();
      for (String part : config.rawParents.split(Pattern.quote(delimiter), -1)) {
        if (!part.isEmpty()) {
          parents.add(part);
        }
      }
      config.opts.parents = parents;
    }
    return config;
  }

  private static boolean isOption(String token) {
    return token.startsWith("-") && token.length() > 1;
  }

  /** CLI log backend: level filter plus the Python tool's stderr prefixes. */
  private static final class CliLog implements Log {
    private final PrintStream out;
    private final PrintStream err;
    private final int level;

    CliLog(PrintStream out, PrintStream err, String logLevel) {
      this.out = out;
      this.err = err;
      if (logLevel.equals("debug")) {
        this.level = 0;
      } else if (logLevel.equals("warning")) {
        this.level = 2;
      } else {
        this.level = 1;
      }
    }

    @Override
    public void debug(String message) {
      if (level <= 0) {
        err.println("DEBUG " + message);
      }
    }

    @Override
    public void info(String message) {
      if (level <= 1) {
        out.println(message);
      }
    }

    @Override
    public void warn(String message) {
      if (level <= 2) {
        err.println("WARNING " + message);
      }
    }

    @Override
    public void error(String message) {
      err.println("error: " + message);
    }
  }
}
