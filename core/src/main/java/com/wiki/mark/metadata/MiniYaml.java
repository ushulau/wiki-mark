package com.wiki.mark.metadata;

import com.wiki.mark.model.MetaException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal YAML-subset parser for front matter only.
 *
 * <p>Direct port of {@code mark.metadata.parse_simple_yaml} and its helpers: {@code key: scalar},
 * {@code key: [a, b]}, {@code key:} followed by an indented {@code - item} list or an indented
 * {@code subkey: value} mapping. Anything deeper raises {@link MetaException}.
 *
 * <p>Accepted micro-divergences from Python's scalar coercion (absurd inputs only):
 *
 * <ul>
 *   <li>Underscored numbers ({@code 1_0}) and {@code inf} parse as strings here (Python reads them
 *       as numbers); hex floats are rejected as numbers like Python does.
 *   <li>Integers beyond {@code long} range fall through to {@code double}/string (Python ints are
 *       unbounded).
 * </ul>
 */
public final class MiniYaml {
  private MiniYaml() {}

  /** Strip a trailing {@code #} comment, honouring single/double quotes. */
  static String stripComment(String line) {
    Character quote = null;
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < line.length()) {
      char ch = line.charAt(i);
      if (quote == null && ch == '#') {
        break;
      }
      if (ch == '\'' || ch == '"') {
        if (quote == null) {
          quote = ch;
        } else if (quote == ch) {
          if (ch == '\'' && i + 1 < line.length() && line.charAt(i + 1) == '\'') {
            out.append("''");
            i += 2;
            continue;
          }
          quote = null;
        }
      }
      out.append(ch);
      i++;
    }
    return out.toString();
  }

  static Object parseScalar(String raw) {
    String text = raw.strip();
    if (text.length() >= 2
        && text.charAt(0) == text.charAt(text.length() - 1)
        && (text.charAt(0) == '\'' || text.charAt(0) == '"')) {
      String inner = text.substring(1, text.length() - 1);
      if (text.charAt(0) == '\'') {
        inner = inner.replace("''", "'");
      } else {
        inner = inner.replace("\\\"", "\"").replace("\\\\", "\\");
      }
      return inner;
    }
    String lowered = text.toLowerCase(java.util.Locale.ROOT);
    if (lowered.equals("true") || lowered.equals("yes") || lowered.equals("on")) {
      return Boolean.TRUE;
    }
    if (lowered.equals("false") || lowered.equals("no") || lowered.equals("off")) {
      return Boolean.FALSE;
    }
    if (lowered.equals("null") || lowered.equals("~") || lowered.equals("")) {
      return null;
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      // fall through
    }
    if (!text.matches("(?i)^[+-]?0[xX].*")) {
      try {
        return Double.parseDouble(text);
      } catch (NumberFormatException e) {
        // fall through
      }
    }
    if (text.startsWith("[") && text.endsWith("]")) {
      String inner = text.substring(1, text.length() - 1).strip();
      if (inner.isEmpty()) {
        return new ArrayList<Object>();
      }
      List<Object> items = new ArrayList<>();
      for (String part : splitFlow(inner)) {
        items.add(parseScalar(part));
      }
      return items;
    }
    return text;
  }

  static List<String> splitFlow(String text) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    Character quote = null;
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (quote != null) {
        current.append(ch);
        if (ch == quote) {
          quote = null;
        }
      } else if (ch == '\'' || ch == '"') {
        quote = ch;
        current.append(ch);
      } else if (ch == '[') {
        depth++;
        current.append(ch);
      } else if (ch == ']') {
        depth--;
        current.append(ch);
      } else if (ch == ',' && depth == 0) {
        parts.add(current.toString());
        current = new StringBuilder();
      } else {
        current.append(ch);
      }
    }
    parts.add(current.toString());
    return parts;
  }

  /**
   * Parse a flat YAML mapping with one level of block lists/mappings.
   *
   * @param text the YAML mapping text
   * @return the parsed mapping
   * @throws MetaException on anything deeper or misshapen.
   */
  public static Map<String, Object> parseSimpleYaml(String text) {
    Map<String, Object> result = new LinkedHashMap<>();
    List<String> lines = TextLines.splitLines(text);
    int i = 0;
    int n = lines.size();
    while (i < n) {
      String raw = lines.get(i);
      String line = stripTrailing(stripComment(raw));
      i++;
      if (line.strip().isEmpty()) {
        continue;
      }
      if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
        throw new MetaException("unexpected indented line in front matter: '" + raw.strip() + "'");
      }
      int sep = line.indexOf(':');
      if (sep < 0) {
        throw new MetaException("expected 'key: value' in front matter, got '" + raw.strip() + "'");
      }
      String key = line.substring(0, sep).strip();
      if (key.isEmpty()) {
        throw new MetaException("empty key in front matter: '" + raw.strip() + "'");
      }
      String rest = line.substring(sep + 1).strip();
      if (!rest.isEmpty()) {
        result.put(key, parseScalar(rest));
        continue;
      }
      // Nested block: gather indented lines (indent/blank tested on the raw line).
      List<String> children = new ArrayList<>();
      while (i < n && (isIndented(lines.get(i)) || lines.get(i).strip().isEmpty())) {
        if (!lines.get(i).strip().isEmpty()) {
          children.add(lines.get(i));
        }
        i++;
      }
      if (children.isEmpty()) {
        result.put(key, null);
        continue;
      }
      int indent = Integer.MAX_VALUE;
      for (String c : children) {
        indent = Math.min(indent, indentWidth(c));
      }
      List<String> stripped = new ArrayList<>();
      for (String c : children) {
        stripped.add(c.substring(indent));
      }
      boolean allItems = true;
      for (String s : stripped) {
        String t = s.strip();
        if (!(t.startsWith("- ") || t.equals("-"))) {
          allItems = false;
          break;
        }
      }
      if (!stripped.isEmpty() && allItems) {
        List<Object> items = new ArrayList<>();
        for (String s : stripped) {
          String item = s.strip().substring(1).strip();
          if (item.startsWith("-")) {
            throw new MetaException("nested lists are not supported in front matter");
          }
          items.add(parseScalar(stripComment(item)));
        }
        result.put(key, items);
      } else {
        Map<String, Object> mapping = new LinkedHashMap<>();
        for (String s : stripped) {
          if (isIndented(s) || s.strip().startsWith("- ")) {
            throw new MetaException("only one nesting level is supported in front matter");
          }
          int sub = s.indexOf(':');
          if (sub < 0 || s.substring(0, sub).strip().isEmpty()) {
            throw new MetaException(
                "expected 'key: value' in front matter, got '" + s.strip() + "'");
          }
          mapping.put(
              s.substring(0, sub).strip(), parseScalar(stripComment(s.substring(sub + 1).strip())));
        }
        result.put(key, mapping);
      }
    }
    return result;
  }

  private static boolean isIndented(String line) {
    return !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t');
  }

  private static int indentWidth(String line) {
    int w = 0;
    while (w < line.length() && (line.charAt(w) == ' ' || line.charAt(w) == '\t')) {
      w++;
    }
    return w;
  }

  private static String stripTrailing(String s) {
    int end = s.length();
    while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
      end--;
    }
    return s.substring(0, end);
  }
}
