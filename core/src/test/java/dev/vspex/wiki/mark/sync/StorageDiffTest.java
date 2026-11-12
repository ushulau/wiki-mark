package dev.vspex.wiki.mark.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the git-style storage diff: normalize, pretty-split, hunks, truncation. */
class StorageDiffTest {

  @Test
  void identicalInputsGiveEmptyDiff() {
    assertEquals("", StorageDiff.diffStorage("<p>x</p>", "<p>x</p>", "old", "new"));
    assertEquals("", StorageDiff.diffStorage("", "", "old", "new"));
    assertEquals("", StorageDiff.diffStorage(null, null, "old", "new"));
  }

  @Test
  void singleTagChangeGivesOneHunk() {
    String oldBody = "<p>a</p><p>b</p><p>c</p><p>d</p><p>e</p><p>f</p>";
    String newBody = "<p>a</p><p>b</p><p>C</p><p>d</p><p>e</p><p>f</p>";
    String diff = StorageDiff.diffStorage(oldBody, newBody, "page", "page");
    assertTrue(diff.startsWith("--- a/page\n+++ b/page\n"), diff);
    assertTrue(diff.contains("@@ -1,6 +1,6 @@\n"), diff);
    assertTrue(diff.contains("-<p>c</p>\n"), diff);
    assertTrue(diff.contains("+<p>C</p>\n"), diff);
    assertTrue(diff.contains(" <p>a</p>\n"), diff);
    assertTrue(diff.contains(" <p>f</p>\n"), diff);
  }

  @Test
  void distantChangesGiveTwoHunks() {
    List<String> oldLines = new ArrayList<>();
    List<String> newLines = new ArrayList<>();
    for (int idx = 0; idx < 30; idx++) {
      oldLines.add("<p>" + idx + "</p>");
      newLines.add("<p>" + idx + "</p>");
    }
    newLines.set(1, "<p>one</p>");
    newLines.set(28, "<p>twenty-eight</p>");
    String diff = StorageDiff.unifiedDiff(oldLines, newLines, "old", "new");
    assertEquals(2, diff.split("@@ -", -1).length - 1, diff);
    assertTrue(diff.contains("@@ -1,5 +1,5 @@\n"), diff);
    assertTrue(diff.contains("@@ -26,5 +26,5 @@\n"), diff);
  }

  @Test
  void deletionShrinksNewCount() {
    List<String> oldLines = new ArrayList<>();
    for (int idx = 0; idx < 10; idx++) {
      oldLines.add("<p>" + idx + "</p>");
    }
    List<String> newLines = new ArrayList<>(oldLines);
    newLines.remove(4);
    String diff = StorageDiff.unifiedDiff(oldLines, newLines, "old", "new");
    assertTrue(diff.contains("@@ -2,7 +2,6 @@\n"), diff);
    assertTrue(diff.contains("-<p>4</p>\n"), diff);
  }

  @Test
  void volatileIdsAreIgnored() {
    String oldBody =
        "<ac:structured-macro ac:name=\"info\" ac:macro-id=\"aaa\"><p>x</p></ac:structured-macro>";
    String newBody =
        "<ac:structured-macro ac:name=\"info\" ac:macro-id=\"bbb\"><p>x</p></ac:structured-macro>";
    assertEquals("", StorageDiff.diffStorage(oldBody, newBody, "old", "new"));
    String localBody = "<ac:layout-cell ac:local-id=\"111\"><p>x</p></ac:layout-cell>";
    String remoteBody = "<ac:layout-cell ac:local-id=\"222\"><p>x</p></ac:layout-cell>";
    assertEquals("", StorageDiff.diffStorage(localBody, remoteBody, "old", "new"));
  }

