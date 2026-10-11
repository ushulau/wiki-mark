package com.wiki.mark.convert;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Block-level Markdown rendering.
 *
 * <p>Direct port of {@code mark.converter._BlockRenderer}: fenced code, ATX headings, rules,
 * raw-HTML passthrough, blockquotes, lists (nested, ordered, task), tables, paragraphs with Setext
 * headings.
 */
public class BlockRenderer {
  private static final Pattern FENCE_RE = Pattern.compile("^(\\s*)(```+|~~~+)\\s*([^\\s`]*).*$");
  private static final Pattern FENCE_CLOSER_RE = Pattern.compile("^\\s*(```+|~~~+)\\s*$");
  private static final Pattern ATX_RE = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
  private static final Pattern HR_RE =
      Pattern.compile("^\\s{0,3}((\\*\\s*){3,}|(-\\s*){3,}|(_\\s*){3,})\\s*$");
  private static final Pattern LIST_RE = Pattern.compile("^(\\s*)([*+-]|\\d+[.)])\\s+(.*)$");
  private static final Pattern TASK_RE = Pattern.compile("^\\[([ xX])\\]\\s+(.*)$");
  private static final Pattern TABLE_DELIM_RE =
      Pattern.compile("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$");
  private static final Pattern TAG_START_RE = Pattern.compile("^</?[a-zA-Z!/?]");
  private static final Pattern SETEXT_RE = Pattern.compile("(=+|-+)\\s*");
  private static final Pattern BLOCKQUOTE_STRIP_RE = Pattern.compile("^>\\s?");

  private final InlineRenderer inline;
  private final boolean dropH1;
  private boolean droppedH1 = false;

  /**
   * Creates a renderer.
   *
   * @param inline the inline renderer for block contents
   * @param dropH1 whether to drop the first H1 heading
   */
  public BlockRenderer(InlineRenderer inline, boolean dropH1) {
    this.inline = inline;
    this.dropH1 = dropH1;
  }

  /**
   * Render a line list to storage-HTML blocks.
   *
   * @param lines the document lines
   * @return the rendered blocks
   */
  public List<String> renderBlocks(List<String> lines) {
    List<String> blocks = new ArrayList<>();
    int i = 0;
    int n = lines.size();
    while (i < n) {
      String line = lines.get(i);
      if (line.strip().isEmpty()) {
        i += 1;
        continue;
      }
      Matcher fence = FENCE_RE.matcher(line);
      if (fence.matches()) {
        BlockResult r = fencedCode(lines, i);
        blocks.add(r.html());
        i = r.next();
        continue;
      }
      Matcher atx = ATX_RE.matcher(line.stripLeading());
      if (atx.matches() && startsWithAny(line, "# \t") && line.stripLeading().startsWith("#")) {
        int level = atx.group(1).length();
        if (dropH1 && level == 1 && !droppedH1) {
          droppedH1 = true;
        } else {
          blocks.add("<h" + level + ">" + inline.render(atx.group(2)) + "</h" + level + ">");
        }
        i += 1;
        continue;
      }
      if (HR_RE.matcher(line).matches()) {
        // Setext H1/H2 takes precedence over a "-" rule when the
        // previous emitted content allows; handled paragraph-side.
        blocks.add("<hr />");
        i += 1;
        continue;
      }
      if (TAG_START_RE.matcher(line.stripLeading()).lookingAt()) {
        BlockResult r = rawHtml(lines, i);
        blocks.add(r.html());
        i = r.next();
        continue;
      }
      if (line.stripLeading().startsWith(">")) {
        BlockResult r = blockquote(lines, i);
        blocks.add(r.html());
        i = r.next();
        continue;
      }
      if (LIST_RE.matcher(line).matches()) {
        BlockResult r = list(lines, i);
        blocks.add(r.html());
        i = r.next();
        continue;
      }
      if (line.contains("|") && i + 1 < n && TABLE_DELIM_RE.matcher(lines.get(i + 1)).matches()) {
        BlockResult r = table(lines, i);
        blocks.add(r.html());
        i = r.next();
        continue;
      }
      BlockResult r = paragraph(lines, i);
      if (r.html() != null) {
        blocks.add(r.html());
      }
      i = r.next();
    }
    return blocks;
  }

  private record BlockResult(String html, int next) {}

