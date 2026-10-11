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
  /** The {@code Parent} header name. */
  public static final String HEADER_PARENT = "Parent";

  /** The {@code Folder} header name. */
  public static final String HEADER_FOLDER = "Folder";

  /** The {@code Space} header name. */
  public static final String HEADER_SPACE = "Space";

  /** The {@code Type} header name. */
  public static final String HEADER_TYPE = "Type";

  /** The {@code Title} header name. */
  public static final String HEADER_TITLE = "Title";

  /** The {@code Layout} header name. */
  public static final String HEADER_LAYOUT = "Layout";

  /** The {@code Sidebar} header name. */
  public static final String HEADER_SIDEBAR = "Sidebar";

  /** The {@code Emoji} header name. */
  public static final String HEADER_EMOJI = "Emoji";

  /** The {@code Attachment} header name. */
  public static final String HEADER_ATTACHMENT = "Attachment";

  /** The {@code Label} header name. */
  public static final String HEADER_LABEL = "Label";

  /** The {@code Order} header name. */
  public static final String HEADER_ORDER = "Order";

  /** The {@code Include} header name. */
  public static final String HEADER_INCLUDE = "Include";

  /** The {@code Content-Appearance} header name. */
  public static final String HEADER_CONTENT_APPEARANCE = "Content-Appearance";

  /** The {@code Image-Align} header name. */
  public static final String HEADER_IMAGE_ALIGN = "Image-Align";

  /** The {@code Image-Width} header name. */
  public static final String HEADER_IMAGE_WIDTH = "Image-Width";

  /** The {@code Image-Height} header name. */
  public static final String HEADER_IMAGE_HEIGHT = "Image-Height";

  /** The {@code Property} header name. */
  public static final String HEADER_PROPERTY = "Property";

  /** The {@code Synchronized} header name. */
  public static final String HEADER_SYNCHRONIZED = "Synchronized";

  /** Known headers in match order; the order is significant for {@link #nearestHeader}. */
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

  /** The full-width content appearance value. */
  public static final String FULL_WIDTH_CONTENT_APPEARANCE = "full-width";

  /** The fixed content appearance value. */
  public static final String FIXED_CONTENT_APPEARANCE = "fixed";

  /** The default content appearance value. */
  public static final String DEFAULT_CONTENT_APPEARANCE = "default";

  private HeaderParser() {}

  /**
   * The known header {@code key} names (case-insensitive), or null.
   *
   * @param key the header name as written
   * @return the canonical header name, or null when unknown
   */
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
   *
   * @param a the first string
   * @param b the second string
   * @return whether the strings are within one edit
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

  /**
   * Header {@code key} was probably meant to be, if within one edit.
   *
   * @param key the header name as written
   * @return the nearest known header, or null when nothing is close
   */
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

  /**
   * Split of a {@code <!-- Key: value -->} line, or null when it is not one.
   *
   * @param key the header name as written
   * @param value the header value
   */
  public record HeaderComment(String key, String value) {}

  /**
   * Splits a {@code <!-- Key: value -->} line.
   *
   * @param line the line to split
   * @return the split, or null when the line is not a header comment
   */
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
   *
   * @param meta metadata whose appearance is updated in place
   * @param value the appearance value
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
