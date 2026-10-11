package com.wiki.mark.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wiki.mark.model.Meta;
import com.wiki.mark.model.MetaException;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Metadata parsing tests.
 *
 * <p>There is no pytest file for this area (upstream covers it only indirectly via {@code
 * test_cli.py}), so these cases were authored from the README "Finding pages" section plus the
 * reference code, and cross-checked byte-for-byte against the Python {@code parse_document} via the
 * vectors harness.
 */
class MetadataParserTest {

  private static MetadataParser.ParseResult parse(String doc) {
    return MetadataParser.parseDocument(doc, new MetadataParser.ParseOptions());
  }

  private static MetadataParser.ParseResult parse(
      String doc, Consumer<MetadataParser.ParseOptions> opts) {
    MetadataParser.ParseOptions o = new MetadataParser.ParseOptions();
    opts.accept(o);
    return MetadataParser.parseDocument(doc, o);
  }

  @Test
  void basicHeaders() {
    MetadataParser.ParseResult r =
        parse(
            "<!-- Space: DOC -->\n"
                + "<!-- Parent: Futures Applications -->\n"
                + "<!-- Title: Futures Settlement Pricing -->\n"
                + "<!-- Label: docs -->\n"
                + "<!-- Label: wiki -->\n"
                + "<!-- Attachment: diagrams/a.png -->\n"
                + "\n"
                + "Body text here.\n");
    Meta m = r.meta();
    assertEquals("DOC", m.space);
    assertEquals("Futures Settlement Pricing", m.title);
    assertEquals(List.of("Futures Applications"), m.parents);
    assertTrue(m.declaredParents);
    assertEquals(List.of("docs", "wiki"), m.labels);
    assertEquals(List.of("diagrams/a.png"), m.attachments);
    assertEquals("page", m.type);
    assertEquals("full-width", m.contentAppearance);
    assertEquals("\nBody text here.\n", r.body());
    assertTrue(r.warnings().isEmpty());
  }

  @Test
  void allScalarHeaders() {
    MetadataParser.ParseResult r =
        parse(
            "<!-- Space: S -->\n<!-- Title: T -->\n<!-- Type: blogpost -->\n"
                + "<!-- Layout: article -->\n<!-- Sidebar: side stuff -->\n"
                + "<!-- Emoji: rocket -->\n<!-- Order: 7 -->\n"
                + "<!-- Image-Align: CENTER -->\n<!-- Image-Width: 600 -->\n"
                + "<!-- Image-Height: 400 -->\n<!-- Content-Appearance: fixed -->\n"
                + "<!-- Synchronized: yes -->\n<!-- Folder: f1 -->\n"
                + "<!-- Property: k=v -->\n<!-- Parent: P -->\n\nBody.\n");
    Meta m = r.meta();
    assertEquals("blogpost", m.type);
    assertEquals("article", m.layout);
    assertEquals("side stuff", m.sidebar);
    assertEquals("rocket", m.emoji);
    assertEquals(Integer.valueOf(7), m.order);
    assertEquals("center", m.imageAlign);
    assertEquals("600", m.imageWidth);
    assertEquals("400", m.imageHeight);
    assertEquals("fixed", m.contentAppearance);
    assertEquals(Boolean.TRUE, m.synchronizedFlag);
    assertEquals(List.of("f1"), m.folders);
    assertEquals("v", m.properties.get("k"));
  }

  @Test
  void keysAreCaseInsensitive() {
    MetadataParser.ParseResult r = parse("<!-- sPaCe: X -->\n<!-- tItLe: Y -->\n\nBody.\n");
    assertEquals("X", r.meta().space);
    assertEquals("Y", r.meta().title);
  }

  @Test
  void nearMissHeaderIsAnError() {
    MetaException e =
        assertThrows(
            MetaException.class, () -> parse("<!-- Space: S -->\n<!-- Titel: T -->\n\nBody.\n"));
    assertEquals(
        "unknown header 'Titel' in '<!-- Titel: T -->' -- did you mean 'Title'?", e.getMessage());
  }