  private static boolean startsWithAny(String line, String chars) {
    return !line.isEmpty() && chars.indexOf(line.charAt(0)) >= 0;
  }

  private BlockResult fencedCode(List<String> lines, int start) {
    Matcher opener = FENCE_RE.matcher(lines.get(start));
    opener.matches();
    char fenceChar = opener.group(2).charAt(0);
    int fenceLen = opener.group(2).length();
    String language = opener.group(3).strip();
    List<String> collected = new ArrayList<>();
    int i = start + 1;
    while (i < lines.size()) {
      Matcher closer = FENCE_CLOSER_RE.matcher(lines.get(i));
      if (closer.matches()
          && closer.group(1).charAt(0) == fenceChar
          && closer.group(1).length() >= fenceLen) {
        i += 1;
        break;
      }
      collected.add(lines.get(i));
      i += 1;
    }
    String code = String.join("\n", collected);
    String macro =
        "<ac:structured-macro ac:name=\"code\">"
            + "<ac:parameter ac:name=\"language\">"
            + InlineRenderer.escapeText(language)
            + "</ac:parameter>"
            + "<ac:plain-text-body>"
            + InlineRenderer.cdata(code)
            + "</ac:plain-text-body>"
            + "</ac:structured-macro>";
    return new BlockResult(macro, i);
  }

  private BlockResult rawHtml(List<String> lines, int start) {
    List<String> collected = new ArrayList<>();
    int i = start;
    while (i < lines.size() && !lines.get(i).strip().isEmpty()) {
      collected.add(lines.get(i));
      i += 1;
    }
    return new BlockResult(String.join("\n", collected), i);
  }

  private BlockResult blockquote(List<String> lines, int start) {
    List<String> inner = new ArrayList<>();
    int i = start;
    while (i < lines.size()) {
      String stripped = lines.get(i).stripLeading();
      if (!stripped.startsWith(">")) {
        if (lines.get(i).strip().isEmpty()
            && i + 1 < lines.size()
            && lines.get(i + 1).stripLeading().startsWith(">")) {
          inner.add("");
          i += 1;
          continue;
        }
        break;
      }
      inner.add(BLOCKQUOTE_STRIP_RE.matcher(stripped).replaceFirst(""));
      i += 1;
    }
    String rendered = String.join("", renderBlocks(inner));
    return new BlockResult("<blockquote>" + rendered + "</blockquote>", i);
  }

  private record ListItem(int indent, boolean ordered, String text, List<String> extra) {}

  private record BuildResult(String html, int pos) {}

  private BlockResult list(List<String> lines, int start) {
    List<ListItem> items = new ArrayList<>();
    int i = start;
    while (i < lines.size()) {
      Matcher match = LIST_RE.matcher(lines.get(i));
      if (!match.matches()) {
        if (lines.get(i).strip().isEmpty()) {
          // Blank ends the list unless another item follows.
          int j = i + 1;
          while (j < lines.size() && lines.get(j).strip().isEmpty()) {
            j += 1;
          }
          if (j < lines.size() && LIST_RE.matcher(lines.get(j)).matches()) {
            i = j;
            continue;
          }
          break;
        }
        // Continuation line: append to the previous item.
        if (!items.isEmpty()
            && (startsWithAny(lines.get(i), " \t") || !lines.get(i).strip().startsWith("#"))) {
          items.get(items.size() - 1).extra().add(lines.get(i).strip());
          i += 1;
          continue;
        }
        break;
      }
      int indent = match.group(1).replace("\t", "    ").length();
      boolean ordered = Character.isDigit(match.group(2).charAt(0));
      items.add(new ListItem(indent, ordered, match.group(3), new ArrayList<>()));
      i += 1;
    }

    BuildResult built = buildList(items, 0, items.get(0).indent());
    return new BlockResult(built.html(), i);
  }

