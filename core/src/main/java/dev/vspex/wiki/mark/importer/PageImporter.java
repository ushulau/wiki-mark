package dev.vspex.wiki.mark.importer;

import dev.vspex.wiki.mark.convert.MarkdownConverter;
import dev.vspex.wiki.mark.json.Json;
import dev.vspex.wiki.mark.metadata.MetadataParser;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches an editable snapshot without modifying Confluence or publishing state. */
public final class PageImporter {
  private PageImporter() {}

  /** Default wiki root beneath the project directory. */
  public static final String DEFAULT_WIKI_DIR = "documents/wiki";

  private static final Pattern DOWNLOAD_ATTACHMENT =
      Pattern.compile("/download/attachments/([0-9]+)/([^\"'\\s<>]+)");
  private static final Pattern ATTR_ENTITY =
      Pattern.compile("&#([0-9]+);|&#x([0-9a-fA-F]+);|&(amp|lt|gt|quot|apos);");

  private record AssetRef(PageImportSource.AttachmentSnapshot attachment, String relativePath) {}

  private record Warn(String code, String message) {}

  public static ImportReport run(
      Path projectDirectory, ImportOptions options, PageImportSource source) throws IOException {
    if (!options.lossless()
        && (options.verify()
            || !options.space().isEmpty()
            || !options.parents().isEmpty()
            || !options.title().isEmpty())) {
      throw new ImportException("--verify, --space, --parent and --title require --lossless");
    }
    Path project = projectDirectory.toAbsolutePath().normalize();
    Path root = project.resolve(options.wikiDir().isEmpty() ? DEFAULT_WIKI_DIR : options.wikiDir());
    String id = ImportPaths.select(source.baseUrl(), options);
    if (!options.output().isEmpty())
      ImportPaths.check(project, ImportPaths.output(root, options.output()));
    var page = source.readPage(id);
    if (!page.id().equals(id)) throw new ImportException("Server returned a different page ID");
    String relative =
        options.output().isEmpty()
            ? ImportPaths.safeName(page.title()) + "-" + id + ".md"
            : options.output();
    Path document = ImportPaths.output(root, relative);
    ImportPaths.check(project, document);
    String name = document.getFileName().toString();
    String stem = name.substring(0, name.length() - 3);
    if (options.lossless()) {
      return runLossless(project, options, source, id, page, document, stem);
    }
    var converted =
        StorageToMarkdownConverter.convert(
            source.baseUrl(), page, source.attachments(id), stem + ".assets");
    List<String> nonImage =
        converted.assets().stream()
            .filter(StorageToMarkdownConverter.Asset::nonImage)
            .map(StorageToMarkdownConverter.Asset::relativePath)
            .toList();
    String markdown = metadata(page, nonImage) + converted.markdown();
    validateMetadata(markdown, page, nonImage);
    List<AssetRef> assets =
        converted.assets().stream()
            .map(asset -> new AssetRef(asset.attachment(), asset.relativePath()))
            .toList();
    List<Warn> warnings =
        converted.warnings().stream().map(w -> new Warn(w.code(), w.message())).toList();
    return commit(project, options, source, id, page, document, stem, markdown, assets, warnings);
  }

  private static ImportReport runLossless(
      Path project,
      ImportOptions options,
      PageImportSource source,
      String id,
      PageImportSource.PageSnapshot page,
      Path document,
      String stem)
      throws IOException {
    String space = options.space().isEmpty() ? page.space() : options.space();
    List<String> parents =
        options.parents().isEmpty()
            ? page.ancestors()
            : options.parents().stream().filter(p -> !p.isEmpty()).toList();
    String title = options.title().isEmpty() ? page.title() : options.title();
    var inventory = StorageToMarkdownConverter.parse(page.storage());
    List<AssetRef> assets =
        losslessAssets(page, source.attachments(id), stem + ".assets", inventory);
    List<Warn> warnings = losslessInventory(page, space, inventory);
    List<String> attachmentPaths = assets.stream().map(AssetRef::relativePath).toList();
    String markdown =
        losslessDocument(space, parents, title, page.labels(), attachmentPaths, page.storage());
    validateLosslessMetadata(markdown, space, parents, title, page.labels(), attachmentPaths);
    if (options.verify()) {
      var body = MetadataParser.parseDocument(markdown, new MetadataParser.ParseOptions()).body();
      checkCompiled(
          MarkdownConverter.render(body, new MarkdownConverter.RenderOptions()).storage(),
          page.storage());
    }
    return commit(project, options, source, id, page, document, stem, markdown, assets, warnings);
  }

