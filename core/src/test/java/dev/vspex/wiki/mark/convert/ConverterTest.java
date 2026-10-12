package dev.vspex.wiki.mark.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vspex.wiki.mark.model.RenderResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Converter tests, ported 1:1 from {@code tests/test_converter.py} (same names, same inputs, same
 * expectations) and additionally covered by the oracle harness.
 *
 * <p>The three page-link tests build their {@code pageLinks} keys with {@code java.nio} so
 * separators always match (the pytest versions hardcode POSIX paths and fail on Windows).
 */
class ConverterTest {

  private static RenderResult render(String doc) {
    return MarkdownConverter.render(doc, new MarkdownConverter.RenderOptions());
  }

  private static RenderResult render(String doc, Consumer<MarkdownConverter.RenderOptions> opts) {
    MarkdownConverter.RenderOptions o = new MarkdownConverter.RenderOptions();
    opts.accept(o);
    return MarkdownConverter.render(doc, o);
  }

  @Test
  void test_headings_and_paragraph() {
    RenderResult result = render("# Title\n\nSome *em* and **strong** text.\n");
    assertEquals(
        "<h1>Title</h1><p>Some <em>em</em> and <strong>strong</strong> text.</p>",
        result.storage());
    assertEquals(List.of(), result.attachments());
  }

  @Test
  void test_setext_headings() {
    RenderResult result = render("Big\n===\n\nSmall\n---\n");
    assertEquals("<h1>Big</h1><h2>Small</h2>", result.storage());
  }

  @Test
  void test_drop_h1_atx_and_setext() {
    assertEquals("<p>Kept</p>", render("# Gone\n\nKept\n", o -> o.dropH1 = true).storage());
    assertEquals("<p>Kept</p>", render("Gone\n===\n\nKept\n", o -> o.dropH1 = true).storage());
    // Only the first H1 is dropped.
    assertEquals("<h1>Two</h1>", render("# One\n\n# Two\n", o -> o.dropH1 = true).storage());
  }

  @Test
  void test_inline_code_strike_and_escapes() {
    RenderResult result = render("Use `a < b` and ~~gone~~ and \\*literal\\*.\n");
    assertEquals(
        "<p>Use <code>a &lt; b</code> and <s>gone</s> and *literal*.</p>", result.storage());
  }

  @Test
  void test_entities_preserved() {
    assertEquals("<p>fish &amp; chips</p>", render("fish &amp; chips\n").storage());
  }

  @Test
  void test_fenced_code_macro() {
    RenderResult result = render("```python\nprint(1 < 2)\n```\n");
    assertEquals(
        "<ac:structured-macro ac:name=\"code\">"
            + "<ac:parameter ac:name=\"language\">python</ac:parameter>"
            + "<ac:plain-text-body><![CDATA[print(1 < 2)]]></ac:plain-text-body>"
            + "</ac:structured-macro>",
        result.storage());
  }

  @Test
  void test_fenced_code_tilde_no_language() {
    RenderResult result = render("~~~\nplain\n~~~\n");
    assertTrue(result.storage().contains("<ac:parameter ac:name=\"language\"></ac:parameter>"));
    assertTrue(result.storage().contains("plain"));
  }

  @Test
  void test_table() {
    RenderResult result = render("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 |\n");
    assertEquals(
        "<table><thead><tr><th>a</th><th>b</th></tr></thead>"
            + "<tbody><tr><td>1</td><td>2</td></tr>"
            + "<tr><td>3</td><td></td></tr></tbody></table>",
        result.storage());
  }

  @Test
  void test_lists_nested_ordered_and_tasks() {
    RenderResult result =
        render("- one\n  - nested\n- two\n\n1. first\n2. second\n\n- [x] done\n- [ ] todo\n");
    assertTrue(
        result.storage().contains("<ul><li>one<ul><li>nested</li></ul></li><li>two</li></ul>"));
    assertTrue(result.storage().contains("<ol><li>first</li><li>second</li></ol>"));
    assertTrue(result.storage().contains("<ac:task-status>complete</ac:task-status>"));
    assertTrue(result.storage().contains("<ac:task-status>incomplete</ac:task-status>"));
  }