  private BuildResult buildList(List<ListItem> items, int pos, int level) {
    List<String> parts = new ArrayList<>();
    String tag = null;
    while (pos < items.size()) {
      ListItem item = items.get(pos);
      if (item.indent() > level) {
        BuildResult sub = buildList(items, pos, item.indent());
        pos = sub.pos();
        if (!parts.isEmpty()) {
          int last = parts.size() - 1;
          String prev = parts.get(last);
          parts.set(
              last, prev.substring(0, prev.length() - "</li>".length()) + sub.html() + "</li>");
        } else {
          parts.add(sub.html());
        }
        continue;
      }
      if (item.indent() < level) {
        break;
      }
      String want = item.ordered() ? "ol" : "ul";
      if (tag == null) {
        tag = want;
        parts.add("<" + tag + ">");
      } else if (!want.equals(tag)) {
        parts.add("</" + tag + ">");
        tag = want;
        parts.add("<" + tag + ">");
      }
      List<String> chunks = new ArrayList<>();
      chunks.add(item.text());
      chunks.addAll(item.extra());
      String body = String.join(" ", chunks).strip();
      Matcher task = TASK_RE.matcher(body);
      if (task.matches()) {
        String mark =
            task.group(1).toLowerCase(java.util.Locale.ROOT).equals("x")
                ? "complete"
                : "incomplete";
        String bodyHtml = inline.render(task.group(2));
        parts.add(
            "<li>"
                + "<ac:task-list><ac:task>"
                + "<ac:task-status>"
                + mark
                + "</ac:task-status>"
                + "<ac:task-body>"
                + bodyHtml
                + "</ac:task-body>"
                + "</ac:task></ac:task-list>"
                + "</li>");
      } else {
        parts.add("<li>" + inline.render(body) + "</li>");
      }
      pos += 1;
    }
    if (tag != null) {
      parts.add("</" + tag + ">");
    }
    return new BuildResult(String.join("", parts), pos);
  }

  private static List<String> splitRow(String line) {
    String core = InlineRenderer.stripChars(line.strip(), "|");
    String[] cells = core.split("\\|", -1);
    List<String> out = new ArrayList<>();
    for (String cell : cells) {
      out.add(cell.strip());
    }
    return out;
  }

  private BlockResult table(List<String> lines, int start) {
    List<String> header = splitRow(lines.get(start));
    int i = start + 2;
    List<List<String>> rows = new ArrayList<>();
    while (i < lines.size() && lines.get(i).contains("|") && !lines.get(i).strip().isEmpty()) {
      rows.add(splitRow(lines.get(i)));
      i += 1;
    }
    int width = header.size();
    StringBuilder head = new StringBuilder();
    for (String cell : header) {
      head.append("<th>").append(inline.render(cell)).append("</th>");
    }
    StringBuilder bodyRows = new StringBuilder();
    for (List<String> row : rows) {
      List<String> padded = new ArrayList<>(row);
      while (padded.size() < width) {
        padded.add("");
      }
      padded = padded.subList(0, width);
      bodyRows.append("<tr>");
      for (String cell : padded) {
        bodyRows.append("<td>").append(inline.render(cell)).append("</td>");
      }
      bodyRows.append("</tr>");
    }
    return new BlockResult(
        "<table><thead><tr>" + head + "</tr></thead><tbody>" + bodyRows + "</tbody></table>", i);
  }

  private BlockResult paragraph(List<String> lines, int start) {
    List<String> collected = new ArrayList<>();
    int i = start;
    while (i < lines.size()) {
      String line = lines.get(i);
      if (line.strip().isEmpty()) {
        i += 1;
        break;
      }
      if (FENCE_RE.matcher(line).matches()
          || LIST_RE.matcher(line).matches()
          || line.stripLeading().startsWith(">")
          || TAG_START_RE.matcher(line.stripLeading()).lookingAt()
          || (line.stripLeading().startsWith("#")
              && ATX_RE.matcher(line.stripLeading()).matches())) {
        break;
      }
      if (line.contains("|")
          && i + 1 < lines.size()
          && TABLE_DELIM_RE.matcher(lines.get(i + 1)).matches()) {
        break;
      }
      collected.add(line.strip());
      // Setext underline?
      if (i + 1 < lines.size() && SETEXT_RE.matcher(lines.get(i + 1).strip()).matches()) {
        String underline = lines.get(i + 1).strip();
        int level = underline.startsWith("=") ? 1 : 2;
        String text = inline.render(String.join(" ", collected));
        i += 2;
        if (dropH1 && level == 1 && !droppedH1) {
          droppedH1 = true;
          return new BlockResult(null, i);
        }
        return new BlockResult("<h" + level + ">" + text + "</h" + level + ">", i);
      }
      i += 1;
    }
    if (collected.isEmpty()) {
      return new BlockResult(null, i);
    }
    String text = inline.render(String.join(" ", collected));
    return new BlockResult("<p>" + text + "</p>", i);
  }
}
