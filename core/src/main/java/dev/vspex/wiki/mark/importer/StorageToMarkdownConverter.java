package dev.vspex.wiki.mark.importer;

import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.html.parser.DTD;
import javax.swing.text.html.parser.ParserDelegator;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/** Structured, offline conversion of a Confluence storage fragment into Markdown. */
public final class StorageToMarkdownConverter {
  public record Warning(String code, String message) {}

  public record Asset(
      PageImportSource.AttachmentSnapshot attachment, String relativePath, boolean nonImage) {}

  public record Result(String markdown, List<Asset> assets, List<Warning> warnings) {
    public Result {
      assets = List.copyOf(assets);
      warnings = List.copyOf(warnings);
    }
  }

  private final String baseUrl;
  private final PageImportSource.PageSnapshot page;
  private final String assetDir;
  private final Map<String, PageImportSource.AttachmentSnapshot> attachments =
      new LinkedHashMap<>();
  private final Map<String, Asset> assets = new LinkedHashMap<>();
  private final List<Warning> warnings = new ArrayList<>();
  private static final Set<String> INLINE =
      Set.of(
          "strong", "b", "em", "i", "s", "del", "strike", "code", "a", "span", "br", "img", "u",
          "sub", "sup");

  private StorageToMarkdownConverter(
      String baseUrl,
      PageImportSource.PageSnapshot page,
      List<PageImportSource.AttachmentSnapshot> attachments,
      String assetDir) {
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.page = page;
    this.assetDir = assetDir;
    for (var attachment : attachments) {
      if (this.attachments.putIfAbsent(attachment.filename(), attachment) != null) {
        throw new ImportException("Ambiguous attachment filename on the selected page");
      }
    }
  }

  public static Result convert(
      String baseUrl,
      PageImportSource.PageSnapshot page,
      List<PageImportSource.AttachmentSnapshot> attachments,
      String assetDir) {
    var converter = new StorageToMarkdownConverter(baseUrl, page, attachments, assetDir);
    Element root = parse(page.storage());
    String markdown = converter.children(root).strip();
    return new Result(
        markdown.isEmpty() ? "" : markdown + "\n",
        new ArrayList<>(converter.assets.values()),
        converter.warnings);
  }