  @Test
  void transposedHeaderIsAnError() {
    MetaException e =
        assertThrows(
            MetaException.class, () -> parse("<!-- Space: S -->\n<!-- Tilte: T -->\n\nBody.\n"));
    assertEquals(
        "unknown header 'Tilte' in '<!-- Tilte: T -->' -- did you mean 'Title'?", e.getMessage());
  }

  @Test
  void farMissCommentStaysInBody() {
    MetadataParser.ParseResult r =
        parse("<!-- Space: S -->\n<!-- Title: T -->\n<!-- TODO: rewrite me -->\n\nBody.\n");
    assertEquals("S", r.meta().space);
    assertEquals("<!-- TODO: rewrite me -->\n\nBody.\n", r.body());
    assertTrue(r.warnings().isEmpty());
  }

  @Test
  void strandedHeaderWarnsAndIsIgnored() {
    MetadataParser.ParseResult r =
        parse("<!-- Space: S -->\n<!-- Title: T -->\n\nBody.\n<!-- Space: LATE -->\n");
    assertEquals("S", r.meta().space);
    assertEquals(1, r.warnings().size());
    assertEquals(
        // Line number is relative to the body (headers already stripped), not the document.
        "line 3: header 'Space' is below page content and was ignored"
            + " (headers must lead the document)",
        r.warnings().get(0));
  }

  @Test
  void strandedHeaderInsideFenceIsIgnored() {
    MetadataParser.ParseResult r =
        parse("<!-- Space: S -->\n<!-- Title: T -->\n\nBody.\n\n```\n<!-- Space: LATE -->\n```\n");
    assertTrue(r.warnings().isEmpty());
  }

  @Test
  void frontMatterBasic() {
    MetadataParser.ParseResult r =
        parse(
            "---\nspace: TEST\ntitle: FM Title\nparents: [Guide, Sub]\nlabels:\n  - docs\n"
                + "  - wiki\nattachments: diag.png\n---\n\nBody here.\n",
            o -> o.filename = "fm.md");
    Meta m = r.meta();
    assertEquals("TEST", m.space);
    assertEquals("FM Title", m.title);
    assertEquals(List.of("Guide", "Sub"), m.parents);
    assertTrue(m.declaredParents);
    assertEquals(List.of("docs", "wiki"), m.labels);
    assertEquals(List.of("diag.png"), m.attachments);
    assertEquals("\nBody here.\n", r.body());
    assertTrue(r.warnings().isEmpty());
  }

  @Test
  void htmlHeadersOverrideScalarsAndAppendToLists() {
    MetadataParser.ParseResult r =
        parse(
            "---\nspace: FM-SPACE\ntitle: FM Title\nparents: [P1]\nlabels: [l1]\n"
                + "content-appearance: fixed\n---\n<!-- Space: H-SPACE -->\n"
                + "<!-- Parent: P2 -->\n<!-- Label: l2 -->\n\nBody.\n");
    Meta m = r.meta();
    assertEquals("H-SPACE", m.space);
    assertEquals("FM Title", m.title);
    assertEquals(List.of("P1", "P2"), m.parents);
    assertEquals(List.of("l1", "l2"), m.labels);
    assertEquals("fixed", m.contentAppearance);
  }

  @Test
  void unknownFrontMatterKeysWarnWithFilename() {
    MetadataParser.ParseResult r =
        parse(
            "---\nspace: S\nbogus-key: 1\nanother: 2\n---\n\nBody.\n", o -> o.filename = "page.md");
    assertEquals(
        List.of(
            "page.md: front matter key 'another' is not read by mark and was ignored",
            "page.md: front matter key 'bogus-key' is not read by mark and was ignored"),
        r.warnings());
  }

  @Test
  void frontMatterNestedListIsAnError() {
    MetaException e =
        assertThrows(MetaException.class, () -> parse("---\nlabels:\n  - - deep\n---\n\nBody.\n"));
    assertEquals(
        "decode YAML front matter: nested lists are not supported in front matter", e.getMessage());
  }

  @Test
  void frontMatterBadShapeIsAnError() {
    MetaException e =
        assertThrows(MetaException.class, () -> parse("---\njust a line\n---\n\nBody.\n"));
    assertEquals(
        "decode YAML front matter: expected 'key: value' in front matter, got 'just a line'",
        e.getMessage());
  }

