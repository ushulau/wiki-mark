package dev.vspex.wiki.mark.importer;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/** Portable filenames, selectors, and filesystem containment checks. */
final class ImportPaths {
  private static final Pattern RESERVED =
      Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?");

  private ImportPaths() {}

  static String safeName(String value) {
    String name =
        value
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}._-]+", "-")
            .replaceAll("^[. -]+|[. -]+$", "");
    if (name.length() > 80) name = name.substring(0, 80).replaceAll("[. -]+$", "");
    if (name.isEmpty()) name = "page";
    if (RESERVED.matcher(name).matches()) name = "page-" + name;
    return name;
  }

  static String safeFilename(String value) {
    int dot = value.lastIndexOf('.');
    if (dot > 0 && value.substring(dot).matches("\\.[A-Za-z0-9]{1,10}")) {
      return safeName(value.substring(0, dot)) + value.substring(dot).toLowerCase(Locale.ROOT);
    }
    return safeName(value);
  }

  static String select(String baseUrl, ImportOptions options) {
    URI site = uri(baseUrl);
    if (site.getRawQuery() != null || site.getRawFragment() != null) {
      throw new ImportException("Confluence base URL must not include a query or fragment");
    }
    if (options.pageId().isEmpty() == options.pageUrl().isEmpty()) {
      throw new ImportException("Specify exactly one of --page-id or --page-url");
    }
    String id = options.pageId();
    if (!options.pageUrl().isEmpty()) {
      URI base = uri(baseUrl);
      URI url = uri(options.pageUrl());
      String context = base.getPath().replaceAll("/$", "");
      if (!sameOrigin(base, url) || !(url.getPath().startsWith(context + "/"))) {
        throw new ImportException("Page URL must belong to the configured Confluence site");
      }
      var match = Pattern.compile("/pages/([0-9]+)(?:/|$)").matcher(url.getPath());
      if (match.find()) id = match.group(1);
      else if (url.getPath().endsWith("/viewpage.action") && url.getRawQuery() != null) {
        for (String pair : url.getRawQuery().split("&")) {
          String[] parts = pair.split("=", 2);
          if (parts.length == 2 && parts[0].equals("pageId")) {
            id = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
          }
        }
      }
    }
    if (!id.matches("[0-9]+")) {
      throw new ImportException("Unsupported page selector; use --page-id with a numeric ID");
    }
    return id;
  }

  static URI uri(String value) {
    try {
      URI uri = URI.create(value);
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || !("http".equalsIgnoreCase(uri.getScheme())
              || "https".equalsIgnoreCase(uri.getScheme()))) {
        throw new IllegalArgumentException();
      }
      return uri;
    } catch (IllegalArgumentException e) {
      throw new ImportException("Expected an HTTP(S) URL without embedded credentials");
    }
  }

  static boolean sameOrigin(URI a, URI b) {
    return a.getScheme().equalsIgnoreCase(b.getScheme())
        && a.getHost().equalsIgnoreCase(b.getHost())
        && port(a) == port(b);
  }

  private static int port(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80);
  }

  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  static Path output(Path root, String relative) {
    String normalized = relative.replace('\\', '/');
    for (String part : normalized.split("/", -1)) {
      if (part.isEmpty()
          || part.equals(".")
          || part.equals("..")
          || part.matches(".*[<>:\"|?*\\p{Cntrl}].*")
          || part.endsWith(".")
          || part.endsWith(" ")
          || RESERVED.matcher(part).matches()) {
        throw new ImportException("Output must be a safe relative .md path under documents/wiki");
      }
    }
    Path path = root.resolve(normalized).normalize();
    if (!path.startsWith(root) || !normalized.endsWith(".md")) {
      throw new ImportException("Output must be a relative .md path under documents/wiki");
    }
    return path;
  }

  static void check(Path project, Path target) throws IOException {
    Path base = project.toRealPath();
    Path absolute = target.toAbsolutePath().normalize();
    if (!absolute.startsWith(project.toAbsolutePath().normalize())) {
      throw new ImportException("Output escapes the project directory");
    }
    Path current = project.toAbsolutePath().normalize();
    for (Path part : current.relativize(absolute)) {
      current = current.resolve(part);
      if (!current.equals(absolute)
          && Files.exists(current, LinkOption.NOFOLLOW_LINKS)
          && !Files.isDirectory(current)) {
        throw new ImportException("Output parent is not a directory: " + current);
      }
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
          && (Files.isSymbolicLink(current)
              || !current
                  .toRealPath()
                  .equals(
                      base.resolve(project.toAbsolutePath().normalize().relativize(current))))) {
        throw new ImportException("Output path follows a symlink or junction");
      }
      // Reject even internal symlinks to keep the output tree unambiguous.
      if (Files.isSymbolicLink(current))
        throw new ImportException("Output paths may not contain symlinks");
    }
  }
}