  static Element parse(String storage) {
    if (storage == null) throw new ImportException("Page has no storage representation");
    if (storage.length() > 5_000_000)
      throw new ImportException("Storage exceeds the 5 million character limit");
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "256");
      factory.setXIncludeAware(false);
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(
          new DefaultHandler() {
            @Override
            public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
              throw e;
            }

            @Override
            public void fatalError(org.xml.sax.SAXParseException e)
                throws org.xml.sax.SAXException {
              throw e;
            }
          });
      // Confluence storage uses XHTML named entities without a document-level DTD.
      // The JDK's local HTML entity catalogue avoids loading any external resources.
      new ParserDelegator();
      DTD entities = DTD.getDTD("html32");
      Matcher matcher =
          Pattern.compile("(?s)<!\\[CDATA\\[.*?\\]\\]>|&([A-Za-z][A-Za-z0-9]+);").matcher(storage);
      StringBuffer expanded = new StringBuffer();
      while (matcher.find()) {
        String replacement = matcher.group();
        String name = matcher.group(1);
        if (name != null && !Set.of("amp", "lt", "gt", "quot", "apos").contains(name)) {
          var entity = entities.getEntity(name);
          if (entity != null) {
            StringBuilder numeric = new StringBuilder();
            entity
                .getString()
                .codePoints()
                .forEach(cp -> numeric.append("&#").append(cp).append(';'));
            replacement = numeric.toString();
          }
        }
        matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
      }
      matcher.appendTail(expanded);
      return builder
          .parse(
              new InputSource(
                  new StringReader(
                      "<import-root xmlns:ac=\"urn:confluence:ac\" xmlns:ri=\"urn:confluence:ri\">"
                          + expanded
                          + "</import-root>")))
          .getDocumentElement();
    } catch (Exception e) {
      throw new ImportException("Malformed or unsupported Confluence storage markup");
    }
  }

  private String children(Node parent) {
    StringBuilder out = new StringBuilder();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node.getNodeType() == Node.TEXT_NODE
          && node.getNodeValue().isBlank()
          && (isBlock(node.getPreviousSibling()) || isBlock(node.getNextSibling()))) continue;
      if (isBlock(node) && out.length() > 0 && out.charAt(out.length() - 1) != '\n')
        out.append("\n\n");
      out.append(render(node));
    }
    return out.toString();
  }

  private static boolean isBlock(Node node) {
    if (!(node instanceof Element e)) return false;
    return e.getTagName().matches("h[1-6]")
        || Set.of(
                "p",
                "div",
                "ul",
                "ol",
                "pre",
                "hr",
                "table",
                "blockquote",
                "ac:structured-macro",
                "ac:macro",
                "ac:layout",
                "ac:layout-section",
                "ac:layout-cell")
            .contains(e.getTagName());
  }

  private String render(Node node) {
    if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
      String text = node.getNodeValue().replaceAll("[\\t\\r\\n ]+", " ");
      return escape(text);
    }
    if (!(node instanceof Element e)) return "";
    String tag = e.getTagName();
    if (tag.matches("h[1-6]"))
      return block("#".repeat(tag.charAt(1) - '0') + " " + children(e).strip());
    return switch (tag) {
      case "p", "div" -> block(children(e).strip());
      case "strong", "b" -> "**" + children(e) + "**";
      case "em", "i" -> "*" + children(e) + "*";
      case "s", "strike", "del" -> "~~" + children(e) + "~~";
      case "code" -> {
        if (e.getTextContent().contains("`"))
          warn("code", "Inline code contains backticks; review the publishing preview");
        yield inlineCode(e.getTextContent());
      }
      case "pre" -> block(fence(e.getTextContent(), ""));
      case "br" -> "  \n";
      case "hr" -> block("---");
      case "ul", "ol" -> block(list(e));
      case "blockquote" -> block(quote(children(e).strip()));
      case "table" -> table(e);
      case "a" -> link(e);
      case "img" -> image(e.getAttribute("src"), e.getAttribute("alt"));
      case "ac:image" -> confluenceImage(e);
      case "ac:link" -> confluenceLink(e);
      case "ac:structured-macro", "ac:macro" -> macro(e);
      case "ac:layout", "ac:layout-section", "ac:layout-cell" -> {
        warn("layout", "Flattened Confluence layout: " + tag);
        yield block(children(e).strip());
      }
      case "ac:rich-text-body", "ac:link-body", "ac:plain-text-link-body", "span" -> children(e);
      case "u", "sub", "sup" -> {
        warn("formatting", "Preserved text without " + tag + " formatting");
        yield children(e);
      }
      default -> {
        warn("unsupported", "Unsupported storage element: " + tag);
        yield "[Unsupported " + escape(tag) + ": " + escape(e.getTextContent().strip()) + "]";
      }
    };
  }

  private String list(Element list) {
    StringBuilder out = new StringBuilder();
    int number = 1;
    if (list.hasAttribute("start")) {
      try {
        number = Integer.parseInt(list.getAttribute("start"));
      } catch (NumberFormatException e) {
        warn("list", "Invalid ordered-list start");
      }
    }
    for (Element item : elements(list)) {
      if (!item.getTagName().equals("li")) {
        warn("list", "Unexpected list child: " + item.getTagName());
        out.append(render(item));
        continue;
      }
      String prefix = list.getTagName().equals("ol") ? number++ + ". " : "- ";
      String text = children(item).strip();
      out.append(prefix)
          .append(text.replace("\n", "\n" + " ".repeat(prefix.length())))
          .append('\n');
    }
    return out.toString().stripTrailing();
  }

  private String macro(Element e) {
    String name = e.getAttribute("ac:name");
    Element plain = child(e, "ac:plain-text-body");
    Element rich = child(e, "ac:rich-text-body");
    String body =
        rich != null ? children(rich).strip() : plain != null ? plain.getTextContent() : "";
    if (name.equals("code") || name.equals("noformat")) {
      if (plain == null) {
        warn("macro", "Code macro without plain-text body retained as text");
        return block(body.isEmpty() ? "[Code macro has no plain-text body]" : body);
      }
      return block(fence(plain == null ? "" : plain.getTextContent(), parameter(e, "language")));
    }
    if (Set.of("info", "note", "warning", "tip", "panel", "expand").contains(name)) {
      warn("macro", "Converted " + name + " macro to labeled text");
      String title = parameter(e, "title");
      return block(quote("**" + escape(title.isEmpty() ? name : title) + "**\n\n" + body));
    }
    warn("macro", "Unsupported macro: " + name);
    if (body.isEmpty()) body = escape(e.getTextContent().strip());
    return block(
        "[Unsupported macro: " + escape(name) + "]" + (body.isEmpty() ? "" : "\n\n" + body));
  }

  private static String parameter(Element e, String name) {
    for (Element child : elements(e)) {
      if (child.getTagName().equals("ac:parameter") && child.getAttribute("ac:name").equals(name))
        return child.getTextContent();
    }
    return "";
  }

  private String table(Element e) {
    List<Element> rows = new ArrayList<>();
    collectRows(e, rows);
    boolean simple = !rows.isEmpty();
    int columns = rows.isEmpty() ? 0 : elements(rows.get(0)).size();
    for (Element row : rows) {
      if (elements(row).size() != columns) simple = false;
      for (Element cell : elements(row)) {
        if (elements(cell).stream().filter(c -> c.getTagName().equals("p")).count() > 1)
          simple = false;
        if (!Set.of("th", "td").contains(cell.getTagName())
            || cell.hasAttribute("rowspan")
            || cell.hasAttribute("colspan")) simple = false;
        for (Element content : elements(cell)) {
          if (!simpleCell(content)) simple = false;
        }
      }
    }
    if (!simple || columns == 0) {
      warn("table", "Complex table retained as sanitized HTML");
      return block(safeHtml(e));
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < rows.size(); i++) {
      out.append('|');
      for (Element cell : elements(rows.get(i))) {
        String text =
            children(cell)
                .strip()
                .replace("\n", "<br>")
                .replace("\\|", "&#124;")
                .replace("|", "&#124;");
        out.append(' ').append(text).append(" |");
      }
      out.append('\n');
      if (i == 0) {
        if (elements(rows.get(0)).stream().anyMatch(c -> !c.getTagName().equals("th"))) {
          warn("table", "First table row becomes the Markdown header row");
        }
        out.append('|').append(" --- |".repeat(columns)).append('\n');
      }
    }
    return block(out.toString().stripTrailing());
  }

  private static boolean simpleCell(Element e) {
    if (e.getTagName().equals("br")
        || e.getTagName().equals("code") && e.getTextContent().contains("\n")) return false;
    if (!INLINE.contains(e.getTagName()) && !e.getTagName().equals("p")) return false;
    return elements(e).stream().allMatch(StorageToMarkdownConverter::simpleCell);
  }

  private static void collectRows(Element e, List<Element> rows) {
    for (Element child : elements(e)) {
      if (child.getTagName().equals("tr")) rows.add(child);
      else if (Set.of("thead", "tbody", "tfoot").contains(child.getTagName()))
        collectRows(child, rows);
    }
  }

  private String safeHtml(Node node) {
    if (!(node instanceof Element e)) return html(node.getTextContent());
    String tag = e.getTagName();
    if (!Set.of(
            "table",
            "thead",
            "tbody",
            "tfoot",
            "tr",
            "td",
            "th",
            "p",
            "br",
            "strong",
            "em",
            "b",
            "i",
            "code",
            "pre",
            "ul",
            "ol",
            "li",
            "blockquote",
            "del")
        .contains(tag)) {
      // Convert links and images too, so asset references are still collected.
      return html(render(e));
    }
    StringBuilder out = new StringBuilder("<" + tag);
    for (String attr : List.of("colspan", "rowspan")) {
      if (e.getAttribute(attr).matches("[0-9]{1,4}"))
        out.append(' ').append(attr).append("=\"").append(e.getAttribute(attr)).append('"');
    }
    out.append('>');
    if (tag.equals("br")) return out.toString();
    for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) out.append(safeHtml(n));
    return out.append("</").append(tag).append('>').toString();
  }

  private String link(Element e) {
    String label = children(e).strip();
    String href = e.getAttribute("href");
    if (href.isEmpty()) {
      warn("anchor", "Named anchor retained as text");
      return label;
    }
    String asset = attachmentHref(href, false);
    return "["
        + (label.isEmpty() ? escape(href) : label)
        + "]("
        + (asset == null ? url(href) : asset)
        + ")";
  }

  private String image(String href, String alt) {
    String asset = attachmentHref(href, true);
    return "![" + escape(alt) + "](" + (asset == null ? url(href) : asset) + ")";
  }

  private String attachmentHref(String href, boolean image) {
    URI absolute;
    try {
      absolute = URI.create(baseUrl + "/").resolve(href);
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (!ImportPaths.sameOrigin(ImportPaths.uri(baseUrl), absolute)) return null;
    Matcher match =
        Pattern.compile("/download/attachments/([0-9]+)/([^/]+)").matcher(absolute.getRawPath());
    if (!match.find()) return null;
    if (!match.group(1).equals(page.id())) {
      warn("attachment", "Cross-page attachment kept as remote URL");
      return null;
    }
    String filename =
        java.net.URLDecoder.decode(
            match.group(2).replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
    return asset(filename, image);
  }

  private String confluenceImage(Element e) {
    Element caption = child(e, "ac:caption");
    String suffix = "";
    if (caption != null) {
      warn("image", "Image caption retained as text");
      suffix = " " + children(caption).strip();
    }
    Element attachment = child(e, "ri:attachment");
    if (attachment != null) {
      if (!selectedPage(attachment)) return fallback(e, "Cross-page attachment image");
      return "!["
          + escape(e.getAttribute("ac:alt"))
          + "]("
          + asset(attachment.getAttribute("ri:filename"), true)
          + ")"
          + suffix;
    }
    Element remote = child(e, "ri:url");
    if (remote != null)
      return image(remote.getAttribute("ri:value"), e.getAttribute("ac:alt")) + suffix;
    return fallback(e, "Unsupported image");
  }

  private String confluenceLink(Element e) {
    Element body = child(e, "ac:link-body");
    if (body == null) body = child(e, "ac:plain-text-link-body");
    String label = body == null ? "" : children(body).strip();
    Element attachment = child(e, "ri:attachment");
    if (attachment != null) {
      if (!selectedPage(attachment)) return fallback(e, "Cross-page attachment link");
      String filename = attachment.getAttribute("ri:filename");
      return "["
          + (label.isEmpty() ? escape(filename) : label)
          + "]("
          + asset(filename, false)
          + ")";
    }
    Element target = child(e, "ri:page");
    String href;
    if (target != null) {
      String id = target.getAttribute("ri:content-id");
      String title = target.getAttribute("ri:content-title");
      String space = target.getAttribute("ri:space-key");
      if (space.isEmpty()) space = page.space();
      href =
          id.matches("[0-9]+")
              ? baseUrl + "/pages/viewpage.action?pageId=" + id
              : baseUrl
                  + "/pages/viewpage.action?spaceKey="
                  + ImportPaths.encode(space)
                  + "&title="
                  + ImportPaths.encode(title);
      if (label.isEmpty()) label = escape(title.isEmpty() ? id : title);
    } else {
      if (e.getAttribute("ac:anchor").isEmpty()) return fallback(e, "Unsupported Confluence link");
      href = baseUrl + "/pages/viewpage.action?pageId=" + page.id();
    }
    String anchor = e.getAttribute("ac:anchor");
    if (!anchor.isEmpty()) href += "#" + ImportPaths.encode(anchor);
    if (label.isEmpty()) label = escape(anchor);
    return "[" + label + "](" + url(href) + ")";
  }

  private boolean selectedPage(Element attachment) {
    Element target = child(attachment, "ri:page");
    if (target == null) return true;
    String id = target.getAttribute("ri:content-id");
    if (!id.isEmpty()) return id.equals(page.id());
    String space = target.getAttribute("ri:space-key");
    return target.getAttribute("ri:content-title").equals(page.title())
        && (space.isEmpty() || space.equals(page.space()));
  }

  private String asset(String filename, boolean image) {
    var attachment = attachments.get(filename);
    if (attachment == null)
      throw new ImportException(
          "Referenced attachment is absent from the selected page: " + filename);
    if (!attachment.id().matches("[A-Za-z0-9_-]+"))
      throw new ImportException("Invalid attachment ID");
    String relative = assetDir + "/" + attachment.id() + "-" + ImportPaths.safeFilename(filename);
    Asset existing = assets.get(attachment.id());
    for (Asset prior : assets.values()) {
      if (prior.relativePath().equalsIgnoreCase(relative)
          && !prior.attachment().equals(attachment)) {
        throw new ImportException("Attachment output filenames collide");
      }
    }
    assets.put(
        attachment.id(),
        new Asset(attachment, relative, !image || existing != null && existing.nonImage()));
    return encodePath(relative);
  }

  private String fallback(Element e, String message) {
    warn("unsupported", message);
    return "[" + message + ": " + escape(e.getTextContent().strip()) + "]";
  }

  private String url(String href) {
    try {
      URI uri = URI.create(baseUrl + "/").resolve(href.replace(" ", "%20"));
      String scheme = uri.getScheme();
      if (uri.getUserInfo() != null
          || scheme == null
          || !Set.of("http", "https", "mailto")
              .contains(scheme.toLowerCase(java.util.Locale.ROOT))) {
        warn("url", "Unsafe URL omitted");
        return "#";
      }
      return uri.toASCIIString().replace("(", "%28").replace(")", "%29");
    } catch (IllegalArgumentException e) {
      warn("url", "Invalid URL omitted");
      return "#";
    }
  }

  static String encodePath(String path) {
    return java.util.Arrays.stream(path.split("/", -1))
        .map(ImportPaths::encode)
        .collect(java.util.stream.Collectors.joining("/"));
  }

  private void warn(String code, String message) {
    Warning warning = new Warning(code, message);
    if (!warnings.contains(warning)) warnings.add(warning);
  }

  private static String block(String text) {
    return text.isEmpty() ? "" : text + "\n\n";
  }

  private static String quote(String text) {
    return "> " + text.replace("\n", "\n> ");
  }

  private static String inlineCode(String text) {
    String fence = "`".repeat(Math.max(1, longest(text, '`') + 1));
    return fence
        + (text.startsWith("`") || text.endsWith("`") || text.startsWith(" ") || text.endsWith(" ")
            ? " " + text + " "
            : text)
        + fence;
  }

  private static String fence(String text, String language) {
    String fence = "`".repeat(Math.max(3, longest(text, '`') + 1));
    String lang = language.matches("[A-Za-z0-9_+.-]*") ? language : "";
    return fence + lang + "\n" + text + (text.endsWith("\n") ? "" : "\n") + fence;
  }

  private static int longest(String value, char character) {
    int max = 0, count = 0;
    for (char c : value.toCharArray()) {
      count = c == character ? count + 1 : 0;
      max = Math.max(max, count);
    }
    return max;
  }

  private static String escape(String text) {
    return text.replace("\\", "\\\\")
        .replaceAll("([`*_{}\\[\\]()#+.!|>~-])", "\\\\$1")
        .replace("&", "&amp;")
        .replace("<", "&lt;");
  }

  private static String html(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private static List<Element> elements(Node node) {
    List<Element> result = new ArrayList<>();
    for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling())
      if (n instanceof Element e) result.add(e);
    return result;
  }

  private static Element child(Element e, String tag) {
    return elements(e).stream().filter(c -> c.getTagName().equals(tag)).findFirst().orElse(null);
  }
}
