package com.wiki.mark.metadata;

import com.wiki.mark.model.Meta;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Title derivation: leading-H1 extraction, filename titles, generated hashes.
 *
 * <p>Direct port of the corresponding part of {@code mark.metadata}.
 */
public final class TitleResolver {
  private static final Pattern FENCE_RE = Pattern.compile("^\\s*(```+|~~~+)");

  private static final Pattern INLINE_CODE_RE = Pattern.compile("`([^`]*)`");
  private static final Pattern INLINE_LINK_RE = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
  private static final Pattern INLINE_EMPH_RE = Pattern.compile("(\\*\\*|__)(.*?)\\1");
  private static final Pattern INLINE_EM_RE = Pattern.compile("(\\*|_)(.*?)\\1");

  private TitleResolver() {}

  /** Line indexes inside fenced code blocks (fences included). */
  static Set<Integer> codeFenceSpans(List<String> lines) {
    Set<Integer> hidden = new HashSet<>();
    Character fence = null;
    for (int idx = 0; idx < lines.size(); idx++) {
      Matcher match = FENCE_RE.matcher(lines.get(idx));
      if (match.find()) {
        char marker = match.group(1).charAt(0);
        if (fence == null) {
          fence = marker;
        } else if (fence == marker) {
          fence = null;
        }
        hidden.add(idx);
      } else if (fence != null) {
        hidden.add(idx);
      }
    }
    return hidden;
  }

  static String stripInline(String text) {
    text = INLINE_CODE_RE.matcher(text).replaceAll("$1");
    text = INLINE_LINK_RE.matcher(text).replaceAll("$1");
    text = INLINE_EMPH_RE.matcher(text).replaceAll("$2");
    text = INLINE_EM_RE.matcher(text).replaceAll("$2");
    return text.strip();
  }

  /** First H1's text (ATX {@code #} or Setext {@code ===}), outside fenced code. */
  public static String extractLeadingH1(String markdownText) {
    List<String> lines = TextLines.splitLines(markdownText);
    Set<Integer> hidden = codeFenceSpans(lines);
    for (int idx = 0; idx < lines.size(); idx++) {
      if (hidden.contains(idx)) {
        continue;
      }
      String line = lines.get(idx);
      String stripped = line.strip();
      if (stripped.startsWith("# ")) {
        return stripInline(rstripHash(stripped.substring(2).strip()));
      }
      if (stripped.equals("#")) {
        return "";
      }
      if (!stripped.isEmpty()
          && idx + 1 < lines.size()
          && !hidden.contains(idx + 1)
          && lines.get(idx + 1).strip().matches("=+\\s*")
          && !stripped.startsWith("#")
          && !stripped.startsWith(">")
          && !stripped.startsWith("-")
          && !stripped.startsWith("*")
          && !stripped.startsWith("+")
          && !stripped.startsWith("|")) {
        return stripInline(stripped);
      }
    }
    return "";
  }

  private static String rstripHash(String s) {
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == '#') {
      end--;
    }
    return s.substring(0, end).strip();
  }

  /**
   * Turn a file or directory name into a page title.
   *
   * <p>Ports Python {@code str.title()}: the first cased letter after a non-cased character is
   * uppercased, other cased letters are lowercased.
   */
  public static String titleFromName(String name) {
    String spaced = name.replace("_", " ").replace("-", " ");
    StringBuilder out = new StringBuilder(spaced.length());
    boolean prevCased = false;
    for (int i = 0; i < spaced.length(); ) {
      int cp = spaced.codePointAt(i);
      boolean cased =
          Character.getType(cp) == Character.UPPERCASE_LETTER
              || Character.getType(cp) == Character.LOWERCASE_LETTER
              || Character.getType(cp) == Character.TITLECASE_LETTER;
      if (cased) {
        out.appendCodePoint(prevCased ? Character.toLowerCase(cp) : Character.toUpperCase(cp));
      } else {
        out.appendCodePoint(cp);
      }
      prevCased = cased;
      i += Character.charCount(cp);
    }
    return out.toString();
  }

  /** Append a short hash of parents/space/title to make a title unique. */
  public static void appendGeneratedHash(Meta meta) {
    StringBuilder path = new StringBuilder();
    for (String parent : meta.parents) {
      path.append(parent).append('/');
    }
    path.append(meta.space).append('/').append(meta.title);
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      byte[] digest = sha256.digest(path.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      meta.title = meta.title + " - " + hex.substring(0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
