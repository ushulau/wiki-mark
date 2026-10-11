package com.wiki.mark.metadata;

import com.wiki.mark.model.Meta;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * HTML header comment parsing ({@code <!-- Key: value -->}) and known-header registry.
 *
 * <p>Direct port of the corresponding part of {@code mark.metadata}. The order of {@link
 * #KNOWN_HEADERS} is significant: {@link #nearestHeader} returns the first header within one edit,
 * so it must stay in this exact order.
 */
public final class HeaderParser {
  public static final String HEADER_PARENT = "Parent";
  public static final String HEADER_FOLDER = "Folder";
  public static final String HEADER_SPACE = "Space";
  public static final String HEADER_TYPE = "Type";
  public static final String HEADER_TITLE = "Title";
  public static final String HEADER_LAYOUT = "Layout";
  public static final String HEADER_SIDEBAR = "Sidebar";
  public static final String HEADER_EMOJI = "Emoji";
  public static final String HEADER_ATTACHMENT = "Attachment";
  public static final String HEADER_LABEL = "Label";
  public static final String HEADER_ORDER = "Order";
  public static final String HEADER_INCLUDE = "Include";
  public static final String HEADER_CONTENT_APPEARANCE = "Content-Appearance";
  public static final String HEADER_IMAGE_ALIGN = "Image-Align";
  public static final String HEADER_IMAGE_WIDTH = "Image-Width";
  public static final String HEADER_IMAGE_HEIGHT = "Image-Height";
  public static final String HEADER_PROPERTY = "Property";
  public static final String HEADER_SYNCHRONIZED = "Synchronized";

  public static final List<String> KNOWN_HEADERS =
      Arrays.asList(
          HEADER_ATTACHMENT,
          HEADER_CONTENT_APPEARANCE,
          HEADER_EMOJI,
          HEADER_FOLDER,
          HEADER_IMAGE_ALIGN,
          HEADER_IMAGE_WIDTH,
          HEADER_IMAGE_HEIGHT,
          HEADER_INCLUDE,
          HEADER_LABEL,
          HEADER_LAYOUT,
          HEADER_ORDER,
          HEADER_PARENT,
          HEADER_PROPERTY,
          HEADER_SIDEBAR,
          HEADER_SPACE,
          HEADER_SYNCHRONIZED,
          HEADER_TITLE,
          HEADER_TYPE);

  public static final String FULL_WIDTH_CONTENT_APPEARANCE = "full-width";
  public static final String FIXED_CONTENT_APPEARANCE = "fixed";
  public static final String DEFAULT_CONTENT_APPEARANCE = "default";

  private HeaderParser() {}

  /** The known header {@code key} names (case-insensitive), or null. */
  public static String canonicalHeader(String key) {
    String stripped = key.strip();
    for (String known : KNOWN_HEADERS) {
      if (stripped.equalsIgnoreCase(known)) {
        return known;
      }
    }
    return null;
  }

  /**
   * One insertion, deletion, substitution or transposition (case-insensitive).
   *
   * <p>Ports {@code edit_distance_within_one} exactly, including transposition counting as one.
   */
  public static boolean editDistanceWithinOne(String a, String b) {
    char[] x = a.toLowerCase(Locale.ROOT).toCharArray();
    char[] y = b.toLowerCase(Locale.ROOT).toCharArray();
    if (Math.abs(x.length - y.length) > 1) {
      return false;
    }
    int i = 0;
    while (i < x.length && i < y.length && x[i] == y[i]) {
      i++;
    }
    if (i == x.length && i == y.length) {
      return true;
    }
    if (x.length == y.length) {
      if (equalRest(x, i + 1, y, i + 1)) {
        return true;
      }
      return i + 1 < x.length
          && x[i] == y[i + 1]
          && x[i + 1] == y[i]
          && equalRest(x, i + 2, y, i + 2);
    }
    if (x.length > y.length) {
      return equalRest(x, i + 1, y, i);
    }
    return equalRest(x, i, y, i + 1);
  }

  private static boolean equalRest(char[] x, int xi, char[] y, int yi) {
    if (x.length - xi != y.length - yi) {
      return false;
    }
    for (int k = 0; k < x.length - xi; k++) {
      if (x[xi + k] != y[yi + k]) {
        return false;
      }
    }
    return true;
  }

  /** Header {@code key} was probably meant to be, if within one edit. */
  public static String nearestHeader(String key) {
    String stripped = key.strip();
    if (stripped.isEmpty()) {
      return null;
    }
    for (String known : KNOWN_HEADERS) {
      if (editDistanceWithinOne(stripped, known)) {
        return known;
      }
    }
    return null;
  }

  /** Split of a {@code <!-- Key: value -->} line, or null when it is not one. */
  public record HeaderComment(String key, String value) {}

  public static HeaderComment parseHeaderComment(String line) {
    String stripped = line.strip();
    if (!stripped.startsWith("<!--") || !stripped.endsWith("-->")) {
      return null;
    }
    if (stripped.length() < "<!--".length() + "-->".length()) {
      return null;
    }
    String content =
        stripped.substring("<!--".length(), stripped.length() - "-->".length()).strip();
    int sep = content.indexOf(':');
    if (sep < 0) {
      return null;
    }
    return new HeaderComment(content.substring(0, sep).strip(), content.substring(sep + 1).strip());
  }

  /**
   * Lenient content-appearance assignment: anything but {@code fixed}/{@code default} becomes
   * {@code full-width} (a typo silently becomes full-width â€” faithful to Python; only the
   * DSL/flag value is strictly validated).
   */
  public static void setContentAppearance(Meta meta, String value) {
    String v = value.strip();
    if (v.equals(FIXED_CONTENT_APPEARANCE)) {
      meta.contentAppearance = FIXED_CONTENT_APPEARANCE;
    } else if (v.equals(DEFAULT_CONTENT_APPEARANCE)) {
      meta.contentAppearance = DEFAULT_CONTENT_APPEARANCE;
    } else {
      meta.contentAppearance = FULL_WIDTH_CONTENT_APPEARANCE;
    }
  }
}