  @Test
  void frontMatterScalarPropertiesIsAnError() {
    MetaException e =
        assertThrows(MetaException.class, () -> parse("---\nproperties: nope\n---\n\nBody.\n"));
    assertEquals("properties must be a mapping, got 'nope'", e.getMessage());
  }

  @Test
  void frontMatterDotCloses() {
    MetadataParser.ParseResult r = parse("---\nspace: S\n...\nBody.\n");
    assertEquals("S", r.meta().space);
    assertEquals("Body.\n", r.body());
  }

  @Test
  void unclosedFrontMatterIsNotFrontMatter() {
    String doc = "---\nspace: S\n\nBody.\n";
    MetadataParser.ParseResult r = parse(doc);
    assertEquals("", r.meta().space);
    assertEquals(doc, r.body());
  }

  @Test
  void titleFromH1() {
    MetadataParser.ParseResult r =
        parse("<!-- Space: S -->\n\n# Hello *World*\n\nBody.\n", o -> o.titleFromH1 = true);
    assertEquals("Hello World", r.meta().title);
  }

  @Test
  void titleFromH1SkipsFencedCode() {
    MetadataParser.ParseResult r =
        parse(
            "<!-- Space: S -->\n\n```\n# Not a title\n```\n\nReal body.\n",
            o -> o.titleFromH1 = true);
    assertEquals("", r.meta().title);
  }

  @Test
  void titleFromSetextH1() {
    MetadataParser.ParseResult r =
        parse("<!-- Space: S -->\n\nBig Title\n=========\n\nBody.\n", o -> o.titleFromH1 = true);
    assertEquals("Big Title", r.meta().title);
  }

  @Test
  void titleFromFilenameTitleCases() {
    MetadataParser.ParseResult r =
        parse(
            "<!-- Space: S -->\n\nBody without heading.\n",
            o -> {
              o.titleFromFilename = true;
              o.filename = "sub/dir_name-test.md";
            });
    assertEquals("Dir Name Test", r.meta().title);
  }

  @Test
  void titleAppendHashMatchesReference() {
    String doc = "<!-- Space: DOC -->\n<!-- Parent: P -->\n<!-- Title: T -->\n\nBody.\n";
    // Exact value pinned from the reference Python (sha256("P/DOC/T")[:8]).
    assertEquals("T - 4f28110d", parse(doc, o -> o.titleAppendHash = true).meta().title);
  }

  @Test
  void optionFallbacksApplyWhenDocLacksThem() {
    MetadataParser.ParseResult r =
        parse(
            "<!-- Title: T -->\n\nBody.\n",
            o -> {
              o.space = "OPT";
              o.contentAppearance = "fixed";
            });
    assertEquals("OPT", r.meta().space);
    assertEquals("fixed", r.meta().contentAppearance);
  }

  @Test
  void contentAppearanceHeaderIsLenient() {
    MetadataParser.ParseResult r =
        parse(
            "<!-- Space: S -->\n<!-- Title: T -->\n<!-- Content-Appearance: sideways -->\n\nBody.\n");
    assertEquals("full-width", r.meta().contentAppearance);
  }

  @Test
  void typeHeaderIsNotValidated() {
    MetadataParser.ParseResult r =
        parse("<!-- Space: S -->\n<!-- Title: T -->\n<!-- Type: banana -->\n\nBody.\n");
    assertEquals("banana", r.meta().type);
  }

  @Test
  void badOrderHeaderIsAnError() {
    MetaException e =
        assertThrows(
            MetaException.class,
            () -> parse("<!-- Space: S -->\n<!-- Title: T -->\n<!-- Order: 3x -->\n\nBody.\n"));
    assertEquals("Order header must be a whole number, got '3x'", e.getMessage());
  }

  @Test
  void fractionalFrontMatterOrderIsAnError() {
    MetaException e =
        assertThrows(
            MetaException.class,
            () -> parse("---\norder: 4.5\n---\n<!-- Space: S -->\n<!-- Title: T -->\n\nBody.\n"));
    assertEquals("order must be a whole number, got 4.5", e.getMessage());
  }

