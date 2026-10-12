package dev.vspex.wiki.mark.convert;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Link and attachment-filename resolution.
 *
 * <p>Direct port of {@code _is_local_ref}, {@code _attachment_filename} and {@code
 * _InlineRenderer._resolve_page_link} from {@code mark.converter}.
 */
public final class LinkResolver {
  private static final Pattern SCHEME_RE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");
  private static final Pattern DRIVE_ABS_RE = Pattern.compile("^[a-zA-Z]:[\\\\/]");

  private LinkResolver() {}

  static boolean isLocalRef(String ref) {
    String r = ref.strip();
    if (r.isEmpty() || r.startsWith("#")) {
      return false;
    }
    // os.path.isabs plus the explicit drive check. Rooted ("\\x") refs use Windows
    // semantics; the final verdict agrees with Linux Python anyway (no scheme → local).
    if (DRIVE_ABS_RE.matcher(r).find() || r.startsWith("/") || r.startsWith("\\")) {
      return true;
    }
    return !SCHEME_RE.matcher(r).lookingAt();
  }

  /**
   * Join {@code ref} onto {@code baseDir} and normalize, like {@code
   * os.path.normpath(os.path.join(baseDir, ref))}.
   */
  static String normalizeJoin(String baseDir, String ref) {
    return Path.of(baseDir).resolve(ref).normalize().toString();
  }

  /**
   * Look up a relative link to another synced {@code .md} file by title.
   *
   * <p>Returns null (falls back to a plain {@code <a>}) for anything not in {@code pageLinks}. A
   * {@code #fragment}/{@code ?query} suffix is dropped.
   */
  static String resolvePageLink(String href, String baseDir, Map<String, String> pageLinks) {
    if (pageLinks == null || pageLinks.isEmpty()) {
      return null;
    }
    String pathOnly = href.split("#", 2)[0].split("\\?", 2)[0];
    if (!pathOnly.toLowerCase(Locale.ROOT).endsWith(".md")) {
      return null;
    }
    return pageLinks.get(normalizeJoin(baseDir, pathOnly));
  }

  /**
   * Attachment filename for a ref: URL path part, percent-decoded, basename.
   *
   * <p>Ports {@code unquote(urlsplit(ref).path).replace("\\", "/").rsplit("/", 1)[-1]}.
   */
  static String attachmentFilename(String ref) {
    String s = ref;
    int hash = s.indexOf('#');
    if (hash >= 0) {
      s = s.substring(0, hash);
    }
    int query = s.indexOf('?');
    if (query >= 0) {
      s = s.substring(0, query);
    }
    s = percentDecode(s).replace('\\', '/');
    int slash = s.lastIndexOf('/');
    return slash >= 0 ? s.substring(slash + 1) : s;
  }

  /** Lenient percent-decoding like {@code urllib.parse.unquote} ({@code +} stays). */
  static String percentDecode(String s) {
    StringBuilder out = new StringBuilder();
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '%' && i + 2 < s.length()) {
        int hi = Character.digit(s.charAt(i + 1), 16);
        int lo = Character.digit(s.charAt(i + 2), 16);
        if (hi >= 0 && lo >= 0) {
          buf.write((hi << 4) | lo);
          i += 3;
          continue;
        }
      }
      flushDecodeBuffer(buf, out);
      out.append(c);
      i++;
    }
    flushDecodeBuffer(buf, out);
    return out.toString();
  }

  private static void flushDecodeBuffer(ByteArrayOutputStream buf, StringBuilder out) {
    if (buf.size() > 0) {
      out.append(new String(buf.toByteArray(), StandardCharsets.UTF_8));
      buf.reset();
    }
  }
}
