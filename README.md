# confluence-publish-gradle-plugin

Standalone Gradle plugin (Java 17) that renders `.mmd` diagrams to images and
publishes `.md` pages to Confluence and imports existing pages as Markdown
(Cloud / Data Center / Server v1 REST API).

- Plugin id: `dev.vspex.confluence-publish`
<!-- wiki-mark-version:start -->
- Group: `dev.vspex`, version `0.8.0`
<!-- wiki-mark-version:end -->

## Requirements

- Gradle 8.5+ and JDK 17+ to use the plugin. Building this repository requires
  JDK 21 and dependency access; the wrapper uses Gradle 8.11.1 and emits Java 17 bytecode.
  The Gradle daemon is pinned to Java 21 in `gradle/gradle-daemon-jvm.properties`;
  `mise.toml` also selects Temurin 21 for the shell. Install it with `mise install`
  if using mise, or install a JDK 21 discoverable by Gradle.
- A container engine (`docker` or `podman`) on `PATH`, only if you enable
  diagram conversion.
- A Confluence API token (Cloud: classic token, see below) or Personal Access
  Token (Data Center / Server).

## Installation

`settings.gradle`:

```groovy
pluginManagement {
  repositories {
    mavenLocal()
    gradlePluginPortal() // or your internal Nexus mirror
  }
}
```

`build.gradle`:

<!-- wiki-mark-version:start -->
```groovy
plugins {
  id 'dev.vspex.confluence-publish' version '0.8.0'
}
```
<!-- wiki-mark-version:end -->

No dependency on the `java` plugin; safe to apply to the root or any project.
It supports Gradle's configuration cache.

`build` also runs `updateReadmeVersion`, which synchronizes marked version
references in this README from the plugin version in the root `build.gradle`.
Run `.\gradlew.bat updateReadmeVersion` to update documentation without running
the full build. Keep the `wiki-mark-version` HTML comment markers around the
managed blocks; only version values inside those blocks are updated. The task
writes the file only when a value changes. Commit README updates alongside a
version change so the displayed examples match.

## Basic setup

1. Put pages under `documentation/wiki` (the default `pagesDir`):

```markdown
<!-- Space: MYSPACE -->
<!-- Title: My Page -->
<!-- Parent: My Parent Page -->

# My Page

Body text.
```

2. Configure the connection (`build.gradle`):

<!-- wiki-mark-version:start -->
```groovy
plugins {
  id 'dev.vspex.confluence-publish' version '0.8.0'
}

confluence {
  baseUrl = 'https://<tenant>.atlassian.net/wiki'
  space = 'MYSPACE'
  username = 'me@example.com' // Cloud only, see Authentication
}
```
<!-- wiki-mark-version:end -->

3. Store the token as described below, then compile offline, preview, and publish:

```
./gradlew confluenceCompile
./gradlew confluencePublish --preview
./gradlew confluencePublish
```

On Windows, use `.\gradlew.bat` in place of `./gradlew`.

## Authentication: where to store keys

The token is resolved in this order (first wins):

1. Explicit `confluence { authToken = ... }` (a value or a `Provider`) —
   convenient for CI injection, never hardcode a real token in the repo.
2. `-Pconfluence.token=...` on the command line.
3. The `CONFLUENCE_TOKEN` environment variable.

Recommended for developers: `~/.gradle/gradle.properties` (outside every
repository, so it can never be committed):

```properties
confluence.token=<API token or PAT>
```

Cloud vs Data Center:

