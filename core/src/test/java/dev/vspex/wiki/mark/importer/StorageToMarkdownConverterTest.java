package dev.vspex.wiki.mark.importer;

import static org.junit.jupiter.api.Assertions.*;

import dev.vspex.wiki.mark.convert.MarkdownConverter;
import java.util.List;
import org.junit.jupiter.api.Test;

class StorageToMarkdownConverterTest {
  private static StorageToMarkdownConverter.Result convert(String storage) {
    return StorageToMarkdownConverter.convert(
        "https://wiki.example/wiki",
        page(storage),
        List.of(new PageImportSource.AttachmentSnapshot("att9", "Photo.png", "/download/9")),
        "guide.assets");
  }

  static PageImportSource.PageSnapshot page(String storage) {
    return new PageImportSource.PageSnapshot(
        "7", "Guide", "ENG", 2, storage, List.of("Engineering"), List.of("demo"));
  }

  @Test
  void convertsSupportedBlocksAndEscapesLiteralMarkup() {
    var result =
        convert(
            "<h2>Hello</h2><p>Unicode café &amp; &copy; &nbsp; &lt;script&gt; "
                + "<strong>bold</strong> <em>italic</em> <del>old</del> <code>x_y</code> *literal*</p>"
                + "<blockquote><p>Quote</p></blockquote><hr/><ol start=\"3\"><li>Third<ul><li>Nested</li></ul></li></ol>");
    assertTrue(result.markdown().startsWith("## Hello\n\n"), result.markdown());
    assertTrue(result.markdown().contains("**bold** *italic* ~~old~~ `x_y`"));
    assertTrue(result.markdown().contains("\\*literal\\*"));
    assertTrue(result.markdown().contains("©"));
    assertTrue(result.markdown().contains("3. Third"));
    assertTrue(result.markdown().contains("   - Nested"));
    assertTrue(result.warnings().isEmpty());
    String compiled =
        MarkdownConverter.render(result.markdown(), new MarkdownConverter.RenderOptions())
            .storage();
    assertTrue(compiled.contains("<h2>Hello</h2>"), compiled);
    assertTrue(compiled.contains("<strong>bold</strong>"));
  }

  @Test
  void codePreservesWhitespaceCdataAndEmbeddedFences() {
    var result =
        convert(
            "<ac:structured-macro ac:name=\"code\"><ac:parameter ac:name=\"language\">java</ac:parameter>"
                + "<ac:plain-text-body><![CDATA[  x < y;\n```\n&copy;\n]]></ac:plain-text-body></ac:structured-macro>");
    assertEquals("````java\n  x < y;\n```\n&copy;\n````\n", result.markdown());
  }

  @Test
  void simpleTablesEscapePipesAndComplexTablesRetainSafeHtml() {
    var simple =
        convert(
            "<table><tbody><tr><th>Name</th><th>Value</th></tr><tr><td>a|b</td><td>2</td></tr></tbody></table>");
    assertEquals("| Name | Value |\n| --- | --- |\n| a&#124;b | 2 |\n", simple.markdown());
    assertTrue(simple.warnings().isEmpty());
    var complex =
        convert(
            "<table onclick=\"bad()\"><tr><td colspan=\"2\"><script>danger</script><p>Text</p></td></tr></table>");
    assertTrue(complex.markdown().contains("colspan=\"2\""));
    assertFalse(complex.markdown().contains("<script>"));
    assertFalse(complex.markdown().contains("onclick"));
    assertFalse(complex.warnings().isEmpty());
    var paragraphs = convert("<table><tr><td><p>First</p><p>Second</p></td></tr></table>");
    assertTrue(paragraphs.markdown().contains("<p>First</p><p>Second</p>"));
    assertFalse(paragraphs.warnings().isEmpty());
    assertTrue(
        MarkdownConverter.render(simple.markdown(), new MarkdownConverter.RenderOptions())
            .storage()
            .contains("<td>a&#124;b</td>"));
  }

  @Test
  void linksAreRemoteAndAssetsAreDeduplicated() {
    var result =
        convert(
            "<p><a href=\"/wiki/display/ENG/Other\">other</a> "
                + "<ac:link ac:anchor=\"part one\"><ri:page ri:content-title=\"Other\"/><ac:plain-text-link-body>Go</ac:plain-text-link-body></ac:link>"
                + "<ac:image ac:alt=\"Photo\"><ri:attachment ri:filename=\"Photo.png\"/></ac:image>"
                + "<ac:link><ri:attachment ri:filename=\"Photo.png\"/></ac:link>"
                + "<img src=\"https://cdn.example/image.png\" alt=\"External\"/></p>");
    assertTrue(result.markdown().contains("[other](https://wiki.example/wiki/display/ENG/Other)"));
    assertTrue(result.markdown().contains("spaceKey=ENG&title=Other#part%20one"));
    assertTrue(result.markdown().contains("![Photo](guide.assets/att9-photo.png)"));
    assertEquals(1, result.assets().size());
    assertTrue(result.assets().get(0).nonImage());
    assertTrue(result.markdown().contains("https://cdn.example/image.png"));
  }

  @Test
  void fallbackPreservesReadableContentAndWarns() {
    var result =
        convert(
            "<ac:layout><ac:layout-section><ac:layout-cell>"
                + "<ac:structured-macro ac:name=\"note\"><ac:rich-text-body><p>Remember</p></ac:rich-text-body></ac:structured-macro>"
                + "<ac:structured-macro ac:name=\"custom\"><ac:rich-text-body><p>Keep me</p></ac:rich-text-body></ac:structured-macro>"
                + "<ac:link><ri:user ri:account-id=\"123\"/></ac:link>"
                + "<ac:image><ri:attachment ri:filename=\"outside.png\"><ri:page ri:content-title=\"Elsewhere\"/></ri:attachment></ac:image>"
                + "</ac:layout-cell></ac:layout-section></ac:layout>");
    assertTrue(result.markdown().contains("Remember"));
    assertTrue(result.markdown().contains("Keep me"));
    assertTrue(result.markdown().contains("Unsupported macro"));
    assertTrue(result.markdown().contains("Cross-page attachment"));
    assertTrue(result.warnings().size() >= 4);
  }

  @Test
  void dangerousUrlsAndExternalEntitiesNeverExecute() {
    assertTrue(convert("<a href=\"javascript:alert(1)\">Bad</a>").markdown().contains("[Bad](#)"));
    assertThrows(
        ImportException.class,
        () -> convert("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///secret'>]><p>&x;</p>"));
    assertThrows(ImportException.class, () -> convert("<p>broken"));
    assertThrows(ImportException.class, () -> convert("<p>&unknown;</p>"));
    assertEquals("", convert("").markdown());
  }

  @Test
  void missingSelectedPageAssetIsAnError() {
    assertThrows(
        ImportException.class,
        () -> convert("<ac:image><ri:attachment ri:filename=\"missing\"/></ac:image>"));
  }

  @Test
  void inlineBackticksAndCaptionsAreRetainedWithWarnings() {
    var result =
        convert(
            "<p><code>a`b</code></p><ac:image><ri:attachment ri:filename=\"Photo.png\"/>"
                + "<ac:caption><p>Caption</p></ac:caption></ac:image>");
    assertTrue(result.markdown().contains("``a`b``"));
    assertTrue(result.markdown().contains("Caption"));
    assertEquals(2, result.warnings().size());
  }
}