  private static ImportReport commit(
      Path project,
      ImportOptions options,
      PageImportSource source,
      String id,
      PageImportSource.PageSnapshot page,
      Path document,
      String stem,
      String markdown,
      List<AssetRef> assets,
      List<Warn> warnings)
      throws IOException {
    Path manifest = document.resolveSibling(stem + ".confluence.json");
    Path storage = document.resolveSibling(stem + ".confluence-storage.xml");
    Map<Path, byte[]> files = new LinkedHashMap<>();
    for (var asset : assets) files.put(document.getParent().resolve(asset.relativePath()), null);
    if (!warnings.isEmpty()) files.put(storage, bytes(page.storage()));
    Map<String, Object> provenance = new LinkedHashMap<>();
    provenance.put(
        "sourceUrl",
        source.baseUrl().replaceAll("/+$", "") + "/pages/viewpage.action?pageId=" + id);
    provenance.put("pageId", id);
    provenance.put("version", page.version());
    provenance.put("sourceSpace", page.space());
    provenance.put("storageHash", dev.vspex.wiki.mark.sync.ContentHash.contentHash(page.storage()));
    provenance.put("mode", options.lossless() ? "lossless" : "markdown");
    provenance.put(
        "warnings",
        warnings.stream()
            .map(w -> new java.util.TreeMap<>(Map.of("code", w.code(), "message", w.message())))
            .toList());
    provenance.put("storageFile", warnings.isEmpty() ? null : storage.getFileName().toString());
    provenance.put(
        "assets",
        assets.stream()
            .map(
                a ->
                    new java.util.TreeMap<>(
                        Map.of(
                            "id",
                            a.attachment().id(),
                            "filename",
                            a.attachment().filename(),
                            "path",
                            a.relativePath())))
            .toList());
    files.put(manifest, bytes(Json.write(provenance) + "\n"));
    files.put(document, bytes(markdown)); // The readable entry point is always committed last.
    for (Path path : files.keySet()) {
      ImportPaths.check(project, path);
      if (Files.exists(path) && (!options.overwrite() || !Files.isRegularFile(path))) {
        throw new ImportException(
            "Output already exists: " + path + " (use --overwrite to replace files)");
      }
    }
    if (!options.preview()) {
      Path downloadStage = Files.createTempDirectory(project, ".confluence-download-");
      try {
        long total = 0;
        Map<Path, Path> downloads = new LinkedHashMap<>();
        for (var asset : assets) {
          Path temporary = downloadStage.resolve("asset-" + downloads.size());
          source.downloadTo(asset.attachment(), temporary, 200L * 1024 * 1024 - total);
          total += Files.size(temporary);
          if (Files.size(temporary) > 50L * 1024 * 1024 || total > 200L * 1024 * 1024)
            throw new ImportException(
                "Attachment downloads exceed 50 MiB per asset or 200 MiB per import");
          downloads.put(document.getParent().resolve(asset.relativePath()), temporary);
        }
        new OutputTransaction().write(project, files, options.overwrite(), downloads);
      } finally {
        try (var paths = Files.list(downloadStage)) {
          for (Path path : paths.toList()) Files.deleteIfExists(path);
        }
        Files.deleteIfExists(downloadStage);
      }
    }
    return new ImportReport(
        document,
        id,
        page.version(),
        markdown,
        new ArrayList<>(files.keySet()),
        warnings.stream().map(Warn::message).toList(),
        assets.size(),
        options.preview());
  }

