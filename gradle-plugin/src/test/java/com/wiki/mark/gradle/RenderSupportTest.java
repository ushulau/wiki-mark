package com.wiki.mark.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wiki.mark.sync.SyncException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenderSupportTest {

  @TempDir Path dir;

  @Test
  void validateAcceptsBothEngines() {
    RenderSupport.validate("docker", 2, "white");
    RenderSupport.validate("podman", 1, "transparent");
  }

  @Test
  void validateRejectsUnknownEngine() {
    SyncException e =
        assertThrows(SyncException.class, () -> RenderSupport.validate("rocket", 2, "white"));
    assertTrue(e.getMessage().contains("containerEngine"), e.getMessage());
  }

  @Test
  void validateRejectsBadScaleAndBackground() {
    assertThrows(SyncException.class, () -> RenderSupport.validate("docker", 0, "white"));
    assertThrows(SyncException.class, () -> RenderSupport.validate("docker", 2, "  "));
  }

  @Test
  void extraRunArgsDefaultPerEngine() {
    assertEquals(List.of(), RenderSupport.defaultExtraRunArgs("docker"));
    assertEquals(List.of("--userns=keep-id"), RenderSupport.defaultExtraRunArgs("podman"));
  }

  @Test
  void containerArgsPutRunFlagsBeforeImageAndMmdcFlagsAfter() {
    List<String> args =
        RenderSupport.containerArgs(
            dir,
            dir.resolve("cfg"),
            "img:1",
            List.of("--userns=keep-id"),
            "white",
            2,
            "flow",
            "png");

    int image = args.indexOf("img:1");
    assertTrue(image > 0, args.toString());
    assertTrue(args.indexOf("--userns=keep-id") < image, args.toString());
    assertEquals("/data/flow.mmd", args.get(args.indexOf("-i") + 1));
    assertEquals("/data/flow.png", args.get(args.indexOf("-o") + 1));
    assertEquals("white", args.get(args.indexOf("-b") + 1));
    assertEquals("2", args.get(args.indexOf("-s") + 1));
    assertEquals("/cfg/puppeteer.json", args.get(args.indexOf("-p") + 1));
    assertEquals("/cfg/mermaid.json", args.get(args.indexOf("-c") + 1));
  }

  @Test
  void containerArgsSvgOutput() {
    List<String> args =
        RenderSupport.containerArgs(
            dir, dir.resolve("cfg"), "img:1", List.of(), "white", 2, "flow", "svg");

    assertEquals("/data/flow.svg", args.get(args.indexOf("-o") + 1));
  }

  @Test
  void containerArgsRejectsUnknownFormat() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RenderSupport.containerArgs(
                dir, dir.resolve("cfg"), "img:1", List.of(), "white", 2, "flow", "pdf"));
  }

  @Test
  void resolveDiagramsSelectsSorted() throws Exception {
    Files.writeString(dir.resolve("b.mmd"), "b", StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("a.mmd"), "a", StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("note.txt"), "x", StandardCharsets.UTF_8);

    List<Path> all = RenderSupport.resolveDiagrams(dir, List.of());
    assertEquals(List.of("a.mmd", "b.mmd"), fileNames(all));

    List<Path> only = RenderSupport.resolveDiagrams(dir, List.of("b.mmd"));
    assertEquals(List.of("b.mmd"), fileNames(only));

    SyncException e =
        assertThrows(SyncException.class, () -> RenderSupport.resolveDiagrams(dir, List.of("zzz")));
    assertTrue(e.getMessage().contains("zzz"), e.getMessage());
  }

  @Test
  void resolveDiagramsMissingDirIsEmpty() {
    assertEquals(List.of(), RenderSupport.resolveDiagrams(dir.resolve("nope"), List.of()));
  }

  @Test
  void containerPathUsesForwardSlashes() {
    assertTrue(!RenderSupport.containerPath(dir).contains("\\"), dir.toString());
  }

  @Test
  void engineNotFoundMessageNamesSkip() {
    assertTrue(
        RenderSupport.engineNotFoundMessage("docker").contains("-x confluenceRenderDiagrams"));
  }

  @Test
  void onPathFindsBinaryAndExe() throws Exception {
    Path bin = dir.resolve("bin");
    Files.createDirectories(bin);
    Path plain = Files.writeString(bin.resolve("mydock"), "x", StandardCharsets.UTF_8);
    Path exe = Files.writeString(bin.resolve("podx.exe"), "x", StandardCharsets.UTF_8);
    plain.toFile().setExecutable(true);
    exe.toFile().setExecutable(true);

    assertTrue(RenderSupport.onPath("mydock", bin.toString()));
    assertTrue(RenderSupport.onPath("podx", bin.toString()));
  }

  @Test
  void onPathMissingIsFalse() {
    assertTrue(!RenderSupport.onPath("no-such-binary-xyz", dir.toString()));
    assertTrue(!RenderSupport.onPath("docker", ""));
    assertTrue(!RenderSupport.onPath("docker", null));
  }

  @Test
  void diagramDigestCoversSourcesAndConfig() {
    byte[] mmd = "flowchart".getBytes(StandardCharsets.UTF_8);
    String base = digest(mmd, 2);
    assertEquals(base, digest(mmd, 2));
    assertTrue(!base.equals(digest(mmd, 3)));
    assertTrue(!base.equals(digest("other".getBytes(StandardCharsets.UTF_8), 2)));
  }

  @Test
  void needsRenderMatrix() throws Exception {
    Path png = dir.resolve("flow.png");
    byte[] image = "fake-png".getBytes(StandardCharsets.UTF_8);
    Files.write(png, image);
    String input = digest("mmd".getBytes(StandardCharsets.UTF_8), 2);
    String recorded = input + ":" + RenderSupport.shaHex(image);

    assertTrue(!RenderSupport.needsRender(recorded, input, png));
    assertTrue(RenderSupport.needsRender(null, input, png));
    assertTrue(RenderSupport.needsRender("malformed", input, png));
    assertTrue(RenderSupport.needsRender("deadbeef:" + RenderSupport.shaHex(image), input, png));
    assertTrue(RenderSupport.needsRender(input + ":deadbeef", input, png));
    assertTrue(RenderSupport.needsRender(recorded, input, dir.resolve("gone.png")));
  }

  @Test
  void prunesKeysForRemovedSources() throws Exception {
    Files.writeString(dir.resolve("kept.mmd"), "a", StandardCharsets.UTF_8);
    Map<String, String> state =
        new LinkedHashMap<>(
            Map.of(
                "diagram/kept.png", "x:y",
                "diagram/gone.png", "x:y",
                "01-page.md", "abc"));

    RenderSupport.pruneRemovedDiagrams(state, dir);

    assertEquals(Set.of("diagram/kept.png", "01-page.md"), state.keySet());
  }

  @Test
  void stateKeyFormat() {
    assertEquals("diagram/flow.png", RenderSupport.stateKey("flow", "png"));
    assertEquals("diagram/flow.svg", RenderSupport.stateKey("flow", "svg"));
  }

  private static String digest(byte[] mmd, int scale) {
    return RenderSupport.diagramDigest(
        mmd, "img:1", "docker", List.of(), "white", scale, "{}", "{}");
  }

  private static List<String> fileNames(List<Path> paths) {
    return paths.stream().map(path -> path.getFileName().toString()).toList();
  }
}
