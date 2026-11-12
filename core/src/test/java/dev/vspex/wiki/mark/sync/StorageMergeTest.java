package dev.vspex.wiki.mark.sync;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StorageMergeTest {
  @Test
  void insertionAtReplacementBoundaryIsOrderedBeforeReplacement() {
    assertEquals(
        "<p>insert</p><p>changed</p>",
        StorageMerge.merge("<p>base</p>", "<p>changed</p>", "<p>insert</p><p>base</p>"));
    assertEquals(
        "<p>insert</p><p>changed</p>",
        StorageMerge.merge("<p>base</p>", "<p>insert</p><p>base</p>", "<p>changed</p>"));
  }

  @Test
  void independentBlocksAndIdenticalChanges() {
    assertEquals(
        "<p>A</p><p>B</p>",
        StorageMerge.merge("<p>a</p><p>b</p>", "<p>A</p><p>b</p>", "<p>a</p><p>B</p>"));
    assertEquals("<p>A</p>", StorageMerge.merge("<p>a</p>", "<p>A</p>", "<p>A</p>"));
  }

  @Test
  void incompatibleEditsAndInsertionsFail() {
    assertThrows(SyncException.class, () -> StorageMerge.merge("<p>a</p>", "<p>b</p>", "<p>c</p>"));
    assertThrows(SyncException.class, () -> StorageMerge.merge("", "<p>b</p>", "<p>c</p>"));
    assertThrows(SyncException.class, () -> StorageMerge.merge("<p>a</p>", "", "<p>c</p>"));
  }

  @Test
  void rawSpellingAndCdataSurvive() {
    String base =
        "<p a='&amp;'>😀</p>\r\n<ac:structured-macro ac:name='code'><ac:plain-text-body><![CDATA[x<y]]></ac:plain-text-body></ac:structured-macro>";
    assertEquals(
        base.replace("😀", "🦊").replace("x<y", "a>b"),
        StorageMerge.merge(base, base.replace("😀", "🦊"), base.replace("x<y", "a>b")));
  }

  @Test
  void unsafeOrHugeInputFails() {
    assertThrows(SyncException.class, () -> StorageMerge.merge("", "<!DOCTYPE x><p/>", ""));
    assertThrows(SyncException.class, () -> StorageMerge.merge("", "<p>", ""));
    assertThrows(SyncException.class, () -> StorageMerge.merge("", "x".repeat(2_000_001), ""));
    assertThrows(
        SyncException.class,
        () ->
            StorageMerge.merge(
                "<p>a</p>".repeat(1100), "<p>b</p>".repeat(1100), "<p>c</p>".repeat(1100)));
  }
}
