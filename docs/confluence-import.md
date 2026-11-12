# Confluence page import reference

`confluenceImport` creates an editable snapshot of one existing page beneath
the wiki directory: the `confluence.pagesDir` extension directory for Gradle
(default `documentation/wiki`), or `documents/wiki` for the CLI. It uses the
existing `confluence` connection, API token resolution, Basic/Bearer authentication,
and TLS settings. It does not require `confluence.space` and never creates or
updates a remote page, attachment, or label.

## Commands and output

```sh
./gradlew confluenceImport --page-id=123456 --preview
./gradlew confluenceImport --page-id=123456 --output=engineering/onboarding.md
./gradlew confluenceImport --page-id=123456 --output=engineering/onboarding.md --overwrite
./gradlew confluenceImport --page-url="https://example.atlassian.net/wiki/pages/viewpage.action?pageId=123456"
./gradlew confluenceImport --page-id=123456 --lossless --verify --output=releases/2026.md
./gradlew confluenceImport --page-id=123456 --lossless --space=NEW --parent="Release train" --title="Release copy"
```

On Windows, use `.\gradlew.bat`. Set task options immediately after the task name.

| Option / task property | Default | Meaning |
| --- | --- | --- |
| `--page-id` / `pageId` | Empty | Numeric page ID. |
| `--page-url` / `pageUrl` | Empty | Same-site `/pages/<id>/...` or `viewpage.action?pageId=<id>` URL. |
| `--output` / `output` | Empty | Relative `.md` path under the wiki directory; otherwise `<sanitized-title>-<page-id>.md`. |
| `--overwrite` / `overwrite` | `false` | Permit replacing files in the current output set. |
| `--preview` / `preview` | `false` | Fetch and convert, print Markdown and planned paths; no filesystem writes or asset-body downloads. |
| `--lossless` / `lossless` | `false` | Write raw storage markup between raw markers; no Markdown conversion. |
| `--verify` / `verify` | `false` | Requires `--lossless`; recompile before writing and fail on any difference. |
| `--space` / `space` | Empty | Retarget the import into another space; otherwise the source space. |
| `--parent` / `parents` | Empty | Retarget under another parent (repeatable, outermost first); otherwise the source ancestors. |
| `--title` / `title` | Empty | Retarget with another title; otherwise the source title. |

Supply exactly one selector. URLs must match the configured scheme, host, port,
and context path. Short links and title-only selectors are unsupported; use an ID.
Paths cannot be absolute, contain traversal or reserved Windows filenames, or
follow symlinks/junctions. Generated names are lowercase, sanitize punctuation,
and limit the title portion to 80 characters. The Markdown keeps the original title.

For `--output=engineering/onboarding.md`, the output set is:

```text
documents/wiki/engineering/
  onboarding.md
  onboarding.confluence.json
  onboarding.assets/                 # only if assets are referenced
    att987-sanitized-filename.png
  onboarding.confluence-storage.xml   # only if conversion warnings occur
```

The document uses UTF-8, LF endings, a trailing newline, and YAML front matter
compatible with this project's existing metadata parser. It preserves the page's
space, title, ordered ancestors, labels, and explicit non-image attachments.
It preserves authored headings and does not insert a duplicate title heading.
Metadata containing control characters or newlines, or any value that cannot
round-trip through the existing parser, causes the import to fail before writing.

The JSON manifest records the canonical source URL, page ID, source space, fetched version,
source storage hash, import mode,
asset identities and paths, structured warnings, and optional original-storage
filename. It is provenance, not a publishing binding or synchronization database.
The storage sidecar contains the original fragment, not necessarily standalone XML.

## Lossless import

`--lossless` skips Markdown conversion and writes the page body as raw storage
markup between `<!-- wiki-mark-raw-begin -->` and `<!-- wiki-mark-raw-end -->`
markers, after HTML comment headers (`Space`, repeated `Parent` outermost
first, `Title`, one `Label` per label, one `Attachment` per downloaded file).
The next `confluenceCompile` extracts the block verbatim, so its output is
byte-for-byte identical to the source page storage. Keep both markers on their
own lines; removing both makes the file compile as Markdown. The file uses UTF-8. Header and marker framing use LF, with exactly one LF after
the opening marker, one LF before the closing marker, and one final LF after it.
The payload between those framing separators is preserved as an original substring:
CRLF, lone CR, LF, mixed endings, source trailing newlines, entities, and CDATA
remain unchanged. Do not let an editor normalize the payload's line endings.
An opening marker without its matching closing marker fails compilation.

For `--output=releases/2026.md`, the output set is:

