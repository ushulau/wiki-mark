package dev.vspex.wiki.mark.convert;

import java.io.IOException;
import java.io.InputStream;
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
 * PNG/SVG intrinsic-size reading and max-box fitting.
 *
 * <p>Direct port of {@code _intrinsic_size} and {@code _InlineRenderer._fitted_size} from {@code
 * mark.converter}. Only {@code .png} and {@code .svg} refs are ever sized; anything else (and any
 * image whose size can't be read) renders at natural size.
 *
 * <p>Only the binding dimension is emitted as a single {@code ac:width} or {@code ac:height}
 * attribute. Setting both makes Confluence build a server-side thumbnail at that size, which
 * silently fails and hides the image; with one attribute the browser scales the other dimension,
 * which stays within its own cap by construction.
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

  /** An image's own (width, height) in px. */
  record IntrinsicSize(double width, double height) {}

  /**
   * The single binding dimension to write as {@code ac:width} or {@code ac:height}.
   *
   * @param attr either {@code "width"} or {@code "height"}
   * @param pixels the capped pixel value of the binding dimension
   */
  record FittedSize(String attr, int pixels) {}

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
   * Return a PNG's own size in px, or null if unavailable.
   *
   * <p>Reads the first 24 bytes: the 8-byte PNG signature, the 4-byte chunk length, the {@code
   * IHDR} tag, then width and height as unsigned big-endian ints.
   */
  static IntrinsicSize pngIntrinsicSize(Path path) {
    final byte[] header;
    try (InputStream in = Files.newInputStream(path)) {
      header = in.readNBytes(24);
    } catch (IOException e) {
      return null;
    }
    if (header.length < 24) {
      return null;
    }
    byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    for (int i = 0; i < signature.length; i++) {
      if (header[i] != signature[i]) {
        return null;
      }
    }
    if (header[12] != 0x49 || header[13] != 0x48 || header[14] != 0x44 || header[15] != 0x52) {
      return null;
    }
    ByteBuffer buf = ByteBuffer.wrap(header);
    long width = buf.getInt(16) & 0xFFFFFFFFL;
    long height = buf.getInt(20) & 0xFFFFFFFFL;
    return new IntrinsicSize(width, height);
  }

  /**
   * Return a local image's own size in px, or null if unavailable.
   *
   * <p>Dispatches on the lowercased file name: {@code .svg} to {@link #svgIntrinsicSize}, {@code
   * .png} to {@link #pngIntrinsicSize}; any other extension returns null.
   */
  static IntrinsicSize intrinsicSize(Path path) {
    Path fileName = path.getFileName();
    String name =
        (fileName == null ? path.toString() : fileName.toString()).toLowerCase(Locale.ROOT);
    if (name.endsWith(".svg")) {
      return svgIntrinsicSize(path);
    }
    if (name.endsWith(".png")) {
      return pngIntrinsicSize(path);
    }
    return null;
  }

  /**
   * Scale a PNG/SVG's intrinsic size down to fit the max box, if needed.
   *
   * <p>Never upscales. Returns the binding dimension only (the one with the smaller scale factor;
   * width wins ties), or null if there is no max box configured, the ref isn't a PNG or SVG, its
   * size can't be determined, or it already fits.
   */
  static FittedSize fittedSize(String src, String baseDir, double maxWidth, double maxHeight) {
    if (!(maxWidth != 0.0 || maxHeight != 0.0)) {
      return null;
    }
    IntrinsicSize size = intrinsicSize(Paths.get(LinkResolver.normalizeJoin(baseDir, src)));
    if (size == null) {
      return null;
    }
    double width = size.width();
    double height = size.height();
    if (width <= 0 || height <= 0) {
      return null;
    }
    double scale = 1.0;
    String attr = null;
    double bound = 0.0;
    if (maxWidth != 0.0 && width > maxWidth) {
      scale = maxWidth / width;
      attr = "width";
      bound = width;
    }
    if (maxHeight != 0.0 && height > maxHeight) {
      double heightScale = maxHeight / height;
      if (attr == null || heightScale < scale) {
        scale = heightScale;
        attr = "height";
        bound = height;
      }
    }
    if (attr == null) {
      return null;
    }
    // Python round() is banker's rounding (half to even), like Math.rint.
    return new FittedSize(attr, (int) Math.rint(bound * scale));
  }
}
