package dev.vspex.wiki.mark.cli;

import dev.vspex.wiki.mark.importer.ConfluenceImportSource;
import dev.vspex.wiki.mark.importer.ImportException;
import dev.vspex.wiki.mark.importer.ImportOptions;
import dev.vspex.wiki.mark.importer.PageImporter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Thin CLI adapter over the shared import service. */
final class ImportCommand {
  private static final String HELP =
      """
      Usage: mark import (--page-id ID | --page-url URL) [options]

      Import one existing Confluence page into documents/wiki (read-only remotely).
        --page-id ID              numeric page ID
        --page-url URL            same-site /pages/ID/... or viewpage.action?pageId=ID URL
        --output PATH             relative .md path under documents/wiki
        --overwrite               replace files in this import's output set
        --preview                 print Markdown and paths; no writes or asset downloads
        --lossless                write raw storage markup; no Markdown conversion
        --verify                  recompile in memory and fail on any byte difference (requires --lossless)
        --space KEY               retarget the import into another space (requires --lossless)
        --parent TITLE            retarget under another parent (repeatable, outermost first)
        --title TITLE             retarget with another title (requires --lossless)
        --wiki-dir DIR            wiki root under the project (default documents/wiki)
        --base-url, -b URL         Confluence site URL, including its context path
        --username, -u NAME        Basic auth username; omit for Bearer auth
        --password, -p TOKEN       API token (or CONFLUENCE_TOKEN)
        --trust-store PATH         extra JVM trust store
        --trust-store-password PW  trust store password
        --insecure-skip-tls-verify skip certificate verification
        --help, -h                 show import help
      """;
  private static final Set<String> CONNECTION_VALUES =
      Set.of(
          "--base-url",
          "-b",
          "--username",
          "-u",
          "--password",
          "-p",
          "--trust-store",
          "--trust-store-password");

  private ImportCommand() {}

  static int run(
      String[] args, PrintStream out, PrintStream err, Map<String, String> env, Path project) {
    String id = "", url = "", output = "", space = "", title = "", wikiDir = "";
    boolean overwrite = false, preview = false, help = false, lossless = false, verify = false;
    List<String> parents = new ArrayList<>();
    List<String> connection = new ArrayList<>();
    try {
      for (int i = 0; i < args.length; i++) {
        String name = args[i], value = null;
        int eq = name.indexOf('=');
        if (eq >= 0 && name.startsWith("--")) {
          value = name.substring(eq + 1);
          name = name.substring(0, eq);
        }
        if (Set.of(
                "--overwrite",
                "--preview",
                "--lossless",
                "--verify",
                "--help",
                "-h",
                "--insecure-skip-tls-verify")
            .contains(name)) {
          if (value != null) throw new ImportException("Option " + name + " takes no value");
          switch (name) {
            case "--overwrite" -> overwrite = true;
            case "--preview" -> preview = true;
            case "--lossless" -> lossless = true;
            case "--verify" -> verify = true;
            case "--help", "-h" -> help = true;
            default -> connection.add(name);
          }
        } else {
          if (!CONNECTION_VALUES.contains(name)
              && !Set.of(
                      "--page-id",
                      "--page-url",
                      "--output",
                      "--space",
                      "--parent",
                      "--title",
                      "--wiki-dir")
                  .contains(name)) {
            throw new ImportException("Unknown import option: " + name);
          }
          if (value == null) {
            if (i + 1 >= args.length || args[i + 1].startsWith("-"))
              throw new ImportException("Option " + name + " needs a value");
            value = args[++i];
          }
          if (value.isEmpty() && !name.equals("--parent"))
            throw new ImportException("Option " + name + " needs a nonempty value");
          switch (name) {
            case "--page-id" -> id = value;
            case "--page-url" -> url = value;
            case "--output" -> output = value;
            case "--space" -> space = value;
            case "--parent" -> parents.add(value);
            case "--title" -> title = value;
            case "--wiki-dir" -> wikiDir = value;
            default -> {
              connection.add(name);
              connection.add(value);
            }
          }
        }
      }
      if (help) {
        out.print(HELP);
        return 0;
      }
      var config = Main.parse(connection);
      var client = Main.connect(config, env);
      var report =
          PageImporter.run(
              project,
              new ImportOptions(
                  id, url, output, overwrite, preview, lossless, verify, space, parents, title,
                  wikiDir),
              new ConfluenceImportSource(client));
      out.println(report.summary());
      if (preview) {
        report.files().forEach(path -> out.println("Would write: " + path));
        out.print(report.markdown());
      }
      report.warnings().forEach(warning -> err.println("warning: " + warning));
      report.files().stream()
          .filter(path -> path.toString().endsWith(".confluence-storage.xml"))
          .forEach(path -> out.println("Original storage: " + path));
      return 0;
    } catch (IOException | RuntimeException e) {
      err.println("error: " + e.getMessage());
      return 1;
    }
  }
}
