package dev.vspex.wiki.mark.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Git-style unified diffs over Confluence storage markup, for drift reports.
 *
 * <p>Display-only pipeline: {@link #normalize} strips volatile attributes, {@link #splitLines}
 * breaks the (often single-line) storage into diffable lines, then {@link #unifiedDiff} renders
 * {@code ---}/{@code +++} hunks with a Myers diff. Equality checks and publishing always stay
 * byte-exact on the raw strings; the split form never feeds back into compare or publish. JDK only,
 * no dependencies.
 */
public final class StorageDiff {
  private StorageDiff() {}

  /** Context lines around each change, like {@code git diff -U3}. */
  public static final int CONTEXT = 3;

  /** Lines longer than this are hard-wrapped by {@link #splitLines}. */
  public static final int MAX_WRAP = 200;

  /** Body lines emitted before {@link #unifiedDiff} truncates with a note. */
  public static final int MAX_DIFF_LINES = 200;

  /** Larger middle sections fall back to one coarse replace block (still valid, non-minimal). */
  static final int MAX_MIDDLE_SUM = 4000;

  private static final Pattern TOKENS =
      Pattern.compile(
          "(?s)<!\\[CDATA\\[.*?\\]\\]>|<!--.*?-->|<[^>\"']*(?:(?:\"[^\"]*\"|'[^']*')[^>\"']*)*>");
  private static final Pattern ATTRIBUTES =
      Pattern.compile("\\s+([\\w:-]+)\\s*=\\s*(?:\"[^\"]*\"|'[^']*')");
  static final long MAX_TRACE_BYTES = 8L * 1024 * 1024;

  private record Op(Kind kind, String text) {
    private enum Kind {
      SAME,
      DEL,
      INS
    }
  }

  /** Removes volatile Confluence attributes ({@code ac:macro-id}, {@code ac:local-id}). */
  public static String normalize(String storage) {
    if (storage == null || storage.isEmpty()) {
      return "";
    }
    var tokens = TOKENS.matcher(storage);
    StringBuilder out = new StringBuilder(storage.length());
    int end = 0;
    while (tokens.find()) {
      out.append(storage, end, tokens.start());
      String token = tokens.group();
      if (!token.startsWith("<!") && !token.startsWith("<?")) {
        var attributes = ATTRIBUTES.matcher(token);
        StringBuilder tag = new StringBuilder();
        int offset = 0;
        while (attributes.find()) {
          String name = attributes.group(1);
          if (name.equals("ac:macro-id") || name.equals("ac:local-id")) {
            tag.append(token, offset, attributes.start());
            offset = attributes.end();
          }
        }
        token = tag.append(token, offset, token.length()).toString();
      }
      out.append(token);
      end = tokens.end();
    }
    return out.append(storage, end, storage.length()).toString();
  }

  /**
   * Breaks storage into diffable lines: first on existing newlines, then between tags, then
   * hard-wraps anything still longer than {@link #MAX_WRAP}. Display-only.
   *
   * @param storage the raw storage body, may be null
   * @return the diffable lines, never null
   */
  public static List<String> splitLines(String storage) {
    List<String> out = new ArrayList<>();
    String normalized = normalize(storage);
    if (normalized.isEmpty()) {
      return out;
    }
    for (String raw : normalized.split("\r?\n", -1)) {
      for (String part : raw.split("(?<=>)(?=<)", -1)) {
        if (part.isEmpty()) out.add("");
        for (int offset = 0; offset < part.length(); offset += MAX_WRAP) {
          out.add(part.substring(offset, Math.min(offset + MAX_WRAP, part.length())));
        }
      }
    }
    return out;
  }

  /**
   * Normalizes, splits, and diffs two storage bodies.
   *
   * @param expected the baseline storage
   * @param actual the live storage
   * @param oldLabel the {@code --- a/} label
   * @param newLabel the {@code +++ b/} label
   * @return the unified diff, or an empty string when they match
   */
  public static String diffStorage(
      String expected, String actual, String oldLabel, String newLabel) {
    return unifiedDiff(splitLines(expected), splitLines(actual), oldLabel, newLabel);
  }

  /**
   * Renders a git-style unified diff of two line lists.
   *
   * @param oldLines the baseline lines
   * @param newLines the live lines
   * @param oldLabel the {@code --- a/} label ({@code "expected"} when empty)
   * @param newLabel the {@code +++ b/} label ({@code "actual"} when empty)
   * @return the unified diff, or an empty string when they match
   */
  public static String unifiedDiff(
      List<String> oldLines, List<String> newLines, String oldLabel, String newLabel) {
    String oldName = oldLabel == null || oldLabel.isEmpty() ? "expected" : oldLabel;
    String newName = newLabel == null || newLabel.isEmpty() ? "actual" : newLabel;
    int prefix = commonPrefix(oldLines, newLines);
    int suffix = commonSuffix(oldLines, newLines, prefix);
    List<String> a = oldLines.subList(prefix, oldLines.size() - suffix);
    List<String> b = newLines.subList(prefix, newLines.size() - suffix);
    if (a.isEmpty() && b.isEmpty()) {
      return "";
    }
    int skipped = Math.max(0, prefix - CONTEXT);
    int keptSuffix = Math.min(suffix, CONTEXT);
    List<Op> script = new ArrayList<>(2 * CONTEXT + a.size() + b.size());
    for (int i = skipped; i < prefix; i++) {
      script.add(new Op(Op.Kind.SAME, oldLines.get(i)));
    }
    script.addAll(diffMiddle(a, b));
    for (int i = oldLines.size() - suffix; i < oldLines.size() - suffix + keptSuffix; i++) {
      script.add(new Op(Op.Kind.SAME, oldLines.get(i)));
    }
    return render(script, oldName, newName, skipped);
  }

  private static int commonPrefix(List<String> a, List<String> b) {
    int prefix = 0;
    while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
      prefix++;
    }
    return prefix;
  }

  private static int commonSuffix(List<String> a, List<String> b, int prefix) {
    int suffix = 0;
    while (suffix < a.size() - prefix
        && suffix < b.size() - prefix
        && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
      suffix++;
    }
    return suffix;
  }

  private static List<Op> diffMiddle(List<String> a, List<String> b) {
    if (a.isEmpty()) {
      List<Op> ops = new ArrayList<>(b.size());
      for (String line : b) {
        ops.add(new Op(Op.Kind.INS, line));
      }
      return ops;
    }
    if (b.isEmpty()) {
      List<Op> ops = new ArrayList<>(a.size());
      for (String line : a) {
        ops.add(new Op(Op.Kind.DEL, line));
      }
      return ops;
    }
    if (a.size() + b.size() > MAX_MIDDLE_SUM) {
      List<Op> coarse = new ArrayList<>(a.size() + b.size());
      for (String line : a) {
        coarse.add(new Op(Op.Kind.DEL, line));
      }
      for (String line : b) {
        coarse.add(new Op(Op.Kind.INS, line));
      }
      return coarse;
    }
    return myers(a, b);
  }

  /** Minimal edit script via the Myers greedy algorithm with a full V trace. */
  private static List<Op> myers(List<String> a, List<String> b) {
    int n = a.size();
    int m = b.size();
    int max = n + m;
    int offset = max;
    int[] v = new int[2 * max + 1];
    List<int[]> trace = new ArrayList<>();
    for (int d = 0; d <= max; d++) {
      if ((long) (d + 1) * v.length * Integer.BYTES > MAX_TRACE_BYTES) {
        List<Op> coarse = new ArrayList<>(n + m);
        for (String line : a) coarse.add(new Op(Op.Kind.DEL, line));
        for (String line : b) coarse.add(new Op(Op.Kind.INS, line));
        return coarse;
      }
      boolean done = false;
      for (int k = -d; k <= d; k += 2) {
        int x;
        if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
          x = v[offset + k + 1];
        } else {
          x = v[offset + k - 1] + 1;
        }
        int y = x - k;
        while (x < n && y < m && a.get(x).equals(b.get(y))) {
          x++;
          y++;
        }
        v[offset + k] = x;
        if (x >= n && y >= m) {
          done = true;
          break;
        }
      }
      trace.add(v.clone());
      if (done) {
        break;
      }
    }
    List<Op> rev = new ArrayList<>(n + m);
    int x = n;
    int y = m;
    for (int d = trace.size() - 1; d >= 1; d--) {
      int[] prev = trace.get(d - 1);
      int k = x - y;
      int prevK =
          (k == -d || (k != d && prev[offset + k - 1] < prev[offset + k + 1])) ? k + 1 : k - 1;
      int prevX = prev[offset + prevK];
      int prevY = prevX - prevK;
      while (x > prevX && y > prevY) {
        x--;
        y--;
        rev.add(new Op(Op.Kind.SAME, a.get(x)));
      }
      if (x == prevX) {
        rev.add(new Op(Op.Kind.INS, b.get(prevY)));
      } else {
        rev.add(new Op(Op.Kind.DEL, a.get(prevX)));
      }
      x = prevX;
      y = prevY;
    }
    while (x > 0 && y > 0) {
      x--;
      y--;
      rev.add(new Op(Op.Kind.SAME, a.get(x)));
    }
    Collections.reverse(rev);
    return rev;
  }

  private static String render(List<Op> script, String oldName, String newName, int skipped) {
    int n = script.size();
    List<int[]> hunks = new ArrayList<>();
    int i = 0;
    while (i < n) {
      if (script.get(i).kind() == Op.Kind.SAME) {
        i++;
        continue;
      }
      int start = i - CONTEXT;
      if (!hunks.isEmpty() && start < hunks.get(hunks.size() - 1)[1]) {
        start = hunks.get(hunks.size() - 1)[1];
      }
      start = Math.max(start, 0);
      int k = i;
      int lastChange = i;
      while (k < n) {
        if (script.get(k).kind() != Op.Kind.SAME) {
          lastChange = k;
        }
        k++;
        if (k > lastChange + 2 * CONTEXT + 1) {
          break;
        }
      }
      int end = Math.min(lastChange + CONTEXT + 1, n);
      hunks.add(new int[] {start, end});
      i = end;
    }
    if (hunks.isEmpty()) {
      return "";
    }
    int totalBody = 0;
    for (int[] hunk : hunks) {
      totalBody += hunk[1] - hunk[0];
    }
    StringBuilder out = new StringBuilder();
    out.append("--- a/").append(oldName).append('\n');
    out.append("+++ b/").append(newName).append('\n');
    int oldLine = skipped;
    int newLine = skipped;
    int covered = 0;
    int emitted = 0;
    for (int h = 0; h < hunks.size(); h++) {
      int[] hunk = hunks.get(h);
      int s = hunk[0];
      int e = Math.min(hunk[1], s + Math.max(MAX_DIFF_LINES - emitted, 0));
      boolean sliced = e < hunk[1];
      if (e <= s) {
        // Budget exhausted exactly at a hunk boundary; this hunk still has body.
        out.append("... (diff truncated, ").append(totalBody - emitted).append(" more lines)\n");
        break;
      }
      while (covered < s) {
        oldLine++;
        newLine++;
        covered++;
      }
      int oldCount = 0;
      int newCount = 0;
      for (int k = s; k < e; k++) {
        Op.Kind kind = script.get(k).kind();
        if (kind != Op.Kind.INS) {
          oldCount++;
        }
        if (kind != Op.Kind.DEL) {
          newCount++;
        }
      }
      out.append("@@ -")
          .append(oldCount == 0 ? oldLine : oldLine + 1)
          .append(',')
          .append(oldCount)
          .append(" +")
          .append(newCount == 0 ? newLine : newLine + 1)
          .append(',')
          .append(newCount)
          .append(" @@\n");
      for (int k = s; k < e; k++) {
        Op op = script.get(k);
        out.append(
                switch (op.kind()) {
                  case SAME -> " ";
                  case DEL -> "-";
                  case INS -> "+";
                })
            .append(op.text())
            .append('\n');
      }
      oldLine += oldCount;
      newLine += newCount;
      covered = e;
      emitted += e - s;
      if (sliced) {
        out.append("... (diff truncated, ").append(totalBody - emitted).append(" more lines)\n");
        break;
      }
    }
    return out.toString();
  }
}
