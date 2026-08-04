![Insula logo](branding/insula-128.png)

# Insula

[![build](https://github.com/adriandeleon/insula/actions/workflows/build.yml/badge.svg)](https://github.com/adriandeleon/insula/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/github/license/adriandeleon/insula)](LICENSE)
![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white)
![JavaFX](https://img.shields.io/badge/JavaFX-26-1e90ff)
![Platforms](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey)
[![Stars](https://img.shields.io/github/stars/adriandeleon/insula?style=flat)](https://github.com/adriandeleon/insula/stargazers)

<!-- Uncomment after the first vX.Y.Z release tag:
[![Release](https://img.shields.io/github/v/release/adriandeleon/insula)](https://github.com/adriandeleon/insula/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/adriandeleon/insula/total)](https://github.com/adriandeleon/insula/releases)
[![Installers](https://github.com/adriandeleon/insula/actions/workflows/release.yml/badge.svg)](https://github.com/adriandeleon/insula/actions/workflows/release.yml)
-->

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
- **Cross-archive search**: one box spanning every archive on disk, so you can look something up
  without first deciding which book it lives in. Results are ranked, tolerate a typo, and are
  labelled with the archive they came from; opening one switches archives for you.
- **Reader mode** with a dark theme that actually wins against the archive's own stylesheet —
  including its tables and inline colours — plus a readable content column. Images are dimmed, not
  inverted, so photographs and maps still look right. `Ctrl+R` cycles it.
- Your **reading position is remembered per article**, so returning to a long page puts you back
  where you were.
- Back/forward history. Links to the live web open in your system browser rather than pretending
  to work offline.

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

## Installers

`./mvnw -Pdist package` builds a native installer for the machine you run it on — a `.deb` on
Linux, `.dmg` on macOS, `.msi` on Windows — into `target/dist/`. jpackage cannot cross-build (both
the installer format and the linked runtime are the host's), so CI builds one per OS on a tag.
Add `-Djpackage.type=app-image` for an unpackaged bundle you can run in place.

The bundled runtime is linked with jlink and carries only the modules Insula reaches, so no JDK is
needed to run it. **BitTorrent is not included in packaged builds**: its native library ships in a
separate jar whose name is not a legal Java module name, and it cannot be linked into the image.
It is opt-in and off by default, HTTP multi-source is the guaranteed path, and Settings reports it
as unavailable rather than pretending otherwise. Running from source still has it.

## Keyboard

| Shortcut | Action |
| --- | --- |
| `Ctrl+Shift+P` | Command palette |
| `Ctrl+L` | Focus search |
| `Ctrl+B` | Library and downloads |
| `Ctrl+1` / `Ctrl+2` | Library / Store |
| `Ctrl+R` | Cycle reader mode (original / comfortable / dark) |
| `Ctrl+Alt+R` | Reader View (extracted article, Firefox-style) |
| `Ctrl+T` / `Ctrl+W` | New tab / close tab |
| `Ctrl+Tab` / `Ctrl+Shift+Tab` | Next / previous tab |
| `Ctrl+D` | Bookmark this article |
| `Ctrl+Shift+B` / `Ctrl+Shift+H` | Bookmarks / History |

Videos in archives are stored as WebM, which JavaFX cannot decode. Install **ffmpeg** and Insula
converts them once and plays them inline; without it, videos open in your usual player.

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

**BitTorrent** is available as an option, never as the only route. It is used only for archives
above a size threshold, only when enabled in Settings, and only when its native library actually
loaded; HTTP remains the fallback in every other case, because BitTorrent is blocked on many
school, library and office networks. If a torrent makes no progress it gives up and the download
continues over HTTP rather than sitting at 0 B/s.

Insula also **merges the Metalink mirrors into the torrent session as web seeds** (BEP 19).
Kiwix's `.torrent` files advertise only a few, geographically clustered mirrors while the `.meta4`
for the same file lists more — measured on one real archive, the torrent had 4 web seeds and the
Metalink 9, so 5 were missing. Since many Kiwix swarms are thin (often zero seeders), those web
seeds are frequently the only thing moving data: a test download completed with **0 peers**
entirely from merged mirrors.

Seeding is off by default and opt-in. A large share of these users are on metered or expensive
connections, and silently uploading tens of gigabytes is not a defensible default.

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

See [CHANGELOG.md](CHANGELOG.md) for what has landed. Next up: full-text search, a richer library
screen with thumbnails and filters, tabs and bookmarks, reading themes, and native installers.

**Delta updates are deliberately not planned.** Fetching only what changed between monthly builds
sounds like the obvious win, but it was measured rather than assumed: across six real build pairs
the median saving was about 8%, and one Wikipedia pair saved *nothing at all*. ZIM compresses
whole clusters and cluster membership shifts between builds, so even unchanged articles compress
to different bytes. The details are in [CLAUDE.md](CLAUDE.md).

## License

MIT — see [LICENSE](LICENSE). Third-party components, and the provenance of the ZIM test fixtures
committed to this repository, are recorded in [NOTICE.md](NOTICE.md).

## Acknowledgements

The ZIM format and the archives themselves are the work of the [openZIM](https://openzim.org) and
[Kiwix](https://kiwix.org) projects, whose mirror infrastructure Insula downloads from. Insula is
an independent reader and is not affiliated with either project.

## When BitTorrent crashes the app

A native crash in libtorrent exits with code 139 and, normally, no crash report: libjlibtorrent
replaces the JVM's own SIGSEGV and SIGBUS handlers with its own, so HotSpot never gets to write
one. To capture the report, run:

```bash
./scripts/debug-run.sh
```

That preloads `libjsig`, the JVM's signal-chaining library, which puts HotSpot's handler back in
the chain. Reproduce the crash under it and `hs_err_pid*.log` appears in the project root with the
native frame that died. Nothing else gives that away — `mvn javafx:run` alone cannot.