  @Test
  void test_blockquote_and_hr() {
    RenderResult result = render("> quoted **bold**\n\n---\n");
    assertEquals(
        "<blockquote><p>quoted <strong>bold</strong></p></blockquote><hr />", result.storage());
  }

  @Test
  void test_links_and_autolinks() {
    RenderResult result = render("[label](https://example.com) and <https://x.test/y>\n");
    assertTrue(result.storage().contains("<a href=\"https://example.com\">label</a>"));
    assertTrue(result.storage().contains("<a href=\"https://x.test/y\">https://x.test/y</a>"));
  }

  @Test
  void test_local_image_becomes_attachment() {
    RenderResult result = render("![alt text](img/pic.png)\n");
    assertEquals(
        "<p><ac:image><ri:attachment ri:filename=\"pic.png\" />"
            + "<ac:caption>alt text</ac:caption></ac:image></p>",
        result.storage());
    assertEquals(List.of("img/pic.png"), result.attachments());
  }

  @Test
  void test_image_align_and_remote_passthrough() {
    RenderResult result =
        render("![](a.png)\n\n![](https://cdn/x.png)\n", o -> o.imageAlign = "center");
    assertTrue(result.storage().contains("<ac:image ac:align=\"center\">"));
    assertTrue(result.storage().contains("<img src=\"https://cdn/x.png\""));
    assertEquals(List.of("a.png"), result.attachments());
  }

  @Test
  void test_invalid_image_align_ignored() {
    RenderResult result = render("![](a.png)\n", o -> o.imageAlign = "top");
    assertTrue(result.storage().contains("<ac:image>"));
  }

  private static void writeSvg(Path tmpDir, String name, int width, int height) throws Exception {
    Files.writeString(
        tmpDir.resolve(name),
        "<svg xmlns=\"http://www.w3.org/2000/svg\" "
            + "width=\""
            + width
            + "\" height=\""
            + height
            + "\"><rect /></svg>");
  }

  @Test
  void test_oversized_svg_is_scaled_down_proportionally(@TempDir Path tmp) throws Exception {
    writeSvg(tmp, "big.svg", 1000, 500);
    RenderResult result =
        render(
            "![](big.svg)\n",
            o -> {
              o.imageMaxWidth = "400";
              o.imageMaxHeight = "400";
              o.imageBaseDir = tmp.toString();
            });
    // 1000x500 constrained to 400x400 -> width is the binding dimension.
    assertTrue(result.storage().contains("<ac:image ac:width=\"400\" ac:height=\"200\">"));
    assertEquals(List.of("big.svg"), result.attachments());
  }

  @Test
  void test_undersized_svg_is_left_alone(@TempDir Path tmp) throws Exception {
    writeSvg(tmp, "small.svg", 100, 50);
    RenderResult result =
        render(
            "![](small.svg)\n",
            o -> {
              o.imageMaxWidth = "400";
              o.imageMaxHeight = "400";
              o.imageBaseDir = tmp.toString();
            });
    assertEquals(
        "<p><ac:image><ri:attachment ri:filename=\"small.svg\" /></ac:image></p>",
        result.storage());
  }

  @Test
  void test_svg_with_only_viewbox_is_scaled(@TempDir Path tmp) throws Exception {
    Files.writeString(
        tmp.resolve("vb.svg"),
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 800 400\"><rect /></svg>");
    RenderResult result =
        render(
            "![](vb.svg)\n",
            o -> {
              o.imageMaxWidth = "400";
              o.imageBaseDir = tmp.toString();
            });
    assertTrue(result.storage().contains("<ac:image ac:width=\"400\" ac:height=\"200\">"));
  }

