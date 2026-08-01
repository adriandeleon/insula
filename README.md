# Insula

A fast, keyboard-first desktop reader for **ZIM** offline content archives — the same archives
[Kiwix](https://kiwix.org) uses: Wikipedia, Wiktionary, Wikibooks, StackExchange dumps and
hundreds more. Download them once, read them forever, with no network.

Insula exists because the reading and library experience around these archives deserves better:
minimal chrome, instant search, everything reachable from the keyboard, and a download that tells
you the truth about whether your file is intact.

Built with **JavaFX 26** on **JDK 25**. The ZIM reader is **pure Java** — no libzim, no JNI build
step, no platform-specific native toolchain to install.

## Features

**Reading**

- Opens any modern `.zim`: both the legacy `A/I/M` and current `C/M/W/X` namespace schemes, and
  uncompressed, XZ and Zstandard clusters.
- Articles render with their images and stylesheets intact — entries are served at their real
  archive paths, so nothing inside the archived HTML has to be rewritten.
- Search-as-you-type over the archive's title index, with back/forward history. Links to the live
  web open in your system browser rather than pretending to work offline.

**Library and downloads**

- Search the Kiwix catalog from inside the app and download without leaving it.
- Downloads run **from several mirrors at once** using HTTP range requests, and **resume** after
  an interruption instead of starting over.
- Every chunk is checked against the publisher's piece hashes as it arrives, and the completed
  file against its published SHA-256. An archive is only offered for reading once it verifies.
- A file that fails verification is **kept, not deleted**, so you can retry without re-downloading
  tens of gigabytes.

**Interface**

- A command palette (`Ctrl+Shift+P`) listing every action with its shortcut — nothing is
  mouse-only.
- Live-applying settings: theme, content zoom, result count, download preferences.

## Install and run

Requires **JDK 25** or newer. No separate Maven install — the repository ships a wrapper.

```bash
git clone <repository-url> insula
cd insula
./mvnw javafx:run
```

Open an archive directly:

```bash
./mvnw javafx:run -Djavafx.args=/path/to/archive.zim
```

Native installers (DMG/MSI/DEB) are on the roadmap; for now the wrapper is the supported way to
run it.

## Getting archives

Use the built-in library (`Ctrl+B`), or download by hand from
[the Kiwix library](https://library.kiwix.org/). Small English archives that make good first
tests, from `download.kiwix.org/zim/wikipedia/`:

| Archive | Size | Notes |
| --- | --- | --- |
| `wikipedia_en_ray-charles_mini_<date>.zim` | ~0.7 MB | tiny, good for a smoke test |
| `wikipedia_en_100_mini_<date>.zim` | ~4.5 MB | top-100 articles, text only |
| `wikipedia_en_100_maxi_<date>.zim` | ~51 MB | top-100 with images |

Every archive on the mirrors has `.sha256`, `.meta4`, `.torrent` and `.magnet` sidecars beside it.
Insula uses the first two automatically; if you download by hand you can check the file yourself
with `sha256sum -c` against the published `.sha256`.

## Keyboard

| Shortcut | Action |
| --- | --- |
| `Ctrl+Shift+P` | Command palette |
| `Ctrl+L` | Focus search |
| `Ctrl+B` | Library and downloads |
| `Ctrl+O` | Open an archive from disk |
| `Ctrl+,` | Settings |
| `Alt+←` / `Alt+→` | Back / forward |
| `Ctrl+=` / `Ctrl+-` / `Ctrl+0` | Zoom in / out / reset |

From the search field, `↓` moves into the results and `Enter` opens the top hit.

## Configuration

Settings, the library index and downloaded archives live in `~/.insula/`. Set
`INSULA_CONFIG_DIR` to put them elsewhere. Every preference is also a palette command, so the
Settings window is never the only way to change something.

## How it works

```
com.insula.zim       pure ZIM format core (no JavaFX): header, dirents, pointer lists,
                     cluster decompression + LRU cache, ZimArchive facade
com.insula.server    ZimHttpServer: loopback-only, /zim/<token>/<ns>/<path> → blob + MIME
com.insula.catalog   ZimEntry + OPDS v2 parser + CatalogClient
com.insula.download  transport seam, HTTP multi-source transport, Metalink parsing,
                     chunk plan + resume, SHA-256 verification, quarantine, manager
com.insula.library   local archives and their verification state
com.insula.command   CommandRegistry + Keybindings + pure PaletteFilter ranking
com.insula.config    Settings: properties file, atomic save, clamped/normalized on read
com.insula.app       JavaFX shell: toolbar, search sidebar, WebView pane, palette,
                     settings, library/downloads
```

**Rendering.** Articles are served to an embedded WebView by a loopback-only HTTP server at their
real archive paths, so relative links, images and CSS resolve untouched.

**Downloading.** The catalog's acquisition link points at a `.meta4` Metalink, which carries the
mirror list *and* 4 MiB piece-level SHA-1 hashes. Insula fetches piece-aligned chunks from several
mirrors concurrently, verifies each as it lands, retries a bad chunk elsewhere, and resumes from a
bitmap stored beside the partial file. The whole-file SHA-256 runs afterwards regardless — it
catches corruption introduced after the write.

**BitTorrent** is designed for but not yet implemented. `TransportSelector` will prefer a torrent
transport for large archives once one is registered and always falls back to HTTP, which stays the
guaranteed path for networks where BitTorrent is blocked. Seeding will be opt-in and off by
default.

## Development

```bash
./mvnw test              # unit + headless-FX tests, no display required
./mvnw verify            # tests plus the formatting gate
./mvnw spotless:apply    # format before committing
```

Tests run against ZIM fixtures from
[openzim/zim-testing-suite](https://github.com/openzim/zim-testing-suite) and against loopback
HTTP servers that can deliberately misbehave — serving corrupt bytes, ignoring range requests or
failing outright — so the failure modes that corrupt a file are covered, not just the happy path.
UI tests drive a real scene graph on JavaFX 26's built-in headless platform.

See [CLAUDE.md](CLAUDE.md) for architecture conventions and a list of non-obvious facts about the
ZIM format and the Kiwix mirror infrastructure that are easy to get wrong.

## Roadmap

See [CHANGELOG.md](CHANGELOG.md) for what has landed. Next up: a BitTorrent transport behind the
existing seam, full-text search, a richer library screen with thumbnails and filters, tabs and
bookmarks, reading themes, and native installers.

## License

MIT — see [LICENSE](LICENSE). Third-party components, and the provenance of the ZIM test fixtures
committed to this repository, are recorded in [NOTICE.md](NOTICE.md).

## Acknowledgements

The ZIM format and the archives themselves are the work of the [openZIM](https://openzim.org) and
[Kiwix](https://kiwix.org) projects, whose mirror infrastructure Insula downloads from. Insula is
an independent reader and is not affiliated with either project.
