package com.wiki.mark.convert;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SVG intrinsic-size reading and max-box fitting.
 *
 * <p>Direct port of {@code _svg_intrinsic_size} and {@code _InlineRenderer._fitted_size} from
 * {@code mark.converter}. Only {@code .svg} refs are ever sized; anything else (and any SVG whose
 * size can't be read) renders at natural size.
 */
public final class ImageSizer {
  private static final Pattern SVG_TAG_RE =
      Pattern.compile("<svg\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern SVG_DIM_RE =
      Pattern.compile("\\b(width|height)\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern SVG_DIM_VALUE_RE =
      Pattern.compile("^([\\d.]+)\\s*(?:px)?$", Pattern.CASE_INSENSITIVE);
  private static final Pattern SVG_VIEWBOX_RE =
      Pattern.compile(
          "viewBox\\s*=\\s*\"\\s*[-\\d.]+\\s+[-\\d.]+\\s+([\\d.]+)\\s+([\\d.]+)\\s*\"",
          Pattern.CASE_INSENSITIVE);

  private ImageSizer() {}

  /** An SVG's own (width, height) in px. */
  record IntrinsicSize(double width, double height) {}

  /**
   * Return an SVG's own size in px, or null if unavailable.
   *
   * <p>Reads explicit {@code width}/{@code height} attributes (percentages are not a fixed pixel
   * size and are ignored) and falls back to {@code viewBox}.
   */
  static IntrinsicSize svgIntrinsicSize(Path path) {
    final String text;
    try {
      byte[] bytes = Files.readAllBytes(path);
      CharsetDecoder decoder =
          StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE);
      try {
        text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException e) {
        return null;
      }
    } catch (IOException e) {
      return null;
    }
    Matcher tagMatch = SVG_TAG_RE.matcher(text);
    String tag = tagMatch.find() ? tagMatch.group(0) : text;
    Map<String, Double> dims = new LinkedHashMap<>();
    Matcher dimMatch = SVG_DIM_RE.matcher(tag);
    while (dimMatch.find()) {
      Matcher valueMatch = SVG_DIM_VALUE_RE.matcher(dimMatch.group(2).strip());
      if (valueMatch.matches()) {
        dims.put(
            dimMatch.group(1).toLowerCase(Locale.ROOT), Double.parseDouble(valueMatch.group(1)));
      }
    }
    if (dims.containsKey("width") && dims.containsKey("height")) {
      return new IntrinsicSize(dims.get("width"), dims.get("height"));
    }
    Matcher viewboxMatch = SVG_VIEWBOX_RE.matcher(tag);
    if (viewboxMatch.find()) {
      return new IntrinsicSize(
          Double.parseDouble(viewboxMatch.group(1)), Double.parseDouble(viewboxMatch.group(2)));
    }
    return null;
  }

  /**
   * Scale an SVG's intrinsic size down to fit the max box, if needed.
   *
   * <p>Preserves aspect ratio and never upscales. Returns null if there is no max box configured,
   * the ref isn't an SVG, or its size can't be determined.
   */
  static int[] fittedSize(String src, String baseDir, double maxWidth, double maxHeight) {
    if (!(maxWidth != 0.0 || maxHeight != 0.0)) {
      return null;
    }
    if (!src.toLowerCase(Locale.ROOT).endsWith(".svg")) {
      return null;
    }
    IntrinsicSize size = svgIntrinsicSize(Paths.get(LinkResolver.normalizeJoin(baseDir, src)));
    if (size == null) {
      return null;
    }
    double width = size.width();
    double height = size.height();
    if (width <= 0 || height <= 0) {
      return null;
    }
    double scale = 1.0;
    if (maxWidth != 0.0 && width > maxWidth) {
      scale = Math.min(scale, maxWidth / width);
    }
    if (maxHeight != 0.0 && height > maxHeight) {
      scale = Math.min(scale, maxHeight / height);
    }
    if (scale >= 1.0) {
      return null;
    }
    // Python round() is banker's rounding (half to even), like Math.rint.
    return new int[] {(int) Math.rint(width * scale), (int) Math.rint(height * scale)};
  }
}
