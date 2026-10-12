package dev.vspex.wiki.mark.convert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inline Markdown rendering with the stash-placeholder pipeline.
 *
 * <p>Direct port of {@code mark.converter._InlineRenderer}. Generated HTML is stashed behind {@code
 * \x00N\x00} placeholders so the later emphasis pass neither escapes nor parses it; backslash
 * escapes hide behind {@code \x01N\x01} until the very end.
 */
public class InlineRenderer {
  private static final Pattern CODE_RE = Pattern.compile("`+([^`]*?)`+");
  private static final Pattern ESCAPE_RE = Pattern.compile("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|~>])");
  private static final Pattern PLACEHOLDER_RE = Pattern.compile("\u0000(\\d+)\u0000");
  private static final Pattern ESCAPE_PH_RE = Pattern.compile("\u0001(\\d+)\u0001");
  private static final Pattern IMAGE_RE =
      Pattern.compile("!\\[([^\\]]*)\\]\\(\\s*(\\S+?)(?:\\s+\"[^\"]*\")?\\s*\\)");
  private static final Pattern LINK_RE =
      Pattern.compile("\\[([^\\]]*)\\]\\(\\s*(\\S+?)(?:\\s+\"[^\"]*\")?\\s*\\)");
  private static final Pattern AUTOLINK_RE =
      Pattern.compile("<((?:https?|ftp):[^<>\\s]+|mailto:[^<>\\s]+)>");
  private static final Pattern ENTITY_RE =
      Pattern.compile("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]+);");
  private static final Pattern STRONG_STAR_RE = Pattern.compile("\\*\\*(.+?)\\*\\*");
  private static final Pattern STRONG_UNDER_RE = Pattern.compile("__(.+?)__");
  private static final Pattern EM_STAR_RE = Pattern.compile("\\*(.+?)\\*");
  private static final Pattern EM_UNDER_RE = Pattern.compile("\\b_(.+?)_\\b");
  private static final Pattern STRIKE_RE = Pattern.compile("~~(.+?)~~");

  private final String imageAlign;
  private final double imageMaxWidth;
  private final double imageMaxHeight;
  private final String imageBaseDir;
  private final Map<String, String> pageLinks;
  private final boolean attachReferenced;
  private final List<String> attachments;
  private final List<String> spans = new ArrayList<>();
  private final List<String> escapes = new ArrayList<>();

  /**
   * Creates a renderer.
   *
   * @param imageAlign image alignment; anything but left/center/right selects none
   * @param imageMaxWidth max image width in pixels; empty selects none
   * @param imageMaxHeight max image height in pixels; empty selects none
   * @param imageBaseDir directory local refs resolve from
   * @param pageLinks md path to page title map, null means none
   * @param attachReferenced upload linked local files as attachments
   * @param attachments collects the local refs to upload
   */
  public InlineRenderer(
      String imageAlign,
      String imageMaxWidth,
      String imageMaxHeight,
      String imageBaseDir,
      Map<String, String> pageLinks,
      boolean attachReferenced,
      List<String> attachments) {
    this.imageAlign =
        imageAlign.equals("left") || imageAlign.equals("center") || imageAlign.equals("right")
            ? imageAlign
            : "";
    this.imageMaxWidth = imageMaxWidth.isEmpty() ? 0.0 : Double.parseDouble(imageMaxWidth);
    this.imageMaxHeight = imageMaxHeight.isEmpty() ? 0.0 : Double.parseDouble(imageMaxHeight);
    this.imageBaseDir = imageBaseDir;
    this.pageLinks = pageLinks == null ? Map.of() : pageLinks;
    this.attachReferenced = attachReferenced;
    this.attachments = attachments;
  }

  /** Escape HTML, leaving already-formed entities alone. */
  static String escapeText(String text) {
    StringBuilder out = new StringBuilder();
    Matcher match = ENTITY_RE.matcher(text);
    int last = 0;
    while (match.find()) {
      out.append(escapeBasic(text.substring(last, match.start())));
      out.append(match.group(0));
      last = match.end();
    }
    out.append(escapeBasic(text.substring(last)));
    return out.toString();
  }

  static String escapeAttr(String text) {
    return escapeBasic(text).replace("\"", "&quot;").replace("'", "&#x27;");
  }

