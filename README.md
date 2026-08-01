# Insula

A fast, keyboard-first desktop reader for [ZIM](https://wiki.openzim.org/wiki/ZIM_file_format)
offline content archives (Wikipedia, Wiktionary, Wikibooks, StackExchange dumps, …) — the same
archives Kiwix uses, with an explicit goal of a **much better reading UX**: modern, minimal
chrome, instant search, keyboard everywhere.

Built with **JavaFX 26** on **JDK 25**. The ZIM reader is **pure Java** (no libzim, no JNI
builds): XZ clusters via `org.tukaani:xz`, Zstandard clusters via `zstd-jni`.

## Status: proof of concept

Working today:

- Open any modern `.zim` (both the old `A/I/M` and the new `C/M/W/X` namespace schemes;
  uncompressed, XZ and Zstandard clusters).
- Home renders the archive's main page in a WebView — images and CSS intact (entries are served
  at their real archive paths by a loopback-only HTTP server, so relative links need no rewriting).
- Search-as-you-type over the title index (uses the `X/listing/titleOrdered/v1` front-article
  listing when the archive has one), Enter/click to open.
- Back/forward history, external links open in the system browser.
- **Command palette** — every action is a registered command, reachable by name with its
  keybinding shown; nothing is mouse-only.
- **Settings** with live apply (theme, content zoom, search result count, reopen-last-archive),
  persisted to `~/.insula/settings.properties`.
- **Library and downloads** — search the Kiwix OPDS catalog, download over HTTP from several
  mirrors at once with resume, per-chunk and whole-file verification, and open the result.

### Keyboard

| Shortcut | Action |
| --- | --- |
| `Ctrl+Shift+P` | Command palette |
| `Ctrl+L` | Focus search |
| `Ctrl+O` | Open archive |
| `Ctrl+B` | Library / downloads |
| `Ctrl+,` | Settings |
| `Alt+←` / `Alt+→` | Back / forward |
| `Ctrl+=` / `Ctrl+-` / `Ctrl+0` | Zoom in / out / reset |

From the search field, `↓` moves into the results and `Enter` opens the top hit.

## Configuration

Settings live in `~/.insula/settings.properties` (override the directory with
`INSULA_CONFIG_DIR`). Every preference is also reachable as a palette command, so the
Settings window is never the only way to change something.

## Run

```bash
mvn javafx:run
```

or with an archive:

```bash
mvn javafx:run -Djavafx.args=/path/to/some.zim
```

Get archives from the [Kiwix library](https://library.kiwix.org/). Good small English ones for
testing (`download.kiwix.org/zim/wikipedia/`):

| File | Size | Notes |
| --- | --- | --- |
| `wikipedia_en_100_mini_<date>.zim` | ~4.5 MB | top-100 articles, text only |
| `wikipedia_en_100_maxi_<date>.zim` | ~51 MB | top-100 **with images** — best manual test |
| `wikipedia_en_climate-change_mini_<date>.zim` | ~12 MB | a themed subset |

Every ZIM on the mirror has `.sha256`, `.meta4`, `.torrent` and `.magnet` sidecars beside it;
verify a download with `sha256sum -c` against the published `.sha256`.

## Test

```bash
mvn test
```

Format-core tests run against small fixtures from
[openzim/zim-testing-suite](https://github.com/openzim/zim-testing-suite) committed under
`src/test/resources/zim/` — between them they cover both namespace schemes and all three
cluster compressions. UI tests drive a real scene graph on JavaFX 26's built-in **Headless**
Glass platform, so the whole suite runs with no display.

## Architecture

```
com.insula.zim       pure ZIM format core (no JavaFX): header, dirents, pointer lists,
                     cluster decompression + LRU cache, ZimArchive facade
com.insula.server    ZimHttpServer: 127.0.0.1-only, /zim/<token>/<ns>/<path> → blob + MIME
com.insula.catalog   ZimEntry + OPDS v2 parser + CatalogClient
com.insula.download  transport seam, HTTP multi-source transport, Metalink parsing,
                     chunk plan + resume, SHA-256 verification, quarantine, manager
com.insula.library   local archives and their verification state
com.insula.command   CommandRegistry + Keybindings + pure PaletteFilter ranking
com.insula.config    Settings: properties file, atomic save, clamped/normalized on read
com.insula.app       JavaFX shell: toolbar, search sidebar, WebView pane, palette,
                     settings, library/downloads
```

### How downloads work

The catalog's acquisition link points at a `.meta4` Metalink, from which the `.zim`, `.sha256`,
`.torrent` and `.magnet` URLs all derive. The Metalink carries a mirror list **and 4 MiB
piece-level SHA-1 hashes**, so the HTTP transport fetches piece-aligned chunks from several
mirrors concurrently and verifies each as it lands — the per-chunk integrity usually attributed
to BitTorrent. A chunk that fails is retried against a different mirror; an interrupted download
resumes from a bitmap stored beside the partial file.

After the bytes land, the whole file is checked against the published SHA-256 (this catches
corruption introduced *after* the write). Only then does the archive enter the library as
verified. A file that fails is **moved aside, not deleted** — discarding tens of GB over one bad
piece is hostile on the connections this app is for.

**BitTorrent** is designed for but not yet implemented: `TransportSelector` will prefer a torrent
transport for large archives once one is registered, and always falls back to HTTP, which stays
the guaranteed path for networks where BitTorrent is blocked. Seeding will be opt-in and off by
default — silently uploading tens of GB on a metered connection is not an acceptable default.

Two things measured against the live service that are easy to get wrong:

- The OPDS `length` attribute is **rounded up to a whole KiB** (712704 published for a
  712215-byte archive), so the Metalink `<size>` is the authoritative byte count.
- A transport reporting "complete" means the bytes arrived, **not** that the archive is usable —
  the job stays non-terminal until verification passes.

**Adding a feature = adding a command.** Register it in `ReaderController.registerCommands()`
so it shows up in the palette automatically; add a chord in `bindKeys()` only if it needs one.
Toolbar buttons dispatch through the registry rather than calling logic directly.

## Roadmap

- **BitTorrent transport** behind the existing seam (jlibtorrent; note the Maven Central artifact
  is stuck at 1.2.0.18 from 2018 and ships no Apple Silicon native — the current 2.0.12.x
  releases are GitHub-only and must be vendored), plus seeding settings.
- Full-text search (the Xapian index inside ZIMs needs native code — likely a Lucene re-index
  on first open instead).
- Richer library screen: thumbnails from the catalog, filters by language and category, paging.
- Tabs, bookmarks, persistent history; reading themes (dark mode CSS injection, width-capped
  column, per-book zoom).
- Split archives (`.zimaa` …), service-worker-dependent archives.
- jpackage installers (DMG/MSI/DEB).