  /**
   * Same-page attachments referenced anywhere in the raw storage: {@code ri:attachment} elements
   * (including inside macros) and same-page {@code /download/attachments} URLs. Cross-page URLs
   * stay remote and keep working, so they are reported by the inventory instead. Output keeps the
   * original filename so republished {@code ri:filename} references resolve.
   */
  private static List<AssetRef> losslessAssets(
      PageImportSource.PageSnapshot page,
      List<PageImportSource.AttachmentSnapshot> attachments,
      String assetDir,
      org.w3c.dom.Element root) {
    Map<String, PageImportSource.AttachmentSnapshot> byName = new LinkedHashMap<>();
    for (var attachment : attachments) {
      if (byName.putIfAbsent(attachment.filename(), attachment) != null) {
        throw new ImportException("Ambiguous attachment filename on the selected page");
      }
    }
    Map<String, AssetRef> found = new LinkedHashMap<>();
    var references = root.getElementsByTagNameNS("urn:confluence:ri", "attachment");
    for (int i = 0; i < references.getLength(); i++) {
      var element = (org.w3c.dom.Element) references.item(i);
      var owners = element.getElementsByTagNameNS("urn:confluence:ri", "page");
      if (owners.getLength() > 0) {
        var owner = (org.w3c.dom.Element) owners.item(0);
        String ownerTitle = owner.getAttributeNS("urn:confluence:ri", "content-title");
        String ownerSpace = owner.getAttributeNS("urn:confluence:ri", "space-key");
        String ownerId = owner.getAttributeNS("urn:confluence:ri", "content-id");
        if (!ownerId.isEmpty()
            ? !ownerId.equals(page.id())
            : !ownerTitle.equals(page.title())
                || (!ownerSpace.isEmpty() && !ownerSpace.equals(page.space()))) continue;
      }
      String filename = element.getAttributeNS("urn:confluence:ri", "filename");
      var attachment = byName.get(filename);
      if (attachment == null)
        throw new ImportException(
            "Referenced attachment is absent from the selected page: " + filename);
      found.putIfAbsent(
          attachment.id(), new AssetRef(attachment, assetDir + "/" + safeSegment(filename)));
    }
    Matcher downloads = DOWNLOAD_ATTACHMENT.matcher(page.storage());
    while (downloads.find()) {
      if (!downloads.group(1).equals(page.id())) {
        continue;
      }
      String filename =
          URLDecoder.decode(
              unescapeAttr(downloads.group(2)).split("[?#]", 2)[0].replace("+", "%2B"),
              StandardCharsets.UTF_8);
      var attachment = byName.get(filename);
      if (attachment == null) {
        throw new ImportException(
            "Referenced attachment is absent from the selected page: " + filename);
      }
      found.putIfAbsent(
          attachment.id(),
          new AssetRef(attachment, assetDir + "/" + safeSegment(attachment.filename())));
    }
    List<AssetRef> assets = new ArrayList<>(found.values());
    Set<String> paths = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    for (var asset : assets) {
      if (!paths.add(asset.relativePath()))
        throw new ImportException(
            "Attachment output filenames collide (differ only by case): "
                + asset.attachment().filename());
    }
    return assets;
  }

