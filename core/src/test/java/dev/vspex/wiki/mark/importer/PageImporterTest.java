package dev.vspex.wiki.mark.importer;

import static org.junit.jupiter.api.Assertions.*;

import dev.vspex.wiki.mark.convert.MarkdownConverter;
import dev.vspex.wiki.mark.importer.PageImportSource.AttachmentSnapshot;
import dev.vspex.wiki.mark.metadata.MetadataParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageImporterTest {
  @TempDir Path project;

  static class Source implements PageImportSource {
    String title = "Guide";
    String storage = "<h1>Authored heading</h1><p>Text</p>";
    List<AttachmentSnapshot> files =
        List.of(new AttachmentSnapshot("att9", "Photo.png", "/download"));
    int downloads;
    boolean failDownload;

    @Override
    public String baseUrl() {
      return "https://wiki.example/wiki";
    }

    @Override
    public PageSnapshot readPage(String id) {
      return new PageSnapshot(
          id, title, "ENG", 3, storage, List.of("Parent 'one' #1"), List.of("demo"));
    }

    @Override
    public List<AttachmentSnapshot> attachments(String id) {
      return files;
    }

    @Override
    public byte[] download(AttachmentSnapshot attachment) {
      downloads++;
      if (failDownload) throw new ImportException("download failed");
      return new byte[] {1, 2, 3};
    }
  }

  private ImportReport run(Source source, String output, boolean overwrite, boolean preview)
      throws IOException {
    return PageImporter.run(
        project, new ImportOptions("7", "", output, overwrite, preview), source);
  }

  @Test
  void defaultAndExplicitPathsAndMetadata() throws Exception {
    var source = new Source();
    source.title = "Guide 'quoted' #2 -->";
    var report = run(source, "engineering/guide.md", false, false);
    assertEquals(project.resolve("documents/wiki/engineering/guide.md"), report.document());
    assertEquals(report.markdown(), Files.readString(report.document()));
    var meta =
        MetadataParser.parseDocument(report.markdown(), new MetadataParser.ParseOptions()).meta();
    assertEquals(source.title, meta.title);
    assertEquals(List.of("Parent 'one' #1"), meta.parents);
    assertTrue(report.markdown().contains("# Authored heading"));
    assertTrue(
        Files.readString(report.document().resolveSibling("guide.confluence.json"))
            .contains("\"version\":3"));
    assertFalse(Files.exists(project.resolve(".gradle/confluence-publish/state.properties")));
    source.title = "CON";
    assertEquals(
        "page-con-7.md", run(source, "", false, false).document().getFileName().toString());
  }

  @Test
  void previewWritesNothingAndNeverDownloads() throws Exception {
    var source = new Source();
    source.storage = "<ac:image><ri:attachment ri:filename=\"Photo.png\"/></ac:image>";
    var report = run(source, "", false, true);
    assertEquals(1, report.assetCount());
    assertEquals(0, source.downloads);
    assertFalse(Files.exists(project.resolve("documents")));
    assertTrue(
        report.files().stream().anyMatch(path -> path.toString().endsWith("att9-photo.png")));
  }

  @Test
  void downloadsOnceAndIncludesNonImagesInMetadata() throws Exception {
    var source = new Source();
    source.storage =
        "<ac:image><ri:attachment ri:filename=\"Photo.png\"/></ac:image>"
            + "<ac:link><ri:attachment ri:filename=\"Photo.png\"/></ac:link>";
    var report = run(source, "guide.md", false, false);
    assertEquals(1, source.downloads);
    assertArrayEquals(
        new byte[] {1, 2, 3},
        Files.readAllBytes(project.resolve("documents/wiki/guide.assets/att9-photo.png")));
    assertEquals(
        List.of("guide.assets/att9-photo.png"),
        MetadataParser.parseDocument(report.markdown(), new MetadataParser.ParseOptions())
            .meta()
            .attachments);
  }

  @Test
  void lossyImportsKeepOriginalStorageAndManifestWarnings() throws Exception {
    var source = new Source();
    source.storage =
        "<ac:structured-macro ac:name=\"custom\"><ac:rich-text-body><p>keep</p></ac:rich-text-body></ac:structured-macro>";
    var report = run(source, "guide.md", false, false);
    assertFalse(report.warnings().isEmpty());
    assertEquals(
        source.storage,
        Files.readString(project.resolve("documents/wiki/guide.confluence-storage.xml")));
    assertTrue(
        Files.readString(project.resolve("documents/wiki/guide.confluence.json"))
            .contains("guide.confluence-storage.xml"));
  }

  @Test
  void collisionsRequireOverwriteAndUnrelatedFilesSurvive() throws Exception {
    var source = new Source();
    var first = run(source, "guide.md", false, false);
    source.storage = "<p>New content</p>";
    assertThrows(ImportException.class, () -> run(source, "guide.md", false, false));
    assertEquals(first.markdown(), Files.readString(first.document()));
    Files.writeString(project.resolve("documents/wiki/unrelated.txt"), "keep");
    var second = run(source, "guide.md", true, false);
    assertTrue(second.markdown().contains("New content"));
    assertEquals("keep", Files.readString(project.resolve("documents/wiki/unrelated.txt")));
  }

  @Test
  void downloadFailureDoesNotCreateIncompleteDocumentOrReplaceExistingFiles() throws Exception {
    var source = new Source();
    var original = run(source, "guide.md", false, false);
    source.storage = "<ac:image><ri:attachment ri:filename=\"Photo.png\"/></ac:image>";
    source.failDownload = true;
    assertThrows(ImportException.class, () -> run(source, "guide.md", true, false));
    assertEquals(original.markdown(), Files.readString(original.document()));
    assertFalse(Files.exists(project.resolve("documents/wiki/guide.assets")));
  }

  @Test
  void unsafePathsAndUnrepresentableMetadataAreRejected() {
    for (String path :
        List.of(
            "../escape.md",
            "C:\\escape.md",
            "\\\\host\\share\\a.md",
            "/absolute.md",
            "a/../../b.md",
            "CON.md",
            "a./b.md",
            "a?.md",
            "file.txt",
            "a//b.md")) {
      assertThrows(ImportException.class, () -> run(new Source(), path, false, false), path);
    }
    var source = new Source();
    source.title = "multi\nline";
    assertThrows(ImportException.class, () -> run(source, "ok.md", false, false));
  }

  @Test
  void generatedAttachmentNamesKeepExtensionsWhenTitlesAreLong() {
    assertTrue(ImportPaths.safeFilename("a".repeat(150) + ".PNG").endsWith(".png"));
    assertEquals("page-con.pdf", ImportPaths.safeFilename("CON.pdf"));
  }

  @Test
  void outputParentFileFailsBeforeDownloading() throws Exception {
    Files.writeString(project.resolve("documents"), "existing file");
    var source = new Source();
    source.storage = "<ac:image><ri:attachment ri:filename=\"Photo.png\"/></ac:image>";
    assertThrows(ImportException.class, () -> run(source, "guide.md", false, false));
    assertEquals(0, source.downloads);
    assertEquals("existing file", Files.readString(project.resolve("documents")));
  }

  @Test
  void selectorsCheckOriginContextAndSupportedUrlShape() {
    String base = "https://wiki.example/wiki";
    assertEquals(
        "7",
        ImportPaths.select(
            base, new ImportOptions("", base + "/spaces/ENG/pages/7/Guide", "", false, true)));
    assertEquals(
        "7",
        ImportPaths.select(
            base,
            new ImportOptions("", base + "/pages/viewpage.action?pageId=7", "", false, true)));
    for (String url :
        List.of(
            "https://evil.example/wiki/pages/7",
            "https://wiki.example/other/pages/7",
            base + "/x/short",
            "https://user:secret@wiki.example/wiki/pages/7")) {
      assertThrows(
          ImportException.class,
          () -> ImportPaths.select(base, new ImportOptions("", url, "", false, true)));
    }
    assertThrows(
        ImportException.class,
        () -> ImportPaths.select(base, new ImportOptions("7", base + "/pages/7", "", false, true)));
    assertThrows(
        ImportException.class,
        () -> ImportPaths.select(base, new ImportOptions("", "", "", false, true)));
  }

  @Test
  void outputSymlinkCannotEscapeProject() throws Exception {
    Path outside = Files.createTempDirectory(project.getParent(), "import-outside-");
    try {
      Files.createDirectories(project.resolve("documents"));
      try {
        Files.createSymbolicLink(project.resolve("documents/wiki"), outside);
      } catch (IOException | UnsupportedOperationException e) {
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symlinks unavailable");
      }
      assertThrows(ImportException.class, () -> run(new Source(), "guide.md", false, false));
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void windowsJunctionCannotRedirectOutputWithinProject() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        System.getProperty("os.name").startsWith("Windows"));
    Files.createDirectories(project.resolve("documents"));
    Path outsideRoot = Files.createDirectory(project.resolve("other"));
    Path junction = project.resolve("documents/wiki");
    var process =
        new ProcessBuilder(
                "cmd.exe", "/c", "mklink", "/J", junction.toString(), outsideRoot.toString())
            .redirectErrorStream(true)
            .start();
    process.getInputStream().readAllBytes();
    org.junit.jupiter.api.Assumptions.assumeTrue(
        process.waitFor() == 0, "Junction creation unavailable");
    try {
      assertThrows(ImportException.class, () -> run(new Source(), "guide.md", false, false));
      assertFalse(Files.exists(outsideRoot.resolve("guide.md")));
    } finally {
      Files.delete(junction);
    }
  }

  @Test
  void commitFailureRestoresOverwrittenFiles() throws Exception {
    Path first = project.resolve("first.json"), last = project.resolve("last.md");
    Files.writeString(first, "old first");
    Files.writeString(last, "old last");
    var files = new LinkedHashMap<Path, byte[]>();
    files.put(first, "new first".getBytes());
    files.put(last, "new last".getBytes());
    var transaction =
        new OutputTransaction(
            (from, to) -> {
              if (from.getFileName().toString().equals("new-1"))
                throw new IOException("injected commit failure");
              Files.move(from, to);
            });
    assertThrows(IOException.class, () -> transaction.write(project, files, true));
    assertEquals("old first", Files.readString(first));
    assertEquals("old last", Files.readString(last));
    try (var paths = Files.list(project)) {
      assertEquals(2, paths.count());
    }
  }

  @Test
  void rollbackFailureRetainsRecoverableBackups() throws Exception {
    Path file = project.resolve("guide.md");
    Files.writeString(file, "old");
    var files = new LinkedHashMap<Path, byte[]>();
    files.put(file, "new".getBytes());
    var transaction =
        new OutputTransaction(
            (from, to) -> {
              if (!from.equals(file)) throw new IOException("injected failure");
              Files.move(from, to);
            });
    var error = assertThrows(ImportException.class, () -> transaction.write(project, files, true));
    assertTrue(error.getMessage().contains("recovery files retained"));
    try (var paths = Files.walk(project)) {
      Path backup =
          paths
              .filter(path -> path.getFileName().toString().equals("backup-0"))
              .findFirst()
              .orElseThrow();
      assertEquals("old", Files.readString(backup));
    }
  }

  private ImportReport runLossless(Source source, String output, boolean overwrite, boolean verify)
      throws IOException {
    return PageImporter.run(
        project,
        new ImportOptions(
            "7", "", output, overwrite, false, true, verify, "", List.of(), "", "", false),
        source);
  }

  private static String compile(String markdown) {
    var body = MetadataParser.parseDocument(markdown, new MetadataParser.ParseOptions()).body();
    return MarkdownConverter.render(body, new MarkdownConverter.RenderOptions()).storage();
  }

  private static Source richSource() {
    var source = new Source();
    source.storage =
        "<ac:layout><ac:layout-section ac:type=\"two_equal\"><ac:layout-cell>"
            + "<h1>Example release</h1>"
            + "<ac:structured-macro ac:name=\"html\"><ac:plain-text-body><![CDATA[<link rel=\"stylesheet\" href=\"/download/attachments/234567/site.css\">\n"
            + "\n"
            + "<script src=\"/download/attachments/234567/site.js\"></script>]]></ac:plain-text-body></ac:structured-macro>"
            + "<ac:structured-macro ac:name=\"toc\"/>"
            + "<p><ac:structured-macro ac:name=\"status\"><ac:parameter ac:name=\"title\">DONE</ac:parameter></ac:structured-macro></p>"
            + "<ac:structured-macro ac:name=\"tip\"><ac:rich-text-body><p>Read this first</p></ac:rich-text-body></ac:structured-macro>"
            + "<ac:structured-macro ac:name=\"jira\"><ac:parameter ac:name=\"server\">Jira Prod</ac:parameter><ac:parameter ac:name=\"key\">PRJ-1</ac:parameter></ac:structured-macro>"
            + "<p>Signed off by <ac:link><ri:user ri:account-id=\"abc123\"/></ac:link></p>"
            + "<p><span style=\"color:var(--ds-text-accent-blue);font-size:14px\">Colored</span></p>"
            + "<ac:structured-macro ac:name=\"code\"><ac:parameter ac:name=\"language\">java</ac:parameter><ac:plain-text-body><![CDATA[int a = 1;\n"
            + "\n"
            + "int b = 2;\n"
            + "]]></ac:plain-text-body></ac:structured-macro>"
            + "<table><tbody><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></tbody></table>"
            + "<ul><li>one<ul><li>nested</li></ul></li><li>two</li></ul>"
            + "<p><ac:image><ri:attachment ri:filename=\"Image One.png\"/></ac:image></p>"
            + "<p><ac:image><ri:attachment ri:filename=\"diagram-\u00e9\u00e8.png\"/></ac:image></p>"
            + "<p><ac:image><ri:url ri:value=\"https://cdn.example/i.png\"/></ac:image></p>"
            + "</ac:layout-cell></ac:layout-section></ac:layout>";
    source.files =
        List.of(
            new AttachmentSnapshot("att1", "Image One.png", "/download/1"),
            new AttachmentSnapshot("att2", "diagram-\u00e9\u00e8.png", "/download/2"));
    return source;
  }

  @Test
  void losslessRoundTripsComplexStorageByteForByte() throws Exception {
    var source = richSource();
    var report = runLossless(source, "guide.md", false, true);
    String file = Files.readString(report.document());
    assertEquals(report.markdown(), file);
    assertTrue(file.contains("<!-- Space: ENG -->\n"), file);
    assertTrue(file.contains("<!-- Parent: Parent 'one' #1 -->\n"), file);
    assertTrue(file.contains("<!-- Title: Guide -->\n"), file);
    assertTrue(file.contains("<!-- Label: demo -->\n"), file);
    assertTrue(file.contains("<!-- Attachment: guide.assets/Image One.png -->\n"), file);
    assertTrue(file.contains("<!-- Attachment: guide.assets/diagram-\u00e9\u00e8.png -->\n"), file);
    assertTrue(file.contains("<!-- wiki-mark-raw-begin -->\n"), file);
    assertEquals(source.storage, compile(file));
    assertEquals(2, source.downloads);
    assertTrue(Files.exists(project.resolve("documents/wiki/guide.assets/Image One.png")));
    assertTrue(
        Files.exists(project.resolve("documents/wiki/guide.assets/diagram-\u00e9\u00e8.png")));
    assertFalse(report.warnings().isEmpty());
    assertTrue(Files.exists(project.resolve("documents/wiki/guide.confluence.json")));
    assertTrue(Files.exists(project.resolve("documents/wiki/guide.confluence-storage.xml")));
  }

  @Test
  void losslessBodiesStartingWithTextOrCommentRoundTrip() throws Exception {
    var text = new Source();
    text.storage = "Plain leading text\n<p>after</p>";
    assertEquals(text.storage, compile(runLossless(text, "text.md", false, true).markdown()));
    var comment = new Source();
    comment.storage = "<!-- an ordinary comment -->\n<p>after</p>";
    assertEquals(
        comment.storage, compile(runLossless(comment, "comment.md", false, true).markdown()));
  }

  @Test
  void losslessRetargetChangesHeadersNotBody() throws Exception {
    var source = richSource();
    var plain = runLossless(source, "a.md", false, false);
    var retargeted =
        PageImporter.run(
            project,
            new ImportOptions(
                "7",
                "",
                "b.md",
                false,
                false,
                true,
                true,
                "NEW",
                List.of("Top", "Sub"),
                "New title",
                "",
                false),
            source);
    assertTrue(retargeted.markdown().contains("<!-- Space: NEW -->\n"));
    assertTrue(retargeted.markdown().contains("<!-- Parent: Top -->\n"));
    assertTrue(retargeted.markdown().contains("<!-- Parent: Sub -->\n"));
    assertTrue(retargeted.markdown().contains("<!-- Title: New title -->\n"));
    assertEquals(compile(plain.markdown()), compile(retargeted.markdown()));
    assertEquals(source.storage, compile(retargeted.markdown()));
  }

  @Test
  void losslessPreviewWritesNothingAndRequiresOverwrite() throws Exception {
    var source = richSource();
    var preview =
        PageImporter.run(
            project,
            new ImportOptions(
                "7", "", "guide.md", false, true, true, false, "", List.of(), "", "", false),
            source);
    assertFalse(Files.exists(project.resolve("documents")));
    assertEquals(0, source.downloads);
    assertTrue(preview.markdown().contains("<!-- wiki-mark-raw-begin -->\n"));
    runLossless(source, "guide.md", false, false);
    var error =
        assertThrows(ImportException.class, () -> runLossless(source, "guide.md", false, false));
    assertTrue(error.getMessage().contains("--overwrite"), error.getMessage());
  }

  @Test
  void losslessMissingAttachmentFailsLoudly() {
    var source = new Source();
    source.storage = "<p><ac:image><ri:attachment ri:filename=\"Ghost.png\"/></ac:image></p>";
    var error =
        assertThrows(ImportException.class, () -> runLossless(source, "guide.md", false, false));
    assertTrue(error.getMessage().contains("Ghost.png"), error.getMessage());
  }

  @Test
  void losslessCaseOnlyCollisionFailsExplicitly() {
    var source = new Source();
    source.storage =
        "<p><ac:image><ri:attachment ri:filename=\"Photo.png\"/></ac:image>"
            + "<ac:image><ri:attachment ri:filename=\"PHOTO.png\"/></ac:image></p>";
    source.files =
        List.of(
            new AttachmentSnapshot("a1", "Photo.png", "/d1"),
            new AttachmentSnapshot("a2", "PHOTO.png", "/d2"));
    var error =
        assertThrows(ImportException.class, () -> runLossless(source, "guide.md", false, false));
    assertTrue(error.getMessage().contains("collide"), error.getMessage());
  }

  @Test
  void losslessRejectsUnsafeFilenamesAndMarkersAndHeaders() {
    var traversal = new Source();
    traversal.storage = "<p><ac:image><ri:attachment ri:filename=\"a/b.png\"/></ac:image></p>";
    traversal.files = List.of(new AttachmentSnapshot("a1", "a/b.png", "/d1"));
    assertThrows(ImportException.class, () -> runLossless(traversal, "guide.md", false, false));
    var marker = new Source();
    marker.storage = "<p>x</p>\n<!-- wiki-mark-raw-begin -->\n<p>y</p>";
    assertThrows(ImportException.class, () -> runLossless(marker, "guide.md", false, false));
    var header = new Source();
    header.title = "sneaky --> comment";
    assertThrows(ImportException.class, () -> runLossless(header, "guide.md", false, false));
  }

  @Test
  void losslessHonorsCustomWikiDirAndSubfolders() throws Exception {
    var source = new Source();
    source.storage = "<p>x</p>";
    var report =
        PageImporter.run(
            project,
            new ImportOptions(
                "7",
                "",
                "releases/2026.md",
                false,
                false,
                true,
                true,
                "",
                List.of(),
                "",
                "documentation/wiki",
                false),
            source);
    assertEquals(project.resolve("documentation/wiki/releases/2026.md"), report.document());
    assertEquals(source.storage, compile(report.markdown()));
    assertFalse(Files.exists(project.resolve("documents")));
  }

  @Test
  void verificationFailureReportsFirstOffset() {
    var error =
        assertThrows(
            ImportException.class, () -> PageImporter.checkCompiled("<p>aXc</p>", "<p>aYc</p>"));
    assertTrue(error.getMessage().contains("offset 4"), error.getMessage());
    assertTrue(error.getMessage().contains("compiled"), error.getMessage());
  }

  @Test
  void losslessPreservesEveryPayloadCharacter() throws Exception {
    int index = 0;
    for (String body :
        List.of(
            "",
            "text\r\nnext\r\n",
            "a\rb\nc\r\nd",
            "\n\n <p>é😀 &amp;</p>\n",
            "<ac:plain-text-body><![CDATA[a\r\n\r\n \t\r\nb\r]]></ac:plain-text-body>",
            "<!-- Title: literal -->\n<p>end</p>",
            "(empty page)")) {
      var source = new Source();
      source.storage = body;
      var result = runLossless(source, "case-" + index++ + ".md", false, true);
      assertEquals(body, compile(Files.readString(result.document())));
    }
  }

  @Test
  void foreignAttachmentsStayRemoteAndQueryUrlsResolve() throws Exception {
    var source = new Source();
    source.storage =
        "<ac:image><ri:attachment ri:filename = 'remote.png'><ri:page ri:content-title='Other'/></ri:attachment></ac:image>"
            + "<ac:structured-macro ac:name='view-file'><ri:attachment ri:filename = 'Photo.png'/></ac:structured-macro>"
            + "<a href='/download/attachments/7/Photo.png?version=2&amp;download=true#anchor'>file</a>";
    var result = runLossless(source, "assets.md", false, true);
    assertEquals(1, source.downloads);
    assertEquals(source.storage, compile(result.markdown()));
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("source page ID")));
  }

  @Test
  void foreignSameNamedAttachmentDoesNotDownloadLocalFile() throws Exception {
    var source = new Source();
    source.storage =
        "<ac:image><ri:attachment ri:filename='Photo.png'><ri:page ri:content-title='Other'/></ri:attachment></ac:image>";
    runLossless(source, "foreign.md", false, true);
    assertEquals(0, source.downloads);
  }

  @Test
  void reservedAssetNameFailsBeforeDownload() {
    var source = new Source();
    source.storage = "<ri:attachment ri:filename='CON.png'/>";
    source.files = List.of(new AttachmentSnapshot("a", "CON.png", "/download"));
    assertThrows(ImportException.class, () -> runLossless(source, "reserved.md", false, true));
    assertEquals(0, source.downloads);
  }

  @Test
  void markdownRejectsLosslessOptionsBeforeReading() {
    var source =
        new Source() {
          @Override
          public PageSnapshot readPage(String id) {
            throw new AssertionError("must not fetch");
          }
        };
    assertThrows(
        ImportException.class,
        () ->
            PageImporter.run(
                project,
                new ImportOptions(
                    "7", "", "a.md", false, true, false, true, "", List.of(), "", "", false),
                source));
  }

  @Test
  void manifestKeepsSourceSpaceWhenRetargetedToRoot() throws Exception {
    var source = new Source();
    var result =
        PageImporter.run(
            project,
            new ImportOptions(
                "7",
                "",
                "root.md",
                false,
                false,
                true,
                true,
                "DEST",
                List.of(""),
                "Copy",
                "",
                false),
            source);
    var parsed = MetadataParser.parseDocument(result.markdown(), new MetadataParser.ParseOptions());
    assertEquals(List.of(), parsed.meta().parents);
    assertEquals("DEST", parsed.meta().space);
    var manifest =
        (java.util.Map<?, ?>)
            dev.vspex.wiki.mark.json.Json.parse(
                Files.readString(result.document().resolveSibling("root.confluence.json")));
    assertEquals("ENG", manifest.get("sourceSpace"));
    assertEquals("lossless", manifest.get("mode"));
  }
}