  private static String escapeBasic(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  static String cdata(String text) {
    return "<![CDATA[" + text.replace("]]>", "]]]]><![CDATA[>") + "]]>";
  }

  private void rememberAttachment(String ref) {
    ref = ref.strip();
    if (!ref.isEmpty() && !attachments.contains(ref)) {
      attachments.add(ref);
    }
  }

  private String stashHtml(String htmlText) {
    spans.add(htmlText);
    return "\u0000" + (spans.size() - 1) + "\u0000";
  }

  private String stashCode(String text) {
    return sub(
        CODE_RE,
        text,
        match -> {
          String code = match.group(1).replace("\n", " ");
          return stashHtml("<code>" + escapeText(code.strip()) + "</code>");
        });
  }

  private String stashEscapes(String text) {
    return sub(
        ESCAPE_RE,
        text,
        match -> {
          escapes.add(match.group(1));
          return "\u0001" + (escapes.size() - 1) + "\u0001";
        });
  }

  private String restore(String text) {
    // To a fixpoint: link HTML may itself hold code-span placeholders.
    for (int i = 0; i < 10; i++) {
      if (!text.contains("\u0000")) {
        break;
      }
      text = sub(PLACEHOLDER_RE, text, match -> spans.get(Integer.parseInt(match.group(1))));
    }
    return text;
  }

  private String restoreEscapes(String text) {
    return sub(ESCAPE_PH_RE, text, match -> escapes.get(Integer.parseInt(match.group(1))));
  }

  /**
   * Render one inline span.
   *
   * @param text the inline span
   * @return the rendered HTML
   */
  public String render(String text) {
    text = stashEscapes(text);
    text = stashCode(text);
    text = renderImages(text);
    text = renderLinks(text);
    text = renderAutolinks(text);
    text = renderEmphasis(text);
    text = restore(text);
    return restoreEscapes(text);
  }

  private String renderImages(String text) {
    return sub(
        IMAGE_RE,
        text,
        match -> {
          String alt = match.group(1);
          String src = stripChars(match.group(2), "<>");
          if (LinkResolver.isLocalRef(src)) {
            rememberAttachment(src);
            String align = imageAlign.isEmpty() ? "" : " ac:align=\"" + imageAlign + "\"";
            int[] fitted = ImageSizer.fittedSize(src, imageBaseDir, imageMaxWidth, imageMaxHeight);
            String width = fitted == null ? "" : " ac:width=\"" + fitted[0] + "\"";
            String height = fitted == null ? "" : " ac:height=\"" + fitted[1] + "\"";
            String filename = escapeAttr(LinkResolver.attachmentFilename(src));
            String out =
                "<ac:image"
                    + align
                    + width
                    + height
                    + "><ri:attachment ri:filename=\""
                    + filename
                    + "\" />";
            if (!alt.strip().isEmpty()) {
              out += "<ac:caption>" + escapeText(alt.strip()) + "</ac:caption>";
            }
            return stashHtml(out + "</ac:image>");
          }
          return stashHtml(
              "<img src=\"" + escapeAttr(src) + "\" alt=\"" + escapeAttr(alt) + "\" />");
        });
  }

  private String renderLinks(String text) {
    return sub(
        LINK_RE,
        text,
        match -> {
          String label = match.group(1);
          String href = stripChars(match.group(2), "<>");
          if (LinkResolver.isLocalRef(href)) {
            String title = LinkResolver.resolvePageLink(href, imageBaseDir, pageLinks);
            if (title != null) {
              String body = label.strip().isEmpty() ? title : label.strip();
              return stashHtml(
                  "<ac:link>"
                      + "<ri:page ri:content-title=\""
                      + escapeAttr(title)
                      + "\" />"
                      + "<ac:plain-text-link-body>"
                      + cdata(body)
                      + "</ac:plain-text-link-body>"
                      + "</ac:link>");
            }
            if (attachReferenced) {
              rememberAttachment(href);
              String filename = escapeAttr(LinkResolver.attachmentFilename(href));
              return stashHtml(
                  "<ac:link>"
                      + "<ri:attachment ri:filename=\""
                      + filename
                      + "\" />"
                      + "<ac:plain-text-link-body>"
                      + cdata(label)
                      + "</ac:plain-text-link-body>"
                      + "</ac:link>");
            }
          }
          String inner = renderEmphasis(label);
          return stashHtml("<a href=\"" + escapeAttr(href) + "\">" + inner + "</a>");
        });
  }

  private String renderAutolinks(String text) {
    return sub(
        AUTOLINK_RE,
        text,
        match -> {
          String href = match.group(1);
          return stashHtml("<a href=\"" + escapeAttr(href) + "\">" + escapeText(href) + "</a>");
        });
  }

  String renderEmphasis(String text) {
    // Escape first so emphasis markers added below are not re-processed.
    text = escapeText(text);
    text = STRONG_STAR_RE.matcher(text).replaceAll("<strong>$1</strong>");
    text = STRONG_UNDER_RE.matcher(text).replaceAll("<strong>$1</strong>");
    text = EM_STAR_RE.matcher(text).replaceAll("<em>$1</em>");
    text = EM_UNDER_RE.matcher(text).replaceAll("<em>$1</em>");
    return STRIKE_RE.matcher(text).replaceAll("<s>$1</s>");
  }

  static String stripChars(String s, String chars) {
    int start = 0;
    int end = s.length();
    while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
      start++;
    }
    while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
      end--;
    }
    return s.substring(start, end);
  }

  static String sub(Pattern pattern, String text, Function<Matcher, String> repl) {
    Matcher matcher = pattern.matcher(text);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(out, Matcher.quoteReplacement(repl.apply(matcher)));
    }
    matcher.appendTail(out);
    return out.toString();
  }
}