```text
<wiki-dir>/releases/
  2026.md
  2026.confluence.json
  2026.assets/                      # only if same-page attachments are referenced
    Release notes.png               # original filename, so references resolve
  2026.confluence-storage.xml       # only if inventory warnings occur
```

Only same-page attachments referenced in storage elements (including inside
macros) or download URLs are downloaded, keeping their original filenames so republished
`ri:filename` references resolve. Filenames that would need renaming to
round-trip (path separators, control characters, glob or Windows-hostile
characters, trailing dots or spaces) and case-only collisions fail explicitly.
Cross-page attachments and download URLs stay remote; the importer reports the
references without downloading them. URL query strings and fragments are excluded
from filename lookup. A download URL containing the source page ID retains that ID
after copying, even if the asset is downloaded and reuploaded; review the warning
before retiring the source page. Every macro name found, macros referencing other pages, spaces,
servers, or users, foreign space keys, and page/blog-post/user reference counts
are reported as warnings and recorded in the manifest.

`--space`, `--parent` (repeatable, outermost first), and `--title` retarget the
headers without touching the body, for copying a page into another space or
under a different parent. `--verify` recompiles the new file in memory before
writing anything and fails with the first differing offset plus context when
the output is not byte-identical. Diagnostic offsets are UTF-16 character offsets.
Verification and retarget options require `--lossless`; incompatible combinations
fail explicitly. Use `--parent=` to clear source ancestry and target a space root.
The Markdown import path is unchanged unless
`--lossless` is chosen.

A successful non-preview lossless import that targets the source page also
records a publish baseline (remote version plus storage hash) under
`.gradle/confluence-publish/baselines`, using the same key and hash publish
uses — the next publish sees no drift. No baseline is written for previews,
retargeted imports (different space, title, or parents), lossy Markdown
imports, or with `--no-baseline`; import always prints which case applied, for
example `Publish baseline recorded: version 3 (hash 41b42668...)`. With
`--overwrite`, an older baseline for the same page is replaced. The
`.confluence.json` manifest keeps page ID, version, storage hash, and mode as a
human-readable source record and is never read as a baseline.
`confluenceResetState` deletes only the change-tracking state file and keeps
baselines.

## Conversion and limitations

| Confluence content | Imported representation |
| --- | --- |
| Text, headings, emphasis, strikethrough, inline code, horizontal rules | Markdown with literal syntax escaped. |
| Lists, nested lists, ordered-list starts, blockquotes | Markdown preserving order and nesting. |
| Preformatted blocks, code/noformat macros | Fenced blocks preserving whitespace; embedded fences are handled. |
| Simple tables | Pipe tables; literal pipes use numeric entities. A non-header first row becomes a header with a warning. |
| Complex or merged-cell tables | Sanitized HTML with a warning; unsupported nested content is retained as readable fallback text. |
| Ordinary links | Markdown links resolved against the site URL. Unsafe schemes are omitted with warnings. |
| Confluence page links and anchors | Absolute Confluence links, retaining labels and available target information. |
| Selected-page attachment links and images | Local relative links to downloaded assets, deduplicated by attachment ID. |
| Externally hosted images | Original remote URL; no download. |
| Info/note/tip/warning/panel/expand macros | Labeled blockquotes with warnings for presentation changes. |
| Columns and layouts | Flattened in document order, with warnings. |
| Image captions | Caption text retained alongside the image, with a warning. |
| Unsupported macros/elements, mentions, embeds, cross-page attachment references | Visible fallback markers and available readable text, with warnings and the original storage sidecar. Ordinary cross-page download URLs remain remote. |

The converter parses the storage body with the JDK XML parser, uses a local JDK
HTML entity catalogue, and disables external entities, DTDs, and network resolution.
No production dependency is added. The runtime needs the standard `java.xml` and
`java.desktop` modules supplied by a normal JDK. Malformed storage, unknown named
entities, missing storage, or storage over five million characters fails clearly.
An explicitly empty storage body imports successfully. XML nesting is limited to
256 elements.

Markdown mode is not a lossless round trip. Editor presentation, macro execution, generated
diagrams' original Mermaid sources, page history, comments, restrictions, and
Confluence editor metadata are not reconstructed. Unsupported content is retained
for review rather than silently removed. The current Markdown publisher also has
a deliberately limited Markdown dialect; inspect compile previews before publishing
documents containing unusual inline code or complex HTML fallbacks.

## Downloads, collisions, and recovery

Only referenced assets from the selected page are downloaded. An unavailable or
missing required attachment fails the import. Each asset is limited to 50 MiB,
and combined assets to 200 MiB per import. Downloads use a 30-second deadline per
request, a ten-second connection timeout, and at most five redirects. Retryable
429/503 responses and transport errors follow the client's retry policy; download
retry delays are capped at 30 seconds. The timeout applies to each attempt, not
the total import duration.