  @Test
  void test_unreadable_or_non_svg_image_is_left_alone(@TempDir Path tmp) {
    RenderResult result =
        render(
            "![](missing.svg)\n",
            o -> {
              o.imageMaxWidth = "400";
              o.imageBaseDir = tmp.toString();
            });
    assertTrue(result.storage().contains("<ac:image>"));
    RenderResult result2 =
        render(
            "![](a.png)\n",
            o -> {
              o.imageMaxWidth = "400";
              o.imageBaseDir = tmp.toString();
            });
    assertTrue(result2.storage().contains("<ac:image>"));
  }

  @Test
  void test_attach_referenced_links() {
    RenderResult result = render("[spec](docs/spec.pdf)\n", o -> o.attachReferenced = true);
    assertTrue(result.storage().contains("<ri:attachment ri:filename=\"spec.pdf\" />"));
    assertTrue(
        result
            .storage()
            .contains("<ac:plain-text-link-body><![CDATA[spec]]></ac:plain-text-link-body>"));
    assertEquals(List.of("docs/spec.pdf"), result.attachments());
  }

  @Test
  void test_attach_referenced_skips_remote() {
    RenderResult result =
        render("[web](https://example.com/f.pdf)\n", o -> o.attachReferenced = true);
    assertEquals("<p><a href=\"https://example.com/f.pdf\">web</a></p>", result.storage());
    assertEquals(List.of(), result.attachments());
  }

  @Test
  void test_raw_html_passthrough() {
    String macro = "<ac:structured-macro ac:name=\"toc\" />";
    assertEquals(macro, render(macro + "\n").storage());
  }

  @Test
  void test_strip_linebreaks_is_accepted_noop() {
    assertInstanceOf(RenderResult.class, render("# T\n", o -> o.stripLinebreaks = true));
  }

  @Test
  void test_url_encoded_attachment_filename() {
    RenderResult result = render("![](my%20pic.png)\n");
    assertTrue(result.storage().contains("ri:filename=\"my pic.png\""));
  }

  @Test
  void test_page_link_resolved_by_page_links_map(@TempDir Path tmp) {
    String key = Paths.get(tmp.toString(), "other.md").normalize().toString();
    RenderResult result =
        render(
            "[see also](other.md)\n",
            o -> {
              o.imageBaseDir = tmp.toString();
              o.pageLinks = Map.of(key, "Other Page");
            });
    assertEquals(
        "<p><ac:link><ri:page ri:content-title=\"Other Page\" />"
            + "<ac:plain-text-link-body><![CDATA[see also]]></ac:plain-text-link-body>"
            + "</ac:link></p>",
        result.storage());
    assertEquals(List.of(), result.attachments());
  }

  @Test
  void test_page_link_ignores_fragment_and_query(@TempDir Path tmp) {
    String key = Paths.get(tmp.toString(), "other.md").normalize().toString();
    RenderResult result =
        render(
            "[see also](other.md#section?x=1)\n",
            o -> {
              o.imageBaseDir = tmp.toString();
              o.pageLinks = Map.of(key, "Other Page");
            });
    assertTrue(result.storage().contains("ri:content-title=\"Other Page\""));
  }

  @Test
  void test_md_link_outside_batch_falls_back_to_plain_link() {
    RenderResult result =
        render(
            "[see also](missing.md)\n",
            o -> {
              o.imageBaseDir = "/docs";
              o.pageLinks = Map.of();
            });
    assertEquals("<p><a href=\"missing.md\">see also</a></p>", result.storage());
  }

  @Test
  void test_page_link_takes_precedence_over_attach_referenced(@TempDir Path tmp) {
    String key = Paths.get(tmp.toString(), "other.md").normalize().toString();
    RenderResult result =
        render(
            "[see also](other.md)\n",
            o -> {
              o.imageBaseDir = tmp.toString();
              o.pageLinks = Map.of(key, "Other Page");
              o.attachReferenced = true;
            });
    assertTrue(result.storage().contains("ri:page"));
    assertEquals(List.of(), result.attachments());
  }
}