  @Test
  void singleLinePageGivesFocusedHunk() {
    StringBuilder oldBody = new StringBuilder();
    StringBuilder newBody = new StringBuilder();
    for (int idx = 0; idx < 300; idx++) {
      oldBody.append("<p>paragraph ").append(idx).append("</p>");
      if (idx == 150) {
        newBody.append("<p>paragraph edited</p>");
      } else {
        newBody.append("<p>paragraph ").append(idx).append("</p>");
      }
    }
    String diff = StorageDiff.diffStorage(oldBody.toString(), newBody.toString(), "page", "page");
    assertTrue(diff.contains("-<p>paragraph 150</p>\n"), diff);
    assertTrue(diff.contains("+<p>paragraph edited</p>\n"), diff);
    assertEquals(1, diff.split("@@ -", -1).length - 1, diff);
  }

  @Test
  void longLinesAreWrapped() {
    String line = "<p>" + "x".repeat(500) + "</p>";
    List<String> lines = StorageDiff.splitLines(line);
    assertTrue(lines.size() > 1);
    for (String split : lines) {
      assertTrue(split.length() <= StorageDiff.MAX_WRAP, split);
    }
  }

  @Test
  void emptyOldSideGivesZeroCountHeader() {
    String diff = StorageDiff.diffStorage("", "<p>new</p>", "old", "new");
    assertTrue(diff.contains("@@ -0,0 +1,1 @@\n"), diff);
    assertTrue(diff.contains("+<p>new</p>\n"), diff);
  }

  @Test
  void largeDiffIsTruncated() {
    List<String> oldLines = new ArrayList<>();
    List<String> newLines = new ArrayList<>();
    for (int idx = 0; idx < 500; idx++) {
      oldLines.add("<p>old " + idx + "</p>");
      newLines.add("<p>new " + idx + "</p>");
    }
    String diff = StorageDiff.unifiedDiff(oldLines, newLines, "old", "new");
    assertTrue(diff.contains("... (diff truncated, 800 more lines)\n"), diff);
  }

  @Test
  void smallMultiHunkDiffIsNotTruncated() {
    List<String> oldLines = new ArrayList<>();
    List<String> newLines = new ArrayList<>();
    for (int idx = 0; idx < 30; idx++) {
      oldLines.add("<p>" + idx + "</p>");
      newLines.add("<p>" + idx + "</p>");
    }
    newLines.set(1, "<p>one</p>");
    newLines.set(28, "<p>twenty-eight</p>");
    String diff = StorageDiff.unifiedDiff(oldLines, newLines, "old", "new");
    assertTrue(!diff.contains("truncated"), diff);
  }

  @Test
  void cdataAndBlankLinesAreNotNormalizedAway() {
    String body = "<ac:plain-text-body><![CDATA[a\n\nb ac:macro-id=\"one\"]]></ac:plain-text-body>";
    assertTrue(!StorageDiff.diffStorage(body, body.replace("\n\n", "\n"), "a", "b").isEmpty());
    assertTrue(!StorageDiff.diffStorage(body, body.replace("one", "two"), "a", "b").isEmpty());
    assertEquals(
        "<p title=' ac:macro-id=\"literal\"'>x</p>",
        StorageDiff.normalize("<p title=' ac:macro-id=\"literal\"' ac:local-id = 'id'>x</p>"));
  }

  @Test
  void nearbyChangesMergeContextAndWorstCaseIsBounded() {
    var oldLines = new ArrayList<String>();
    for (int i = 0; i < 20; i++) oldLines.add("line" + i);
    var newLines = new ArrayList<>(oldLines);
    newLines.set(3, "changed");
    newLines.set(9, "also changed");
    assertEquals(
        1, StorageDiff.unifiedDiff(oldLines, newLines, "a", "b").split("@@ -", -1).length - 1);
    var a = new ArrayList<String>();
    var b = new ArrayList<String>();
    for (int i = 0; i < 2000; i++) {
      a.add("old" + i);
      b.add("new" + i);
    }
    assertTrue(StorageDiff.unifiedDiff(a, b, "a", "b").contains("truncated"));
    assertEquals(
        "@@ -1,1 +0,0 @@",
        StorageDiff.unifiedDiff(List.of("a"), List.of(), "a", "b")
            .lines()
            .skip(2)
            .findFirst()
            .orElseThrow());
  }
}