Initial attachment URLs must be same-origin. Same-origin redirects can receive
authentication; redirects to another origin require HTTPS and receive no credentials,
including subsequent redirects back to the original site. HTTPS-to-HTTP redirects
are rejected. REST metadata redirects are refused; configure the final site URL.
Error reports omit remote response bodies and credential-bearing request details.

All output collisions are checked before downloading asset bodies. Preview also
checks collisions, so use `--overwrite --preview` to inspect a replacement.
Required downloads stream into temporary staging files before output installation;
the complete attachment set is not retained in memory. Files are staged on the
output filesystem, existing files are backed up, and the Markdown entry point is
installed last. A commit failure triggers rollback. If rollback fails, the error
identifies the staging directory holding recovery files; retain and inspect it
before retrying. These moves are not a crash-atomic multi-file transaction.
An unsuccessful operation can leave empty directories; it does not deliberately
install a partial Markdown document.

`--overwrite` only replaces files generated by the current import. It never deletes
unrelated or stale assets/sidecars. An old storage sidecar may remain after a later
lossless import; the current manifest identifies whether a sidecar belongs to that
snapshot. Change-tracking state under `.gradle/confluence-publish` is untouched.

## Compile and publish an imported document

Gradle import and publishing share `confluence.pagesDir` (default
`documentation/wiki`), so an imported file compiles without extra
configuration. Override it when the wiki lives elsewhere:

```groovy
confluence {
  pagesDir = layout.projectDirectory.dir('documents/wiki')
}
```

The CLI resolves `documents/wiki` relative to the working directory unless
`--wiki-dir` points it at another root.

```sh
./gradlew confluenceCompile
./gradlew confluencePublish --preview --only=engineering/onboarding.md
```

Review the Markdown, manifest, warnings, and storage sidecar before explicitly
publishing. Non-image assets have explicit `attachments` front matter, so their
local links are recognized without `attachReferenced = true`. Percent-encoded
local paths are decoded for attachment lookup. Local images are discovered by the
publisher as usual. The publisher uses space/title/ancestor metadata to identify
pages; the import manifest does not force an update to its recorded ID and is not
a last-published baseline. Publish drift protection independently compares local
and remote storage using version tags and successful-publish state. Review existing publishing settings such as configured
parents, title hashing, and layout before reusing them for imported pages.
A newly imported file publishes on the next run even when nothing changed
locally, so `confluenceResetState` is not needed for a brand new page.

## Publish drift protection

Every successful page create or update includes a `[v<sha1>]` version-message tag.
After the complete page operation succeeds, the publisher also writes a baseline
containing `storageHash` and `versionNumber` under
`.gradle/confluence-publish/baselines/`, keyed by site and page ID. The import
manifest is provenance, not this baseline. A tag supports fresh clones; local
state supports wiki edits that remove the tag. Missing or malformed state is
unknown. State newer than the remote version, or contradictory evidence at the
same version, is not trusted. A newer remote version tag supersedes older state.

| Local / remote state | Publish behavior |
| --- | --- |
| Identical raw storage | No prompt or remote writes, including attachments and labels. |
| Remote matches baseline; local differs | Publish without prompting. |
| Local matches baseline; remote differs | Show remote-only drift; default to keeping remote. |
| Both differ from baseline | Show conflict; default to keeping remote. |
| No trusted baseline | Show unknown-baseline drift; require an explicit decision. |
| No remote page | Create without prompting. |

An interactive console asks for `yes` to overwrite. Without a console (including
most Gradle daemon and CI runs), drift fails with a unified storage diff and
requires `--force`. Preview reports drift but changes neither remote content nor
publish state. The diff compares remote storage to the proposed local storage,
uses three context lines and roughly 200 body lines maximum, and has no ANSI
color. Volatile IDs are removed only from actual tag attributes; CDATA and blank
lines remain visible. If display normalization hides a raw difference, the report
says so explicitly. Credentials configured for the run are redacted before diff
formatting; raw equality and published payloads remain untouched.

```sh
./gradlew confluencePublish --preview
./gradlew confluencePublish --force
mark sync --files 'documentation/wiki/*.md' --force
```

`--changed` / `onlyChanged` now check every selected page remotely so local file
hashes cannot hide wiki edits. `--only` still restricts the selection. `changesOnly`
is retained for compatibility; exact equality skips writes regardless of that flag.
Because equal storage skips the whole remote operation, an attachment-only or
label-only edit is not published by that run, even with `--force`. Include a body
change when intentionally updating those assets or metadata. This is an explicit
behavior change from earlier versions.

