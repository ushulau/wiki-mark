package dev.vspex.wiki.mark.metadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Line splitting with Python {@code str.splitlines()} semantics for the line breaks that occur in
 * practice ({@code \n}, {@code \r\n}, lone {@code \r}).
 *
 * <p>Python also splits on {@code \v \f \u2028} etc.; that edge is intentionally not ported.
 *
 * <p>Shared with the {@code convert} package (same line semantics everywhere).
 */
public final class TextLines {
  private TextLines() {}

  /**
   * Splits text into lines, discarding the breaks.
   *
   * @param text the text to split
   * @return the lines without breaks
   */
  public static List<String> splitLines(String text) {
    List<String> out = new ArrayList<>();
    int start = 0;
    int i = 0;
    int n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      if (c == '\n') {
        out.add(text.substring(start, i));
        i++;
        start = i;
      } else if (c == '\r') {
        out.add(text.substring(start, i));
        i++;
        if (i < n && text.charAt(i) == '\n') {
          i++;
        }
        start = i;
      } else {
        i++;
      }
    }
    if (start < n) {
      out.add(text.substring(start));
    }
    return out;
  }

  /**
   * Splits text into lines, keeping the breaks.
   *
   * @param text the text to split
   * @return the lines with breaks
   */
  public static List<String> splitLinesKeepends(String text) {
    List<String> out = new ArrayList<>();
    int start = 0;
    int i = 0;
    int n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      if (c == '\n') {
        out.add(text.substring(start, i + 1));
        i++;
        start = i;
      } else if (c == '\r') {
        i++;
        if (i < n && text.charAt(i) == '\n') {
          i++;
        }
        out.add(text.substring(start, i));
        start = i;
      } else {
        i++;
      }
    }
    if (start < n) {
      out.add(text.substring(start));
    }
    return out;
  }
}