- Cloud: `baseUrl` must be the site URL `https://<tenant>.atlassian.net/wiki`
  (the bare tenant without `/wiki` 404s, and `api.atlassian.com` gateway URLs
  are refused). Set `username` to the Atlassian account email, so the plugin
  sends Basic auth (`email:token`). The token must be a **classic API token
  without scopes** (Security → API tokens at `id.atlassian.com`), created
  while logged in as that same email: scoped tokens only work through the
  `api.atlassian.com` gateway this plugin does not use. See Atlassian's
  [Basic auth documentation](https://developer.atlassian.com/cloud/confluence/basic-auth-for-rest-apis/)
  and [scoped token endpoint requirements](https://support.atlassian.com/confluence/kb/scoped-api-tokens-in-confluence-cloud/).
- Data Center / Server with a Personal Access Token: omit `username` and the
  plugin sends Bearer auth.

Safety rules, enforced by the plugin: the token is never logged, never
appears in exception messages, and is excluded from up-to-date fingerprinting
(`@Internal`). The trust-store password gets the same treatment. `preview`
needs the token too (it resolves against the live wiki); `confluenceImport` also
requires the token and read access to the selected page and its attachments.
`compile` never needs a token.
`confluence.token` is a Gradle project property, so it can also come from
`gradle.properties`; a command-line `-P` value overrides that file. An explicitly
empty property overrides the environment variable and fails publishing.

## Tasks

See the [command cheat sheet](docs/command-cheat-sheet.md) for commands, parameters, and short explanations.

| Task | Purpose |
|---|---|
| `confluenceRenderDiagrams` | `.mmd` → images via a pinned mermaid-cli container. Skips when up-to-date (sources, config, and the `--only` selection are all tracked). `--only=<name>` (repeatable, `.mmd` suffix optional) renders selected diagrams. |
| `confluenceCompile` | Runs render first so images are fresh (a no-op unless conversion is enabled), then checks all pages offline to `build/confluence/storage`. No network, no token. Joins `check` only when `verifyOnCheck = true`. |
| `confluencePublish` | Runs render + compile, then syncs to Confluence. `--preview` resolves ancestry and prints storage without writing; `--changed` (or `onlyChanged = true` in config) checks all selected pages remotely to detect wiki edits even when local digests match; `--only=<file>` (repeatable, relative to `pagesDir`) wins over `--changed`. Never wired into `build`/`check`. |
| `confluenceResetState` | Deletes the change-tracking state (`.gradle/confluence-publish/state.properties`, which survives `clean`; a legacy `documentation/wiki/.published-hashes` is imported once). |
| `confluenceImport` | Reads one existing page beneath `confluence.pagesDir`. Select `--page-id` or `--page-url`; use `--lossless --verify` for exact storage preservation, or optional `--output`, `--overwrite`, and `--preview`. Never wired into `build`/`check` or publishing. |

Task options belong to the task immediately preceding them:

```sh
./gradlew confluencePublish --preview --only=guide.md --only=reference.md
./gradlew confluencePublish --changed
./gradlew confluenceRenderDiagrams --only=architecture --only=workflow.mmd
```

Publish selection does not narrow the render or compile dependencies: they still
process their configured inputs. `--preview` prevents remote writes and page-state
updates, but its dependencies still write local storage and enabled diagram outputs.
Gradle's `--dry-run` only lists tasks; use `--preview` for a live publishing preview.

`confluenceCompile` checks parsing and rendering; it does not require a title or
space, resolve live ancestry, or check explicit attachment patterns. Publishing
requires both a resolved title and space. The publish preview includes the `article` layout wrapper.

Publishing always compares raw local and remote storage and records a version-message
hash plus per-page successful-publish state. Local-only edits publish silently;
remote drift or unknown baselines show a unified diff and require an interactive
confirmation or `--force`. A lossless import records the baseline, so editing an
import and publishing needs no `--force`. Conflict output names the remote and
baseline versions, the last editor and time, and a unified diff; run with
`--force` after reviewing the diff, or re-import to refresh the baseline.
Preview never writes. Concurrent HTTP 409 updates fail
without retrying against a newer version. The drift check compares raw storage
hashes. Publish records both submitted markup and the verified stored body, so
server rewriting does not cause repeated drift. Matching local input, render
context, remote body and version skip writes, including with `--force`. Older
ambiguous history may need one reviewed forced publish to establish this tracking.
See [0.8.0 release notes](docs/release-notes/0.8.0.md).

`--changed` / `onlyChanged` check all selected pages remotely; local digests cannot
hide wiki edits. `changesOnly` is retained for compatibility: identical storage
always skips all remote writes, including attachments and labels. Attachment-only
or label-only edits therefore require a body change to publish. `--force` permits
overwriting drift but does not bypass raw-equality or verified-history skipping, or version checks.
`confluenceResetState` clears legacy page and diagram hashes while preserving
publish baselines. See [import and drift behavior](docs/confluence-import.md).

## Import an existing Confluence page

Use the same `confluence` connection and authentication settings as publishing.
The page supplies its own space; setting `confluence.space` is optional for imports.

```sh
./gradlew confluenceImport --page-id=123456 --preview
./gradlew confluenceImport --page-id=123456 --output=engineering/onboarding.md
./gradlew confluenceImport --page-url="https://example.atlassian.net/wiki/spaces/ENG/pages/123456/Onboarding"
./gradlew confluenceImport --page-id=123456 --output=engineering/onboarding.md --overwrite
./gradlew confluenceImport --page-id=123456 --lossless --verify --output=releases/2026.md
```

On Windows, use `.\gradlew.bat`. Specify exactly one selector. Supported page URLs
contain `/pages/<numeric-id>/...` or `viewpage.action?pageId=<numeric-id>` and must
belong to the configured site and context path. For short links, use the page ID.

The output is relative to the project owning the task. `--output` is a relative
`.md` path under `confluence.pagesDir` (default `documentation/wiki`); the filename defaults to
`<sanitized-title>-<page-id>.md`. Absolute paths, traversal, reserved Windows names,
and symlink/junction paths are rejected. Existing output files require `--overwrite`.
`--preview` prints Markdown, planned paths, and warnings without creating files or
downloading attachment bodies; collision checks still apply.

Use `--lossless --verify` to preserve the raw body exactly, including macros,
layout, entities, CDATA, and original line endings. Lossless mode emits HTML comment
headers and marker-wrapped storage. `--space`, `--parent`, and `--title` retarget
headers without modifying storage and require lossless mode. A successful
non-preview lossless import that targets the source page records a publish
baseline (version plus storage hash) under `.gradle/confluence-publish/baselines`;
previews, retargeted imports, lossy imports, and `--no-baseline` skip it with an
explanatory line.

Default Markdown imports preserve supported text, headings, lists, code, and tables, download
referenced attachments into `<document-stem>.assets/`, and rewrite their links.
Page links remain remote. Metadata includes the original space, title, ancestors,
labels, and explicit non-image attachments. A sibling `.confluence.json` manifest
records the source page and version. Lossy conversions produce warnings and retain
the original storage in a `.confluence-storage.xml` sidecar. Metadata that the
existing parser cannot preserve, including embedded newlines, fails before writing.

Confluence stays read-only during import, but a qualifying lossless import writes
one local publishing-state file: the publish baseline described above. Files are
staged before committing, with rollback on replacement failures; Markdown is
installed last. Downloads are limited to 50 MiB per asset and 200 MiB per import,
with a 30-second request timeout and at most five redirects. HTTPS CDN redirects
receive no credentials. External images are kept as URLs.

Gradle import, compile, and publishing share `confluence.pagesDir`, defaulting to
`documentation/wiki`. To choose another wiki root, set:

```groovy
confluence {
  pagesDir = layout.projectDirectory.dir('documents/wiki')
}
```

Review the Markdown and warnings before publishing. Imports are snapshots; the
manifest does not bind publishing to a page ID or merge remote edits. See the
[import reference](docs/confluence-import.md) for conversion details, recovery,
CLI usage, and limitations.

## Diagrams

`.mmd` sources live directly in `diagrams/` under `pagesDir` (not recursively)
(`documentation/wiki/diagrams` by default); pages embed the outputs as
`diagrams/<name>.png`. Conversion is **opt-in** — both flags default to
`false`, and when neither is set the task logs and skips without needing a
container engine:

```groovy
confluence {
  diagrams {
    convertToPng = true // render .mmd -> .png (the format Confluence shows inline)
    convertToSvg = true // additionally render .mmd -> .svg
  }
}
```

Note: Confluence serves SVG attachments for download rather than inline, so
PNG is the practical format for published pages; SVG is for external reuse.

Change detection is per diagram: each render records
`diagram/<name>.<format> = <inputHash>:<outputHash>` in the shared state file
(`.gradle/confluence-publish/state.properties`, alongside the page digests). A
diagram re-renders only when its `.mmd` or settings changed, or its output is
missing or tampered with; everything else skips, and the run ends with a
`rendered X images, Y up-to-date` summary. A deleted `.mmd` drops its keys on
the next full run (`--only` runs never prune).

Full block (defaults shown):

```groovy
confluence {
  diagrams {
    convertToPng = false
    convertToSvg = false
    // sourceDir defaults to pagesDir/diagrams
    containerImage = 'minlag/mermaid-cli:12.0.1' // tag pinned, digest in reference/PROVENANCE.md
    containerEngine = 'docker'     // or 'podman'
    // extraRunArgs default per engine: docker -> [], podman -> ['--userns=keep-id']
    scale = 2                      // mmdc -s
    background = 'white'           // mmdc -b
    // mermaidJson / puppeteerJson default to the reference render.sh blobs
    // (HTML labels disabled: Confluence cannot render them)
  }
}
```

Skip conversion for one build with `-x confluenceRenderDiagrams`. A missing
engine binary fails with install/skip instructions; a render failure reports
the diagram, the exit code, and the container output.

## Page headers

HTML comments at the top of each page (typos such as `Titel` fail the build):

```markdown
<!-- Space: MYSPACE -->
<!-- Title: My Page -->
<!-- Parent: My Parent Page -->
<!-- Label: demo -->
```

Use one header comment per line before the Markdown body. Header names are
case-insensitive. Per-page values override DSL defaults; `Parent` entries are
appended after the configured `parents` list.

| HTML header | YAML key | Behavior |
|---|---|---|
| `Space` | `space` | Space key; falls back to `confluence.space`. |
| `Title` | `title` | Explicit title; otherwise use the configured title derivation. |
| `Type` | `type` | REST content type, default `page`; support depends on the server. |
| `Parent` (repeatable) | `parents` (list) | Parent chain; explicit parents suppress directory-derived parents. |
| `Label` (repeatable) | `labels` (list) | Remote labels; replacement or append follows `appendLabels`. |
| `Attachment` (repeatable) | `attachments` (list) | File/glob relative to the page directory; each pattern must match during publish. Matching local links become Confluence attachment links without requiring `attachReferenced`. |
| `Content-Appearance` | `content-appearance` | `full-width`, `fixed`, or `default`; effective default is `full-width`. |
| `Image-Align` | `image-align` | `left`, `center`, or `right`. |
| `Image-Width`, `Image-Height` | `image-width`, `image-height` | Positive pixel caps for local PNG/SVG images, scaled proportionally; only the binding dimension is written to the page. |
| `Layout`, `Sidebar` | `layout`, `sidebar` | `article` wraps published storage in two columns; sidebar is raw storage markup. Other layouts leave storage unchanged. |
| `Emoji` | `emoji` | Passed with page updates. |
| `Synchronized` | `synchronized` | `false` skips the page in compile and publish. |
| `Folder` (repeatable) | `folders` (list) | Parsed, then warned about and ignored. |
| `Property` (`key=value`) | `properties` (mapping) | Parsed, then warned about; content properties are not applied. |
| `Order` | `order` | Integer recorded in metadata; sibling ordering is not applied. |
| `Include` | Not supported | Markers are not expanded; body markers produce warnings. |

A YAML front-matter block is accepted as an alternative:

```markdown
---
space: MYSPACE
title: My Page
parents:
  - My Parent Page
labels:
  - demo
attachments:
  - downloads/*.pdf
content-appearance: full-width
image-align: center
synchronized: true
---

# My Page

Body text.
```

YAML uses a small parser for scalars, lists, and mappings, not a full YAML engine.
Keys are case-insensitive and ignore hyphens/underscores. HTML headers following
front matter override scalars and append to lists. Unknown YAML keys are warned
about and ignored; likely HTML header typos such as `Titel` fail parsing.

## Extended setup

This example uses H1 titles, directory-derived parents, local change selection,
offline verification during `check`, and PNG diagrams. Apply `base` to supply
`check` in a project without another lifecycle plugin such as `java`.

<!-- wiki-mark-version:start -->
```groovy
plugins {
  id 'base'
  id 'dev.vspex.confluence-publish' version '0.8.0'
}

confluence {
  baseUrl = 'https://<tenant>.atlassian.net/wiki'
  username = 'me@example.com'
  space = 'MYSPACE'
  pagesDir = layout.projectDirectory.dir('docs/wiki')
  pageIncludes = ['**/*.md']
  titleFromH1 = true
  titleFromFilename = false
  titleAppendHash = false
  dropH1 = true
  parents = ['Engineering']
  parentsFromPath = true
  parentsFromPathRoot = layout.projectDirectory.dir('docs/wiki').asFile.absolutePath
  imageHeight = '800'
  imageWidth = '1200'
  imageAlign = 'center'
  contentAppearance = 'full-width'
  appendLabels = true
  minorEdit = true
  versionMessage = 'Published by Gradle'
  changesOnly = true
  onlyChanged = true
  attachReferenced = true
  continueOnError = true
  verifyOnCheck = true
  diagrams {
    convertToPng = true
    convertToSvg = false
    sourceDir = layout.projectDirectory.dir('docs/wiki/diagrams')
    containerImage = 'minlag/mermaid-cli:12.0.1'
    containerEngine = 'docker'
    extraRunArgs = []
    scale = 2
    background = 'white'
  }
}
```
<!-- wiki-mark-version:end -->

Put an H1 in each page under `docs/wiki`, then run `./gradlew check` or
`./gradlew confluencePublish --preview`. Diagram conversion requires a running
container engine and access to the container image.

Optional connection overrides (inside `confluence`), for CI or a corporate CA:

```groovy
authToken = providers.environmentVariable('WIKI_TOKEN')
trustStore = layout.projectDirectory.file('certificates/wiki-truststore.jks')
trustStorePassword = providers.environmentVariable('WIKI_TRUSTSTORE_PASSWORD')
insecureSkipTlsVerify = false
```

All properties are lazy and accept Gradle providers. Omit what you do not set;
never assign `null`. Overriding `authToken` changes the default token resolution.

## Complete configuration reference

These are all properties on the `confluence` extension. Empty strings mean unset
unless a fallback is described. Boolean defaults are `false`.

| Property | Type / default | Purpose |
|---|---|---|
| `baseUrl` | String, `''` | Required for publishing and importing, including preview. |
| `authToken` | String, Gradle `confluence.token` then `CONFLUENCE_TOKEN` | Required for publishing and importing, including preview. |
| `username` | String, `''` | Nonempty selects Basic auth; empty selects Bearer auth. |
| `space` | String, `''` | Fallback space key for pages. |
| `pagesDir` | Directory, `documentation/wiki` | Base directory for page discovery. |
| `pageIncludes` | List of strings, `['**/*.md']` | Paths/globs relative to `pagesDir`, deduplicated and sorted. |
| `imageHeight` | String, `''` | Max pixel height cap for local PNG/SVG images, scaled proportionally; page header wins. Only the binding dimension is written to the page. |
| `imageWidth` | String, `''` | Max pixel width cap for local PNG/SVG images, scaled proportionally; page header wins. Only the binding dimension is written to the page. |
| `imageAlign` | String, `''` | `left`, `center`, or `right`; page header wins. |
| `contentAppearance` | String, `''` | `full-width`, `fixed`, or `default`; header wins, otherwise `full-width`. |
| `titleFromH1` | Boolean, `false` | Derive a missing title from the first H1 outside code fences. |
| `titleFromFilename` | Boolean, `false` | Derive a missing title from the filename, replacing hyphens/underscores with spaces and title-casing. Mutually exclusive with `titleFromH1`. |
| `titleAppendHash` | Boolean, `false` | Append a short hash of parents, space, and title to the resolved title. |
| `dropH1` | Boolean, `false` | Omit the first H1 from rendered content. |
| `parents` | List of strings, `[]` | Prepend a parent chain to page-declared parents. Missing parents are created during publish. |
| `parentsFromPath` | Boolean, `false` | Derive parent titles from directories when the page has no explicit parents. Index/README pages represent their own directory. |
| `parentsFromPathRoot` | String, `''` | Root for directory-derived parents; empty uses `pagesDir`. Prefer an absolute path. |
| `appendLabels` | Boolean, `false` | Add labels without removing existing ones. Default replaces labels, including clearing them for pages without labels. |
| `minorEdit` | Boolean, `false` | Mark page updates as minor edits. |
| `versionMessage` | String, `''` | Message sent with page updates. |
| `changesOnly` | Boolean, `false` | Compatibility flag; exact storage equality always skips remote writes. |
| `attachReferenced` | Boolean, `false` | Also upload local files referenced by links. Local images are collected without this flag. |
| `continueOnError` | Boolean, `false` | Process remaining pages after a failure; task still fails if any page failed. |
| `onlyChanged` | Boolean, `false` | Compatibility flag; remotely check every selected page for drift; `--only` takes precedence. |
| `verifyOnCheck` | Boolean, `false` | Wire compile into `check` if a lifecycle plugin providing `check` is applied. |
| `trustStore` | File, unset | Extra trust store augmenting the JVM's default store. |
| `trustStorePassword` | String, `''` | Password for the extra trust store. |
| `insecureSkipTlsVerify` | Boolean, `false` | Skip certificate and hostname verification; logs a warning. For testing only. |
| `diagrams` | Nested block | The rendering settings below. |

All properties inside `confluence { diagrams { ... } }`:

| Property | Type / default | Purpose |
|---|---|---|
| `convertToPng` | Boolean, `false` | Generate PNG images next to the sources. |
| `convertToSvg` | Boolean, `false` | Generate SVG images next to the sources. Both formats may be enabled. |
| `sourceDir` | Directory, `pagesDir/diagrams` | Directory containing `.mmd` files. |
| `containerImage` | String, `minlag/mermaid-cli:12.0.1` | Mermaid CLI container image. |
| `containerEngine` | String, `docker` | `docker` or `podman`. |
| `extraRunArgs` | List of strings, `[]` for Docker, `['--userns=keep-id']` for Podman | Engine flags before the image; an explicit list replaces the default. |
| `scale` | Integer, `2` | Positive Mermaid CLI scale (`-s`). |
| `background` | String, `white` | Nonblank Mermaid CLI background (`-b`). |
| `mermaidJson` | String, JSON below | Mermaid config contents (`-c`), not a file path. |
| `puppeteerJson` | String, JSON below | Puppeteer config contents (`-p`), not a file path. |

Default JSON values, also usable as explicit overrides inside `diagrams`:

```groovy
mermaidJson = '{"htmlLabels": false, "flowchart": {"htmlLabels": false}, "state": {"htmlLabels": false}, "fontFamily": "Arial, Helvetica, sans-serif"}'
puppeteerJson = '{"executablePath": "/usr/bin/chromium-browser", "args": ["--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage"]}'
```

The compile output location is a task property rather than an extension setting:

```groovy
tasks.named('confluenceCompile') {
  outputDir = layout.buildDirectory.dir('wiki-storage')
}
```

Import task properties `pageId`, `pageUrl`, `output`, `overwrite`, `preview`,
`lossless`, `verify`, `space`, `parents`, and `title` correspond to its command-line
options. The `wikiDir` task property follows `confluence.pagesDir` (default
`documentation/wiki`). The CLI defaults to `documents/wiki` and supports `--wiki-dir`.

Publish task properties `preview`, `force`, `changed`, and `onlyFiles` correspond to the
`--preview`, `--force`, `--changed`, and `--only` options; render's `onlyDiagrams` corresponds to its
`--only` option. They may also be set through `tasks.named(...)`.

## Troubleshooting

- Build fails creating `Test` / `DefaultReportContainer` with `Type T not present`:
  check `./gradlew --version`. This failure was observed when Gradle 8.11.1 ran on
  Java 25, outside its [supported Java runtime range](https://docs.gradle.org/8.11.1/userguide/compatibility.html).
  The committed daemon JVM criteria select Java 21 independently of `JAVA_HOME`;
  keep that file present and select Java 21 as the Gradle JVM in your IDE too.
  With mise, run `mise install`, then `mise exec -- java -version`. To use its
  environment explicitly, run `mise exec -- ./gradlew clean build` (Windows:
  `mise exec -- .\gradlew.bat clean build`). If your current shell still selects
  Java 25, activate the mise environment or set `JAVA_HOME` to your installed JDK 21.
  The Java compilation toolchain alone does not select Gradle's runtime JVM.
- HTML 404 titled `Page Unavailable`: `baseUrl` is missing `/wiki` (Cloud).
- `403 "caller cannot access Confluence"`: the token is invalid/expired, the
  `username` email does not own the token, the account cannot access
  Confluence, or the token has scopes (mint a classic scopeless token).
  Verify by logging into the wiki in a browser as that email and checking the
  token list under the same account.
- `Container engine '...' not found on PATH`: install the engine or skip with
  `-x confluenceRenderDiagrams` (or leave the convert flags off). Note the
  binary being present is not enough — the Docker daemon must be running.
- `rendering '....mmd' failed (exit N)`: the container output follows in the
  message; usually a mermaid syntax error or a daemon/network problem.
- Import reports `HTTP 401/403/404`: check the credentials, page ID, and page/asset
  read permissions. Error responses are summarized without exposing server bodies.
- Import reports unsupported storage or conversion warnings: see the
  [import reference](docs/confluence-import.md#conversion-and-limitations).
- Import reports an output collision: choose another path, or use `--overwrite`
  after reviewing the existing document. It replaces files in this import's output
  set and leaves unrelated or stale assets alone.

## Project layout

- `core/` — plain Java library, no Gradle API (bundled into the plugin jar).
- `cli/` — thin `main()` over core (`sync` and `import`).
- `gradle-plugin/` — the plugin: `confluence` extension + the five tasks.
- `docs/` — feature references, including Confluence-to-Markdown imports.
- `reference/` — Python reference copies, shell transcripts, provenance.

Build with `./gradlew build` (JDK 21 toolchain, Gradle 8.5+). Tests: JUnit 5
(unit) + TestKit (functional); no other test frameworks. Dependencies stay
lean by convention (JDK first); `core` ships bundled so consumers resolve a
single artifact.

This repository's root `build` depends on `spotlessApply` for every module;
formatting runs before Java compilation and Spotless checks. Format separately
with `./gradlew :spotlessApply`. These are repository build settings; applying
the published Confluence plugin does not install Spotless into a consumer project.

### Merging publish drift

Use `./gradlew confluencePublish --merge --preview` to review a body merge, then
`./gradlew confluencePublish --merge` to publish it. CLI equivalents are `--merge --dry-run`
and `--merge`. Conflicting edits stop without remote writes; `--merge` and `--force`
are mutually exclusive.

Merge tracking needs full snapshots from a successful publish or an exact local/remote match.
Older hash-only baselines cannot merge existing differences. Existing-page merges preserve
remote labels, attachments, ancestry, appearance, and emoji. New attachment references must
already exist remotely. Local auxiliary changes are deferred. Source Markdown stays unchanged;
keep the ignored baseline directory to retain remote contributions across future publishes.
An ordinary publish requires explicit overwrite consent when it would remove those contributions.

See [merge behavior and validation](docs/publish-merge.md) for limits and recovery guidance.

A qualifying lossless re-import also clears older merge snapshots for that page. Exact local
baseline evidence at the fetched version overrides stale copied version-message hashes.
Baselines belong to each checkout; after deleting `.gradle`, use a lossless import to establish
them again. See the [0.7.0 fix notes](docs/release-notes/0.7.0.md).
