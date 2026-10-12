package dev.vspex.wiki.mark.metadata;

import dev.vspex.wiki.mark.model.Meta;
import dev.vspex.wiki.mark.model.MetaException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * YAML front matter ({@code ---} block) splitting and application.
 *
 * <p>Direct port of the corresponding part of {@code mark.metadata}. HTML headers override scalar
 * front matter values and append to repeatable ones (handled by the parse order in {@link
 * MetadataParser}).
 */
public final class FrontMatterParser {
  private FrontMatterParser() {}

  /**
   * Split of a document into optional YAML text and the remaining body.
   *
   * @param yamlOrNull the YAML text, or null when the document has no front matter
   * @param body the remaining body
   */
  public record FrontMatter(String yamlOrNull, String body) {}

  /**
   * Split {@code ---} YAML front matter off the front of {@code text}.
   *
   * <p>When the document does not open with a {@code ---} line, or no closing {@code ---}/{@code
   * ...} line follows, there is no front matter and the body is returned unchanged.
   *
   * @param text the document
   * @return the split
   */
  public static FrontMatter splitFrontMatter(String text) {
    List<String> lines = TextLines.splitLinesKeepends(text);
    if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
      return new FrontMatter(null, text);
    }
    for (int idx = 1; idx < lines.size(); idx++) {
      String stripped = lines.get(idx).strip();
      if (stripped.equals("---") || stripped.equals("...")) {
        StringBuilder yaml = new StringBuilder();
        for (int k = 1; k < idx; k++) {
          yaml.append(lines.get(k));
        }
        StringBuilder body = new StringBuilder();
        for (int k = idx + 1; k < lines.size(); k++) {
          body.append(lines.get(k));
        }
        return new FrontMatter(yaml.toString(), body.toString());
      }
    }
    return new FrontMatter(null, text);
  }

  static String normaliseKey(String key) {
    return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
  }

  /** Port of {@code _to_string_list}. Booleans stringify as {@code True}/{@code False}. */
  static List<String> asStringList(Object value) {
    if (value == null) {
      return new ArrayList<>();
    }
    if (value instanceof String s) {
      String t = s.strip();
      List<String> out = new ArrayList<>();
      if (!t.isEmpty()) {
        out.add(t);
      }
      return out;
    }
    if (value instanceof List<?> list) {
      List<String> out = new ArrayList<>();
      for (Object item : list) {
        if (item instanceof String s && !s.strip().isEmpty()) {
          out.add(s.strip());
        } else if (item instanceof Long || item instanceof Double || item instanceof Boolean) {
          out.add(pyStr(item));
        }
      }
      return out;
    }
    if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
      List<String> out = new ArrayList<>();
      out.add(pyStr(value));
      return out;
    }
    return new ArrayList<>();
  }

  /** Port of {@code _to_string}. Booleans stringify as {@code true}/{@code false}. */
  static String asString(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof String s) {
      return s.strip();
    }
    if (value instanceof Boolean b) {
      return b ? "true" : "false";
    }
    if (value instanceof Long || value instanceof Double) {
      return pyStr(value);
    }
    return pyRepr(value);
  }

  /** Port of {@code _to_bool}. */
  static boolean asBool(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof String s) {
      String lowered = s.strip().toLowerCase(Locale.ROOT);
      if (lowered.equals("true")
          || lowered.equals("yes")
          || lowered.equals("on")
          || lowered.equals("1")) {
        return true;
      }
      if (lowered.equals("false")
          || lowered.equals("no")
          || lowered.equals("off")
          || lowered.equals("0")) {
        return false;
      }
    }
    throw new MetaException("synchronized must be true or false, got " + pyRepr(value));
  }

  /** Port of {@code _to_int}. */
  static int asInt(Object value) {
    if (value instanceof Boolean) {
      throw new MetaException("order must be a whole number, got " + pyRepr(value));
    }
    if (value instanceof Long l) {
      if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
        throw new MetaException("order must be a whole number, got " + pyRepr(value));
      }
      return l.intValue();
    }
    if (value instanceof Double d) {
      // Python: value != float(int(value)) â€” int() truncates toward zero.
      if (d.isNaN() || d.isInfinite() || d != (double) (long) d.doubleValue()) {
        throw new MetaException("order must be a whole number, got " + pyRepr(value));
      }
      long truncated = (long) d.doubleValue();
      if (truncated < Integer.MIN_VALUE || truncated > Integer.MAX_VALUE) {
        throw new MetaException("order must be a whole number, got " + pyRepr(value));
      }
      return (int) truncated;
    }
    if (value instanceof String s) {
      try {
        return Integer.parseInt(s.strip());
      } catch (NumberFormatException e) {
        // fall through to the error below
      }
    }
    throw new MetaException("order must be a whole number, got " + pyRepr(value));
  }

  /** Python {@code str()} for numbers/bools in list position. */
  static String pyStr(Object value) {
    if (value instanceof Boolean b) {
      return b ? "True" : "False";
    }
    if (value instanceof Double d) {
      return d.toString();
    }
    return String.valueOf(value);
  }

  /** Python {@code repr()} for message formatting (simple values; no quote escaping). */
  static String pyRepr(Object value) {
    if (value == null) {
      return "None";
    }
    if (value instanceof String s) {
      return pyReprString(s);
    }
    if (value instanceof Boolean b) {
      return b ? "True" : "False";
    }
    if (value instanceof List<?> list) {
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(pyRepr(list.get(i)));
      }
      return sb.append("]").toString();
    }
    if (value instanceof Map<?, ?> map) {
      StringBuilder sb = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        sb.append(pyRepr(e.getKey())).append(": ").append(pyRepr(e.getValue()));
      }
      return sb.append("}").toString();
    }
    return String.valueOf(value);
  }

  static String pyReprString(String s) {
    if (s.contains("'") && !s.contains("\"")) {
      return "\"" + s + "\"";
    }
    return "'" + s + "'";
  }

  /** Apply parsed front matter onto {@code meta}; returns warnings for unknown keys. */
  static List<String> applyFrontMatter(Meta meta, Map<String, Object> parsed, String filename) {
    List<String> warnings = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    for (Map.Entry<String, Object> entry : parsed.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      String norm = normaliseKey(key);
      switch (norm) {
        case "parents" -> {
          meta.parents.addAll(asStringList(value));
          meta.declaredParents = true;
        }
        case "folders" -> meta.folders.addAll(asStringList(value));
        case "space" -> meta.space = asString(value);
        case "type" -> {
          String t = asString(value);
          meta.type = t.isEmpty() ? "page" : t;
        }
        case "title" -> meta.title = asString(value);
        case "layout" -> meta.layout = asString(value);
        case "sidebar" -> meta.sidebar = asString(value);
        case "emoji" -> meta.emoji = asString(value);
        case "attachments" -> meta.attachments.addAll(asStringList(value));
        case "labels" -> meta.labels.addAll(asStringList(value));
        case "contentappearance" -> HeaderParser.setContentAppearance(meta, asString(value));
        case "imagealign" -> meta.imageAlign = asString(value).toLowerCase(Locale.ROOT);
        case "imagewidth" -> meta.imageWidth = asString(value);
        case "imageheight" -> meta.imageHeight = asString(value);
        case "order" -> meta.order = asInt(value);
        case "synchronized" -> meta.synchronizedFlag = asBool(value);
        case "properties" -> {
          if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
              meta.properties.put(String.valueOf(e.getKey()), e.getValue());
            }
          } else if (value != null) {
            throw new MetaException("properties must be a mapping, got " + pyRepr(value));
          }
        }
        default -> unknown.add(key);
      }
    }
    Collections.sort(unknown);
    for (String key : unknown) {
      String where = filename.isEmpty() ? "" : filename + ": ";
      warnings.add(where + "front matter key '" + key + "' is not read by mark and was ignored");
    }
    if (!meta.sidebar.isEmpty()) {
      meta.layout = "article";
    }
    return warnings;
  }
}