  @Test
  void synchronizedAcceptsWordForms() {
    MetadataParser.ParseResult r =
        parse("<!-- Space: S -->\n<!-- Title: T -->\n<!-- Synchronized: 0 -->\n\nBody.\n");
    assertEquals(Boolean.FALSE, r.meta().synchronizedFlag);
  }

  @Test
  void badSynchronizedIsAnError() {
    MetaException e =
        assertThrows(
            MetaException.class,
            () ->
                parse(
                    "<!-- Space: S -->\n<!-- Title: T -->\n<!-- Synchronized: maybe -->\n\nBody.\n"));
    assertEquals("Synchronized header must be true or false, got 'maybe'", e.getMessage());
  }

  @Test
  void badPropertyHeadersAreErrors() {
    MetaException noEquals =
        assertThrows(
            MetaException.class,
            () ->
                parse(
                    "<!-- Space: S -->\n<!-- Title: T -->\n<!-- Property: novalue -->\n\nBody.\n"));
    assertEquals(
        "Property header must be written as key=value, got 'novalue'", noEquals.getMessage());
    MetaException noName =
        assertThrows(
            MetaException.class,
            () -> parse("<!-- Space: S -->\n<!-- Title: T -->\n<!-- Property: =v -->\n\nBody.\n"));
    assertEquals("Property header has no name: '=v'", noName.getMessage());
  }

  @Test
  void includeHeaderStaysInBody() {
    MetadataParser.ParseResult r =
        parse("<!-- Space: S -->\n<!-- Title: T -->\n<!-- Include: other.md -->\n\nBody.\n");
    assertEquals("<!-- Include: other.md -->\n\nBody.\n", r.body());
  }

  @Test
  void rawHtmlBeforeHeadersIsSkipped() {
    MetadataParser.ParseResult r =
        parse(
            "<ac:structured-macro ac:name=\"toc\" />\n<!-- Space: S -->\n<!-- Title: T -->\n\nBody.\n");
    assertEquals("S", r.meta().space);
    assertEquals("T", r.meta().title);
  }

  @Test
  void optionParentsArePrependedAndBlanksFiltered() {
    MetadataParser.ParseResult r =
        parse(
            "<!-- Space: S -->\n<!-- Title: T -->\n<!-- Parent: Doc -->\n\nBody.\n",
            o -> o.parents = new java.util.ArrayList<>(List.of("CLI1", "", "CLI2")));
    assertEquals(List.of("CLI1", "CLI2", "Doc"), r.meta().parents);
  }

  @Test
  void titleAndSpaceAreStrippedOfSpaces() {
    MetadataParser.ParseResult r =
        parse("<!-- Space: S -->\n<!-- Title:   Spaced Title   -->\n\nBody.\n");
    assertEquals("Spaced Title", r.meta().title);
    assertNull(r.meta().order);
    assertFalse(r.meta().declaredParents);
  }

  @Test
  void frontMatterCanBeDisabled() {
    String doc = "---\nspace: S\n---\n<!-- Title: T -->\n\nBody.\n";
    MetadataParser.ParseResult r = parse(doc, o -> o.frontMatter = false);
    // With front matter off, the leading "---" line ends the header run immediately,
    // so even the HTML Title below it is never parsed and the body is untouched.
    assertEquals("", r.meta().space);
    assertEquals("", r.meta().title);
    assertEquals(doc, r.body());
  }

  @Test
  void frontMatterSidebarForcesArticle() {
    MetadataParser.ParseResult r =
        parse("---\nsidebar: fm side\n---\n<!-- Space: S -->\n<!-- Title: T -->\n\nBody.\n");
    assertEquals("article", r.meta().layout);
    assertEquals("fm side", r.meta().sidebar);
  }

  @Test
  void frontMatterPropertiesMapping() {
    MetadataParser.ParseResult r = parse("---\nproperties:\n  k1: v1\n  k2: 5\n---\n\nBody.\n");
    assertEquals("v1", r.meta().properties.get("k1"));
    assertEquals(5L, r.meta().properties.get("k2"));
  }
}