  /**
   * Heuristic, non-fatal inventory of a raw body: every macro name found, flags for macros that
   * reference other pages, spaces, servers, or users, plus foreign space keys and reference counts.
   * Nothing here changes bytes; {@code --verify} guards the bytes.
   */
  private static List<Warn> losslessInventory(
      PageImportSource.PageSnapshot page, String space, org.w3c.dom.Element root) {
    List<Warn> warnings = new ArrayList<>();
    String storage = page.storage();
    var elements = root.getElementsByTagName("*");
    Set<String> reported = new LinkedHashSet<>();
    int pages = 0, posts = 0, users = 0, spaceRefs = 0;
    for (int i = 0; i < elements.getLength(); i++) {
      var element = (org.w3c.dom.Element) elements.item(i);
      if ("urn:confluence:ri".equals(element.getNamespaceURI())) {
        switch (element.getLocalName()) {
          case "page" -> pages++;
          case "blog-post" -> posts++;
          case "user" -> users++;
          case "space" -> spaceRefs++;
          default -> {}
        }
        String key = element.getAttributeNS("urn:confluence:ri", "space-key");
        if (!key.isEmpty() && !key.equals(space))
          warn(
              warnings,
              "reference",
              "Reference to space '" + key + "' points outside the target space");
      }
      if ("urn:confluence:ac".equals(element.getNamespaceURI())
          && Set.of("structured-macro", "macro").contains(element.getLocalName())) {
        String name = element.getAttributeNS("urn:confluence:ac", "name");
        if (reported.add(name))
          warn(warnings, "macro", "Macro '" + name + "' preserved without conversion");
        boolean external = element.getElementsByTagNameNS("urn:confluence:ri", "*").getLength() > 0;
        var parameters = element.getElementsByTagNameNS("urn:confluence:ac", "parameter");
        for (int j = 0; j < parameters.getLength(); j++) {
          String key =
              ((org.w3c.dom.Element) parameters.item(j))
                  .getAttributeNS("urn:confluence:ac", "name");
          if (key.toLowerCase(java.util.Locale.ROOT).contains("server")) external = true;
        }
        if (external)
          warn(
              warnings,
              "macro",
              "Macro '"
                  + name
                  + "' references another page, space, server, or user; verify on the target instance");
      }
    }
    Matcher downloads = DOWNLOAD_ATTACHMENT.matcher(storage);
    Set<String> foreignPages = new TreeSet<>();
    while (downloads.find()) {
      if (downloads.group(1).equals(page.id())) {
        warn(
            warnings,
            "attachment",
            "Download URL retains the source page ID; copying this page does not retarget that URL");
      } else {
        foreignPages.add(downloads.group(1));
      }
    }
    for (String pid : foreignPages) {
      warn(
          warnings,
          "attachment",
          "Download URL from page " + pid + " kept as a remote reference, not downloaded");
    }
    if (pages + posts + users + spaceRefs > 0)
      warn(
          warnings,
          "reference",
          "Body references "
              + pages
              + " page link(s), "
              + posts
              + " blog post(s), "
              + users
              + " user mention(s), "
              + spaceRefs
              + " space reference(s); links without an explicit space resolve in the target space");
    return warnings;
  }

  private static String losslessDocument(
      String space,
      List<String> parents,
      String title,
      List<String> labels,
      List<String> attachments,
      String storage) {
    for (String line : storage.split("\r?\n", -1)) {
      String stripped = line.strip();
      if (stripped.equals(MarkdownConverter.RAW_BEGIN)
          || stripped.equals(MarkdownConverter.RAW_END)) {
        throw new ImportException(
            "Page storage contains the raw block marker and cannot be imported losslessly");
      }
    }
    StringBuilder out = new StringBuilder();
    header(out, "Space", space);
    for (String parent : parents) {
      header(out, "Parent", parent);
    }
    header(out, "Title", title);
    for (String label : labels) {
      header(out, "Label", label);
    }
    for (String attachment : attachments) {
      header(out, "Attachment", attachment);
    }
    out.append('\n');
    out.append(MarkdownConverter.RAW_BEGIN).append('\n');
    out.append(storage).append('\n');
    out.append(MarkdownConverter.RAW_END).append('\n');
    return out.toString();
  }

