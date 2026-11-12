package dev.vspex.wiki.mark.convert;

import dev.vspex.wiki.mark.metadata.TextLines;
import dev.vspex.wiki.mark.model.RenderResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Markdown to Confluence storage-format converter.
 *
 * <p>Direct port of {@code mark.converter.render}: ATX and Setext headings, paragraphs, fenced code
 * blocks, GFM tables, nested ordered/unordered lists, blockquotes, horizontal rules, inline
 * code/emphasis/links/images, plus raw-HTML passthrough.
 */
public final class MarkdownConverter {
  private MarkdownConverter() {}

  /**
   * Start marker of a lossless raw storage block. The markers carry no colon, so the metadata
   * parser treats them as ordinary comments: the header run ends at the first marker and stranded
   * header checks skip them.
   */
  public static final String RAW_BEGIN = "<!-- wiki-mark-raw-begin -->";

  /** End marker of a lossless raw storage block. */
  public static final String RAW_END = "<!-- wiki-mark-raw-end -->";

  /** Options for {@link #render}; mirrors {@code render} keyword arguments. */
  public static class RenderOptions {
    /** Creates options with defaults. */
    public RenderOptions() {}

    /** Do not publish the first H1 heading. */
    public boolean dropH1 = false;

    /** Accepted for CLI parity; always a no-op (see class javadoc in the reference). */
    public boolean stripLinebreaks = false;

    /** Image alignment; empty selects none. */
    public String imageAlign = "";

    /** Max image width in pixels; empty selects none. */
    public String imageMaxWidth = "";

    /** Max image height in pixels; empty selects none. */
    public String imageMaxHeight = "";

    /** Directory local image paths resolve from. */
    public String imageBaseDir = "";

    /** Maps a normalized absolute path of another local md file to its page title. */
    public Map<String, String> pageLinks = null;

    /** Upload local files that links point at. */
    public boolean attachReferenced = false;

    /** Explicit attachment paths/globs whose local links should become attachment links. */
    public List<String> attachmentPatterns = List.of();
  }

  /**
   * Render Markdown to Confluence storage format.
   *
   * @param markdownText the Markdown source
   * @param opts render options; {@code opts.pageLinks} maps a normalized absolute path of another
   *     local {@code .md} file to that file's Confluence page title, may be null
   * @return the storage plus the local refs to upload
   */
  public static RenderResult render(String markdownText, RenderOptions opts) {
    List<String> attachments = new ArrayList<>();
    String raw = extractRaw(markdownText);
    if (raw != null) {
      return new RenderResult(raw, attachments);
    }
    InlineRenderer inline =
        new InlineRenderer(
            opts.imageAlign,
            opts.imageMaxWidth,
            opts.imageMaxHeight,
            opts.imageBaseDir,
            opts.pageLinks,
            opts.attachReferenced,
            attachments);
    inline.setAttachmentPatterns(opts.attachmentPatterns);
    BlockRenderer blocks = new BlockRenderer(inline, opts.dropH1);
    String storage = String.join("", blocks.renderBlocks(TextLines.splitLines(markdownText)));
    return new RenderResult(storage, attachments);
  }

  /** Extracts the original payload; framing uses exactly one LF on each side. */
  static String extractRaw(String text) {
    int first = 0;
    while (first < text.length() && Character.isWhitespace(text.charAt(first))) first++;
    if (!text.startsWith(RAW_BEGIN, first)) {
      if (text.stripTrailing().endsWith("\n" + RAW_END) || text.strip().equals(RAW_END)) {
        throw new IllegalArgumentException("Raw storage closing marker has no opening marker");
      }
      return null;
    }
    int start = first + RAW_BEGIN.length();
    if (start >= text.length() || text.charAt(start) != '\n') {
      throw new IllegalArgumentException("Raw storage opening marker must end with LF");
    }
    start++;
    int end = text.length();
    while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
    int close = end - RAW_END.length();
    if (close < start || !text.startsWith(RAW_END, close) || text.charAt(close - 1) != '\n') {
      throw new IllegalArgumentException("Raw storage requires a closing marker on its own line");
    }
    return text.substring(start, close - 1);
  }
}
