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
    InlineRenderer inline =
        new InlineRenderer(
            opts.imageAlign,
            opts.imageMaxWidth,
            opts.imageMaxHeight,
            opts.imageBaseDir,
            opts.pageLinks,
            opts.attachReferenced,
            attachments);
    BlockRenderer blocks = new BlockRenderer(inline, opts.dropH1);
    String storage = String.join("", blocks.renderBlocks(TextLines.splitLines(markdownText)));
    return new RenderResult(storage, attachments);
  }
}
