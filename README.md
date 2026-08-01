# Offline Wiki

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
  persisted to `~/.offline-wiki/settings.properties`.

### Keyboard

| Shortcut | Action |
| --- | --- |
| `Ctrl+Shift+P` | Command palette |
| `Ctrl+L` | Focus search |
| `Ctrl+O` | Open archive |
| `Ctrl+,` | Settings |
| `Alt+←` / `Alt+→` | Back / forward |
| `Ctrl+=` / `Ctrl+-` / `Ctrl+0` | Zoom in / out / reset |

From the search field, `↓` moves into the results and `Enter` opens the top hit.

## Configuration

Settings live in `~/.offline-wiki/settings.properties` (override the directory with
`OFFLINE_WIKI_CONFIG_DIR`). Every preference is also reachable as a palette command, so the
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
com.offlinewiki.zim      pure ZIM format core (no JavaFX): header, dirents, pointer lists,
                         cluster decompression + LRU cache, ZimArchive facade
com.offlinewiki.server   ZimHttpServer: 127.0.0.1-only, /zim/<token>/<ns>/<path> → blob + MIME
com.offlinewiki.command  CommandRegistry + Keybindings + pure PaletteFilter ranking
com.offlinewiki.config   Settings: properties file, atomic save, clamped/normalized on read
com.offlinewiki.app      JavaFX shell: toolbar, search sidebar, WebView pane, palette, settings
```

**Adding a feature = adding a command.** Register it in `ReaderController.registerCommands()`
so it shows up in the palette automatically; add a chord in `bindKeys()` only if it needs one.
Toolbar buttons dispatch through the registry rather than calling logic directly.

## Roadmap (not in the POC)

- Library screen: book cards (title, description, icon, size, article count), not a tree.
- Full-text search (the Xapian index inside ZIMs needs native code — likely a Lucene re-index
  on first open instead).
- Tabs, bookmarks, persistent history; reading themes (dark mode CSS injection, width-capped
  column, per-book zoom).
- Catalog browsing + downloads from library.kiwix.org.
- Split archives (`.zimaa` …), service-worker-dependent archives.
- jpackage installers (DMG/MSI/DEB).
