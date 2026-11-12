package dev.vspex.wiki.mark.sync;

import java.io.StringReader;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

/** Exact, bounded three-way merge of complete top-level storage blocks. */
public final class StorageMerge {
  private StorageMerge() {}

  public static String merge(String base, String local, String remote) {
    validate(base);
    validate(local);
    validate(remote);
    if (local.equals(remote)) return local;
    if (base.equals(local)) return remote;
    if (base.equals(remote)) return local;
    List<String> b = blocks(base), l = blocks(local), r = blocks(remote);
    if (new HashSet<>(b).size() != b.size())
      throw new SyncException("Merge conflict: ambiguous repeated storage blocks");
    List<Edit> edits = new ArrayList<>(edits(b, l));
    for (Edit right : edits(b, r)) {
      boolean duplicate = false;
      for (Edit left : edits) {
        if (left.equals(right)) {
          duplicate = true;
          break;
        }
        if (left.start == left.end && right.start == right.end
            ? left.start == right.start
            : left.start == left.end
                ? right.start < left.start && left.start < right.end
                : right.start == right.end
                    ? left.start < right.start && right.start < left.end
                    : left.start < right.end && right.start < left.end)
          throw new SyncException(
              "Merge conflict: overlapping storage blocks near block " + (right.start + 1));
      }
      if (!duplicate) edits.add(right);
    }
    edits.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
    StringBuilder out = new StringBuilder();
    int cursor = 0;
    for (Edit edit : edits) {
      for (; cursor < edit.start; cursor++) out.append(b.get(cursor));
      edit.replacement.forEach(out::append);
      cursor = edit.end;
    }
    for (; cursor < b.size(); cursor++) out.append(b.get(cursor));
    String merged = out.toString();
    validate(merged);
    return merged;
  }

  private record Edit(int start, int end, List<String> replacement) {}

  private static List<Edit> edits(List<String> base, List<String> branch) {
    int n = base.size(), m = branch.size();
    if ((long) (n + 1) * (m + 1) > 1_000_000)
      throw new SyncException("Merge resource limit: too many storage blocks");
    int[][] dp = new int[n + 1][m + 1];
    for (int i = n - 1; i >= 0; i--)
      for (int j = m - 1; j >= 0; j--)
        dp[i][j] =
            base.get(i).equals(branch.get(j))
                ? 1 + dp[i + 1][j + 1]
                : Math.max(dp[i + 1][j], dp[i][j + 1]);
    List<Edit> out = new ArrayList<>();
    int i = 0, j = 0;
    while (i < n || j < m) {
      if (i < n && j < m && base.get(i).equals(branch.get(j))) {
        i++;
        j++;
        continue;
      }
      int start = i;
      List<String> replacement = new ArrayList<>();
      while (i < n || j < m) {
        if (i < n && j < m && base.get(i).equals(branch.get(j))) break;
        if (j < m && (i == n || dp[i][j + 1] > dp[i + 1][j])) replacement.add(branch.get(j++));
        else i++;
      }
      out.add(new Edit(start, i, List.copyOf(replacement)));
    }
    return out;
  }

  static void validate(String value) {
    parse(value);
  }

  static Set<String> attachmentNames(String value) {
    var nodes =
        parse(value)
            .getElementsByTagNameNS("http://atlassian.com/resource/identifier", "attachment");
    Set<String> names = new HashSet<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      var element = (org.w3c.dom.Element) nodes.item(i);
      if (element
              .getElementsByTagNameNS("http://atlassian.com/resource/identifier", "page")
              .getLength()
          == 0)
        names.add(element.getAttributeNS("http://atlassian.com/resource/identifier", "filename"));
    }
    return names;
  }

  private static org.w3c.dom.Document parse(String value) {
    if (value.length() > 2_000_000)
      throw new SyncException("Merge resource limit: storage exceeds 2 million characters");
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      var parser = factory.newDocumentBuilder();
      parser.setErrorHandler(
          new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void fatalError(org.xml.sax.SAXParseException e)
                throws org.xml.sax.SAXException {
              throw e;
            }

            @Override
            public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
              throw e;
            }
          });
      return parser.parse(
          new InputSource(
              new StringReader(
                  "<root xmlns:ac='http://atlassian.com/content' xmlns:ri='http://atlassian.com/resource/identifier'>"
                      + value
                      + "</root>")));
    } catch (Exception e) {
      throw new SyncException("Merge unsupported: storage is not a supported XML fragment");
    }
  }

  private static List<String> blocks(String text) {
    List<String> out = new ArrayList<>();
    int start = 0, depth = 0;
    for (int i = 0; i < text.length(); ) {
      if (text.charAt(i) != '<') {
        i++;
        continue;
      }
      int end;
      if (text.startsWith("<!--", i)) end = text.indexOf("-->", i) + 3;
      else if (text.startsWith("<![CDATA[", i)) end = text.indexOf("]]>", i) + 3;
      else {
        int j = i + 1;
        char quote = 0;
        for (; j < text.length(); j++) {
          char c = text.charAt(j);
          if (quote != 0) {
            if (c == quote) quote = 0;
          } else if (c == '\'' || c == '"') quote = c;
          else if (c == '>') break;
        }
        end = j + 1;
        if (text.startsWith("</", i)) depth--;
        else if (!text.startsWith("<?", i) && text.charAt(j - 1) != '/') depth++;
      }
      if (depth == 0) {
        out.add(text.substring(start, end));
        start = end;
      }
      i = end;
      if (out.size() > 10000) throw new SyncException("Merge resource limit: too many blocks");
    }
    if (start < text.length()) out.add(text.substring(start));
    return out;
  }
}
