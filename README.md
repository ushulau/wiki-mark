# confluence-publish-gradle-plugin

Standalone Gradle plugin (Java 17) that renders `.mmd` diagrams to images and
publishes `.md` pages to Confluence (Cloud / Data Center / Server v1 REST API).

- Plugin id: `com.wiki.mark.confluence-publish`
- Group: `com.wiki.mark`, version `0.1.0`

## Requirements

- Gradle 8.5+ to run; JDK 21 + network to build (emits Java 17 bytecode).
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

```groovy
plugins {
  id 'com.wiki.mark.confluence-publish' version '0.1.0'
}
```

No dependency on the `java` plugin; safe to apply to the root or any project.

## Quick start

1. Put pages under `documentation/wiki` (the default `pagesDir`):

```markdown
<!-- Space: MYSPACE -->
<!-- Title: My Page -->
<!-- Parent: My Parent Page -->

# My Page

Body text.
```

2. Configure the connection (`build.gradle`):

```groovy
confluence {
  baseUrl = 'https://<tenant>.atlassian.net/wiki'
  space = 'MYSPACE'
  username = 'me@example.com' // Cloud only, see Authentication
}
```

3. Preview first, then publish:

```
./gradlew confluencePublish --preview
./gradlew confluencePublish
```

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
  `api.atlassian.com` gateway this plugin does not use.
- Data Center / Server with a Personal Access Token: omit `username` and the
  plugin sends Bearer auth.

Safety rules, enforced by the plugin: the token is never logged, never
appears in exception messages, and is excluded from up-to-date fingerprinting
(`@Internal`). The trust-store password gets the same treatment. `preview`
needs the token too (it resolves against the live wiki); `compile` never does.

## Tasks

| Task | Purpose |
|---|---|
| `confluenceRenderDiagrams` | `.mmd` → images via a pinned mermaid-cli container. Skips when up-to-date (sources, config, and the `--only` selection are all tracked). `--only=<name>` (repeatable, `.mmd` suffix optional) renders selected diagrams. |
| `confluenceCompile` | Runs render first so images are fresh (a no-op unless conversion is enabled), then checks all pages offline to `build/confluence/storage`. No network, no token. Joins `check` only when `verifyOnCheck = true`. |
| `confluencePublish` | Runs render + compile, then syncs to Confluence. `--preview` resolves ancestry and prints storage without writing; `--changed` (or `onlyChanged = true` in config) publishes only pages whose digest differs from the state file; `--only=<file>` (repeatable, relative to `pagesDir`) wins over `--changed`. Never wired into `build`/`check`. |
| `confluenceResetState` | Deletes the change-tracking state (`.gradle/confluence-publish/state.properties`, which survives `clean`; a legacy `documentation/wiki/.published-hashes` is imported once). |

## Diagrams

`.mmd` sources live in `diagrams/` under `pagesDir`
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
```

Skip conversion for one build with `-x confluenceRenderDiagrams`. A missing
engine binary fails with install/skip instructions; a render failure reports
the diagram, the exit code, and the container output.

## Page headers

HTML comments at the top of each page (typos such as `Titel` fail the build):

```markdown
<!-- Space: MYSPACE -->   <!-- falls back to confluence.space -->
<!-- Title: My Page -->
<!-- Parent: My Parent Page -->
<!-- Label: demo -->
```

Also recognized: `Attachment`, `Content-Appearance`, `Emoji`, `Folder`,
`Image-Height`, `Image-Width`, `Include`, `Layout`, `Order`, `Property`,
`Sidebar`, `Synchronized`, `Type`. A YAML front-matter block is accepted as an
alternative to the HTML comments.

## Full DSL example

```groovy
confluence {
  baseUrl = 'https://<tenant>.atlassian.net/wiki' // required for publish, not compile
  // authToken: explicit value/Provider; else -Pconfluence.token; else $CONFLUENCE_TOKEN
  username = 'me@example.com' // optional; present => Basic auth, absent => Bearer
  space = 'MYSPACE'           // default when a page has no Space header
  // pagesDir defaults to documentation/wiki; pageIncludes defaults to ['**/*.md']
  imageHeight = '800'         // per-file header wins
  contentAppearance = 'full-width' // full-width | fixed | default
  // titleFromH1 / titleFromFilename (mutually exclusive), titleAppendHash,
  // dropH1, parents, parentsFromPath (+ Root), appendLabels, minorEdit,
  // versionMessage, changesOnly, onlyChanged (= --changed), attachReferenced,
  // continueOnError,
  // verifyOnCheck, trustStore (+ Password), insecureSkipTlsVerify (testing only)
  diagrams {
    convertToPng = true
  }
}
```

All properties are lazy; omit what you do not set (never assign `null`).

## Troubleshooting

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

## Project layout

- `core/` — plain Java library, no Gradle API (bundled into the plugin jar).
- `cli/` — thin `main()` over core (parity debugging).
- `gradle-plugin/` — the plugin: `confluence` extension + the four tasks.
- `reference/` — Python reference copies, shell transcripts, provenance.

Build with `./gradlew build` (JDK 21 toolchain, Gradle 8.5+). Tests: JUnit 5
(unit) + TestKit (functional); no other test frameworks. Dependencies stay
lean by convention (JDK first); `core` ships bundled so consumers resolve a
single artifact.