Drift decisions happen before ancestor creation or uploads. Updates use the exact
version that was checked. HTTP 409 fails rather than retrying against a newer
version; even `--force` does not bypass concurrent-edit protection. Remote writes
are not a multi-operation transaction: a failed attachment or label operation can
follow a successful body update. Local baseline state advances only after the
complete page operation succeeds. Resetting legacy page/diagram hashes preserves
publish baselines so a routine reset does not erase drift evidence.

The lossless guarantee is **fetched storage to compiled storage**. Confluence may
normalize attributes, macro IDs, or tag serialization on save. A live
publish/refetch identity measurement has not been performed; a display-normalized
diff is diagnostic and never substitutes for raw equality.

## CLI

The optional CLI exposes the same core workflow:

```sh
mark import --page-id 123456 --base-url https://example.atlassian.net/wiki --username me@example.com --preview
mark import --page-url "https://example.atlassian.net/wiki/spaces/ENG/pages/123456/Onboarding" --base-url https://example.atlassian.net/wiki --output engineering/onboarding.md
mark import --help
```

`mark` here denotes invoking `dev.vspex.wiki.mark.cli.Main` with both the CLI and
core jars on the Java classpath; the repository does not install a `mark` launcher.
For example, after building, PowerShell can invoke:

```powershell
java -cp "cli/build/libs/cli-0.5.0.jar;core/build/libs/core-0.5.0.jar" dev.vspex.wiki.mark.cli.Main import --help
```

Use `:` as the classpath separator on Unix. The CLI resolves `documents/wiki`
relative to the working directory. Connection flags are the existing `--base-url`,
`--username`, `--password`, `--trust-store`, `--trust-store-password`, and
`--insecure-skip-tls-verify`; prefer `CONFLUENCE_TOKEN` over a command-line token.
CLI token precedence remains explicit `--password` then `CONFLUENCE_TOKEN`.
Gradle's `confluence.token` property is not read by the CLI. Import failures return
a nonzero exit code. Existing `mark [sync]` commands retain their behavior.

## Development and API references

Core owns snapshots, conversion, import orchestration, safe paths, and transaction
handling. Gradle and CLI code only adapt configuration and report results. The
Gradle task is untracked, always reads live remote state when explicitly invoked,
supports the configuration cache, and has no lifecycle/render/compile dependencies.

Run `.\gradlew.bat build` on Windows or `./gradlew build` on Unix. Tests exercise
conversion, metadata, pagination, authentication, read-only requests, download
limits and redirects, output safety, rollback, CLI behavior, and the packaged plugin
with configuration-cache reuse. Test servers are local; no production wiki or
credentials are required. Builds also apply the repository's Java formatting.

The adapter uses the existing v1 site API and explicit expansions for
`body.storage,space,ancestors,version`, plus paginated label and attachment reads.
See Atlassian's [Cloud v1 reference](https://developer.atlassian.com/cloud/confluence/rest/v1/),
[Cloud attachment API](https://developer.atlassian.com/cloud/confluence/rest/v1/api-group-content---attachments/),
and [Data Center REST reference](https://developer.atlassian.com/server/confluence/rest/latest/).
This feature does not migrate the client to v2 or support `api.atlassian.com`
gateway URLs. Deployment-specific availability and permissions still apply.

## Merge after importing

Publish supports opt-in `--merge` for independent body changes. Full local/remote snapshots
are required; import manifests and historical hash-only baselines alone are insufficient.
Establish tracking through exact local/remote equality or a successful deliberate publish.
See [publish merge](publish-merge.md) for preview commands, scope, and recovery.

## Baseline refresh and strict drift checks

A same-target, byte-exact lossless import accepts the fetched revision and invalidates older
merge snapshots for that page. This also handles version messages carrying a hash copied from
an older revision: an exact hash and version in local state take precedence over that tag.
`--verify` is optional for recording state because import always runs the exact compile check.
Skipped and failed imports do not refresh state. `--no-baseline` retains existing state.

Baselines are local to each checkout. Deleting `.gradle` removes local history; re-importing
in lossless mode can establish it again. A differing remote revision or exact storage hash
still requires consent when publishing local edits. Exact local/remote equality continues to
skip all remote writes, including with `--force`. HTTP 409 still fails without overwrite retries.

Volatile macro IDs are not normalized for drift decisions: their bytes affect storage hashes.
Only displayed diffs normalize them, with a warning if normalization hides a raw difference.
A live-server normalization measurement has not been performed; offline regression tests cover
that distinction. Import provenance manifests remain separate from publish state.
