package com.wiki.mark.gradle;

import com.wiki.mark.sync.SyncException;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Gradle-free helpers behind {@code confluenceRenderDiagrams}: validation, container argument
 * building, and diagram selection. Plain JDK so unit tests cover it without a Gradle runtime.
 */
final class RenderSupport {
  private RenderSupport() {}

  /** Pinned mermaid-cli tag (digest recorded in {@code reference/PROVENANCE.md}). */
  static final String DEFAULT_CONTAINER_IMAGE = "minlag/mermaid-cli:12.0.1";

  /** Exact {@code mermaid.json} blob from the reference {@code render.sh} (no HTML labels). */
  static final String MERMAID_JSON =
      "{\"htmlLabels\": false, \"flowchart\": {\"htmlLabels\": false},"
          + " \"state\": {\"htmlLabels\": false},"
          + " \"fontFamily\": \"Arial, Helvetica, sans-serif\"}";

  /** Exact {@code puppeteer.json} blob from the reference {@code render.sh}. */
  static final String PUPPETEER_JSON =
      "{\"executablePath\": \"/usr/bin/chromium-browser\","
          + " \"args\": [\"--no-sandbox\", \"--disable-gpu\", \"--disable-dev-shm-usage\"]}";

  static final String XDG_CONFIG_HOME = "/tmp/.chromium";
  static final String XDG_CACHE_HOME = "/tmp/.chromium";

  /** Per-engine {@code extraRunArgs} default: podman maps the current user, docker needs none. */
  static List<String> defaultExtraRunArgs(String engine) {
    if ("podman".equals(engine)) {
      return List.of("--userns=keep-id");
    }
    return List.of();
  }

  /** Fail-fast validation of the diagrams block; the engine binary itself is probed at run time. */
  static void validate(String engine, int scale, String background) {
    if (!"docker".equals(engine) && !"podman".equals(engine)) {
      throw new SyncException(
          "invalid containerEngine '" + engine + "' (expected: docker or podman)");
    }
    if (scale < 1) {
      throw new SyncException(
          "invalid diagrams scale '" + scale + "' (expected: a positive integer)");
    }
    if (background == null || background.isBlank()) {
      throw new SyncException("diagrams background must not be empty");
    }
  }

  /**
   * Resolves the {@code .mmd} files to render: every {@code *.mmd} under {@code sourceDir},
   * name-sorted, or the {@code --only} selection (stems, with or without the {@code .mmd} suffix).
   * A missing source dir renders nothing.
   */
  static List<Path> resolveDiagrams(Path sourceDir, List<String> only) {
    if (!Files.isDirectory(sourceDir)) {
      return List.of();
    }
    List<Path> all;
    try (Stream<Path> list = Files.list(sourceDir)) {
      all =
          list.filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(".mmd"))
              .sorted(Comparator.comparing(path -> path.getFileName().toString()))
              .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (only == null || only.isEmpty()) {
      return all;
    }
    List<String> missing = new ArrayList<>();
    List<Path> selected = new ArrayList<>();
    for (String entry : only) {
      String stem = entry.endsWith(".mmd") ? entry.substring(0, entry.length() - 4) : entry;
      Path file = sourceDir.resolve(stem + ".mmd");
      if (!Files.isRegularFile(file)) {
        missing.add(entry);
      } else {
        selected.add(file.toAbsolutePath().normalize());
      }
    }
    if (!missing.isEmpty()) {
      throw new SyncException(
          "--only diagram(s) not found under '" + sourceDir + "': " + String.join(", ", missing));
    }
    selected.sort(Comparator.comparing(path -> path.getFileName().toString()));
    return selected;
  }

  /** True when {@code binary} resolves on the process {@code PATH}. */
  static boolean onPath(String binary) {
    return onPath(binary, System.getenv("PATH"));
  }

  /** Same, against an explicit path value so tests stay deterministic. */
  static boolean onPath(String binary, String pathEnv) {
    if (pathEnv == null || pathEnv.isEmpty()) {
      return false;
    }
    for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
      if (dir.isEmpty()) {
        continue;
      }
      if (isRunnable(Path.of(dir, binary)) || isRunnable(Path.of(dir, binary + ".exe"))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isRunnable(Path candidate) {
    return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
  }

  /** Host path as the container engine expects it: forward slashes (Windows-safe). */
  static String containerPath(Path path) {
    return path.toAbsolutePath().normalize().toString().replace('\\', '/');
  }

  /**
   * Full engine argument list for one diagram in one {@code format} ({@code png} or {@code svg}),
   * replicating {@code render.sh}: both mounts, the Chromium env, caller run args, then the pinned
   * image and the {@code mmdc} flags.
   */
  static List<String> containerArgs(
      Path sourceDir,
      Path cfgDir,
      String image,
      List<String> extraRunArgs,
      String background,
      int scale,
      String stem,
      String format) {
    if (!"png".equals(format) && !"svg".equals(format)) {
      throw new IllegalArgumentException("render format must be png or svg, got '" + format + "'");
    }
    List<String> args = new ArrayList<>();
    args.add("run");
    args.add("--rm");
    args.add("-v");
    args.add(containerPath(sourceDir) + ":/data");
    args.add("-v");
    args.add(containerPath(cfgDir) + ":/cfg");
    args.add("-e");
    args.add("XDG_CONFIG_HOME=" + XDG_CONFIG_HOME);
    args.add("-e");
    args.add("XDG_CACHE_HOME=" + XDG_CACHE_HOME);
    args.addAll(extraRunArgs);
    args.add(image);
    args.add("-p");
    args.add("/cfg/puppeteer.json");
    args.add("-c");
    args.add("/cfg/mermaid.json");
    args.add("-i");
    args.add("/data/" + stem + ".mmd");
    args.add("-o");
    args.add("/data/" + stem + "." + format);
    args.add("-b");
    args.add(background);
    args.add("-s");
    args.add(Integer.toString(scale));
    return args;
  }

  /** Actionable message when the engine binary is not on {@code PATH}. */
  static String engineNotFoundMessage(String engine) {
    String install = "podman".equals(engine) ? "Install Podman" : "Install Docker Desktop";
    return "Container engine '"
        + engine
        + "' not found on PATH. "
        + install
        + " and ensure '"
        + engine
        + "' is on PATH, or skip diagram rendering with"
        + " '-x confluenceRenderDiagrams' (or unset diagrams.convertToPng/convertToSvg).";
  }
}