  private static void header(StringBuilder out, String key, String value) {
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new ImportException(
          "Page "
              + key
              + " contains a control character or newline that the current metadata parser"
              + " cannot represent");
    }
    if (value.contains("-->")) {
      throw new ImportException(
          "Page " + key + " contains '-->' and cannot be written as an HTML comment header");
    }
    out.append("<!-- ").append(key).append(": ").append(value).append(" -->\n");
  }

  /**
   * The markers end the header run before the body starts, so body lines are never consumed as
   * headers; stranded-header warnings only describe comments kept verbatim in the body and are
   * ignored here.
   */
  private static void validateLosslessMetadata(
      String document,
      String space,
      List<String> parents,
      String title,
      List<String> labels,
      List<String> attachments) {
    var result = MetadataParser.parseDocument(document, new MetadataParser.ParseOptions());
    var meta = result.meta();
    if (!meta.space.equals(space)
        || !meta.title.equals(title)
        || !meta.parents.equals(parents)
        || !meta.labels.equals(labels)
        || !meta.attachments.equals(attachments)) {
      throw new ImportException(
          "Page metadata cannot be represented by the current metadata parser");
    }
  }

  static void checkCompiled(String compiled, String storage) {
    if (compiled.equals(storage)) {
      return;
    }
    int offset = firstDifference(compiled, storage);
    throw new ImportException(
        "Lossless verification failed at UTF-16 offset "
            + offset
            + " (compiled "
            + compiled.length()
            + " characters, source "
            + storage.length()
            + " characters); compiled: "
            + excerpt(compiled, offset)
            + "; source: "
            + excerpt(storage, offset));
  }

  static int firstDifference(String a, String b) {
    int n = Math.min(a.length(), b.length());
    for (int i = 0; i < n; i++) {
      if (a.charAt(i) != b.charAt(i)) {
        return i;
      }
    }
    return n;
  }

  private static String excerpt(String value, int offset) {
    int from = Math.max(0, offset - 40);
    int to = Math.min(value.length(), offset + 40);
    return "'" + value.substring(from, to).replace("\r", "\\r").replace("\n", "\\n") + "'";
  }

  /**
   * Filenames must survive the round trip unchanged: creatable on every platform and unambiguous as
   * publish attachment patterns (which treat {@code *?[]} as globs).
   */
  private static String safeSegment(String filename) {
    if (filename.isEmpty()
        || filename.equals(".")
        || filename.equals("..")
        || filename.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")
        || filename.indexOf('/') >= 0
        || filename.indexOf('\\') >= 0
        || filename.codePoints().anyMatch(c -> c < 0x20 || c == 0x7F)
        || filename.matches(".*[<>:\"|?*\\[\\]].*")
        || filename.endsWith(".")
        || filename.endsWith(" ")) {
      throw new ImportException(
          "Attachment filename cannot round-trip without renaming: " + filename);
    }
    return filename;
  }

  private static String unescapeAttr(String value) {
    Matcher matcher = ATTR_ENTITY.matcher(value);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      String replacement;
      if (matcher.group(1) != null) {
        replacement = new String(Character.toChars(Integer.parseInt(matcher.group(1))));
      } else if (matcher.group(2) != null) {
        replacement = new String(Character.toChars(Integer.parseInt(matcher.group(2), 16)));
      } else {
        replacement =
            switch (matcher.group(3)) {
              case "amp" -> "&";
              case "lt" -> "<";
              case "gt" -> ">";
              case "quot" -> "\"";
              default -> "'";
            };
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static void warn(List<Warn> warnings, String code, String message) {
    Warn warning = new Warn(code, message);
    if (!warnings.contains(warning)) {
      warnings.add(warning);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String metadata(PageImportSource.PageSnapshot page, List<String> attachments) {
    StringBuilder out =
        new StringBuilder(
            "---\nspace: " + scalar(page.space()) + "\ntitle: " + scalar(page.title()) + "\n");
    appendList(out, "parents", page.ancestors());
    appendList(out, "labels", page.labels());
    appendList(out, "attachments", attachments);
    return out.append("---\n\n").toString();
  }

  private static void appendList(StringBuilder out, String key, List<String> values) {
    if (!values.isEmpty()) {
      out.append(key).append(":\n");
      for (String value : values) out.append("  - ").append(scalar(value)).append('\n');
    }
  }

  private static String scalar(String value) {
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new ImportException(
          "Metadata contains a control character or newline that the current metadata parser cannot represent");
    }
    return "'" + value.replace("'", "''") + "'";
  }

  private static void validateMetadata(
      String document, PageImportSource.PageSnapshot page, List<String> attachments) {
    var result = MetadataParser.parseDocument(document, new MetadataParser.ParseOptions());
    var meta = result.meta();
    if (!meta.title.equals(page.title())
        || !meta.space.equals(page.space())
        || !meta.parents.equals(page.ancestors())
        || !meta.labels.equals(page.labels())
        || !meta.attachments.equals(attachments)
        || !result.warnings().isEmpty()) {
      throw new ImportException(
          "Page metadata cannot be represented by the current metadata parser");
    }
  }
}
