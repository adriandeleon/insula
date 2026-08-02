# Changelog

All notable changes to Insula are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Everything below is in `main` and unreleased; there has been no tagged version yet.

### Added

**Design system — "Lagoon & Shore"**

- The UI now runs on the design kit's tokens: one Lagoon teal accent (`#0E7C6B` light, `#3FC9AC`
  dark), warm-grey Shore grounds, and three semantic colors with fixed meanings — amber is
  "being checked", coral is "needs repair", green is "verified". They are mapped onto AtlantaFX's
  looked-up colors, so stock controls and existing components re-theme without being touched, and
  dark mode is one style class on the root.
- **Inter is bundled** (OFL-1.1) so the interface looks the same on every OS instead of falling
  back to whatever the platform calls its default.
- **Theme can follow the system.** Alongside Light and Dark there is now System, which tracks the
  OS colour scheme live — an OS-scheduled sunset switch reaches Insula without a restart.
- A shared state vocabulary (`Pills`) replaces per-screen styling, so a Catalog card, a Library
  row and the Home strip can no longer tell different stories about the same archive. Two rules
  it enforces: verifying is never folded into downloading, and a quarantined file advertises the
  cost of the *repair* ("Repair · 12 MB"), not the size of the loss.

**ZIM reading**

- Pure-Java ZIM reader supporting both the legacy `A/I/M` and current `C/M/W/X` namespace schemes,
  and uncompressed, XZ/LZMA2 and Zstandard clusters, with a bounded LRU cache of decompressed
  clusters.
- Article rendering in an embedded WebView, fed by a loopback-only HTTP server that serves entries
  at their real archive paths so relative links, images and stylesheets resolve without rewriting.
  ZIM redirects answer HTTP 302 to keep the browser's base URL canonical.
- Title-prefix search over the archive index, preferring the `X/listing/titleOrdered/v1`
  front-article listing when present.
- **Cross-archive fuzzy search**: one query spans every verified archive in the library, so you
  need not know which book an article lives in. Ranked by match quality (exact > prefix >
  word-start > substring > subsequence > single typo), tolerant of insertions, deletions,
  substitutions and transpositions, and each result labelled with its source archive; opening a
  result switches archives automatically. Titles are indexed in memory lazily and off-thread on
  first search — measured at ~13 ms per query across 197k entries in four archives.
- Back/forward history; links to the live web open in the system browser.
- **Reader mode** that restyles an article over whatever CSS the archive shipped: a real dark
  theme (including tables, striped rows and inline colours, with images dimmed rather than
  inverted), a readable content column and looser line height. Cycle with `Ctrl+R`.
- **Reading position remembered per article**, stored as a fraction of document height so it
  survives a resize or font change, and restored when you return.
- Rendering sits behind an `ArticleRenderer` interface (load, history, zoom, CSS injection, scroll
  position), so the engine is a replaceable part rather than woven through the reading layer.

**Library and downloads**

- Kiwix OPDS v2 catalog search from within the app.
- Multi-mirror HTTP downloads using range requests, with piece-aligned chunking, concurrent
  fetches, retry against a different mirror on failure, and resume from a bitmap stored beside the
  partial file.
- Per-chunk SHA-1 verification against the Metalink piece hashes as chunks arrive, plus a
  whole-file SHA-256 check against the publisher's digest before an archive is admitted to the
  library.
- Quarantine on verification failure: the file is moved aside rather than deleted, and its stale
  resume state is discarded so a retry starts clean.
- A persisted library of local archives that records which ones verified; only verified archives
  are opened automatically.
- Pluggable transport seam (`DownloadTransport`) and `TransportSelector`, so transports can be
  added without touching each other. HTTP is always the registered fallback.
- **BitTorrent transport** (optional), backed by a vendored jlibtorrent 2.0.12.9. Used only above
  a size threshold, only when enabled, and only when its native library loaded; it gives up and
  lets HTTP take over rather than stalling at 0 B/s. Seeding is off by default.
- **Metalink web-seed merge**: mirrors listed in the `.meta4` but absent from the `.torrent` are
  injected into the session as BEP-19 web seeds. Measured on a real archive, that is 5 mirrors the
  torrent omits out of 9; a test download completed with zero peers purely from them.

**Interface**

- Command registry that the palette, keybindings and toolbar all dispatch through, so every action
  is discoverable by name and nothing is mouse-only.
- In-scene command palette (`Ctrl+Shift+P`) with prefix-ranked filtering and per-command shortcut
  hints.
- Settings window with live apply — theme, content zoom, search result count, reopen-last-archive,
  and download preferences — persisted to `~/.insula/settings.properties` via an atomic write.
- Library and downloads view (`Ctrl+B`) showing catalog results as cards alongside local archives
  and in-flight downloads, with progress sampled at 4 Hz.

**Project**

- Maven wrapper, so no separate Maven installation is required (`./mvnw`).
- Project documentation: README, this changelog, and `CLAUDE.md` recording the architecture
  conventions plus the non-obvious ZIM-format and Kiwix-infrastructure facts that are easy to get
  wrong.
- MIT license, plus a NOTICE recording third-party licenses and the provenance of the committed
  ZIM test fixtures.
- GitHub Actions build on Linux, macOS and Windows.
- Spotless with Palantir Java Format, enforced at `verify`.
- Test suite of 118 tests: pure unit tests, loopback-server tests covering corrupt, unresponsive
  and range-ignoring mirrors, and headless-FX tests on JavaFX 26's built-in headless platform, so
  the whole suite runs without a display.

**Library & Store**

- The Library is the home surface: a disk gauge (space used by archives, free space written out),
  a pinned "Arriving" section of in-place-updated download rows while anything is in flight, and
  the on-device archive list. The Store is its own full surface with toolbar navtabs; `Ctrl+1`
  opens the Library, `Ctrl+2` the Store, and startup lands on the Library when nothing reopens.
- **Update detection**: installed archives are matched to the cached catalog by file-name base
  (name + flavour, build date stripped) and get an "Update to YYYY-MM" pill when the catalog has a
  strictly newer build; `library.checkUpdates` announces the count and `library.updateAll` queues
  everything. After a verified update lands, Insula offers — never silently performs — deletion of
  the superseded older build, and never touches the archive currently being read.
- **First-run starters**: an empty library suggests a few curated archives (a seconds-sized
  Wikipedia demo, Wikipedia's most-read articles, Wikivoyage, Vikidia), resolved against the
  cached catalog by name at display time — never hardcoded links — with the flavour sized to fit
  free disk.
- **Piece-level repair**: a quarantined file can be repaired instead of re-downloaded. The
  Metalink's 4 MiB SHA-1 piece hashes pinpoint the damaged ranges; only those travel again (each
  piece hash-checked before it is written), and the whole-file SHA-256 keeps the final say. A
  recovery sidecar written beside every download also lets a quit-during-verification resume at
  the next launch instead of stranding the file.

**Packaging**

- `./mvnw -Pdist package` now produces a **native installer** — `.deb`, `.dmg` or `.msi` for
  whichever machine builds it — with a jlink'd runtime carrying only the modules Insula reaches,
  so no JDK is needed to run it. Several dependencies are automatic modules that jlink refuses
  (the WebP stack), so moditect gives them real descriptors first; the WebP reader's
  `ImageReaderSpi` provider was checked to survive linking, since ImageIO finds it by service
  loader and a dropped provider would break images only in packaged builds. A tagged push builds
  all three on CI. Verified on Linux: the `.deb` installs to `/opt/insula` with a desktop entry,
  and the packaged binary runs.
- **BitTorrent is excluded from packaged builds.** Its native library ships in a separate jar whose
  name is not a legal module name, so it cannot be linked into the image. The transport is opt-in
  and off by default, HTTP multi-source is the guaranteed path, and Settings already reports it as
  unavailable; running from source is unaffected.

**Tabs, bookmarks and history**

- **Tabs.** Several articles open at once, with a tab strip over the reader: `Ctrl+T` opens the
  archive's front page in a new tab, `Ctrl+W` closes one, `Ctrl+Tab` cycles. Tabs share a single
  rendering engine rather than holding one per tab — a WebView each would keep an engine, a scene
  graph and GPU textures per open article, which is the memory shape this project avoids
  everywhere else; loads out of a local archive are sub-second, so switching reloads and restores
  the scroll that tab recorded. Closing the active tab moves right, then falls back left at the
  end of the strip, and closing the last one returns to the Library rather than leaving a blank
  screen.
- **Bookmarks.** `Ctrl+D` (or the toolbar star, which fills in when the article is saved) keeps an
  article for later. Bookmarks record the archive and its title alongside the article's, so the
  list is readable — and openable — without opening a 20 GB archive to find out what an entry was.
- **History.** Every article visited is recorded, most recent first, bounded and de-duplicated so
  revisiting a page moves it up rather than filling the list with copies. `history.clear` empties
  it.
- The sidebar now switches between **Search / Bookmarks / History** (`Ctrl+Shift+B`,
  `Ctrl+Shift+H`); both lists open on Enter or double-click, and Delete removes a bookmark.

**Reader View**

- A Firefox-style Reader View (`Ctrl+Alt+R`, or the toolbar Reader button): the article is
  extracted with Mozilla's Readability — the same engine behind Firefox's about:reader, vendored
  at 0.6.0 — and the page is rebuilt as a clean column with the title, source archive, and a
  reading-time estimate shown as a slow–fast range. An Aa panel controls serif/sans, text size,
  column width, line spacing, and Light / Sepia / Dark themes (Firefox's palettes); every knob
  applies live and persists. The toolbar button lights up only when Mozilla's readerable probe
  says the page is worth distilling, exiting is a plain reload, and following a link leaves
  Reader View exactly as Firefox does. This is distinct from the existing reader *themes*, which
  style the archive's own page rather than extracting from it.

**LAN sharing**

- `lan.share` serves the verified library to other devices on the local network, kiwix-serve
  style: a phone-friendly index page plus every archive at a stable URL, with a QR code window for
  pointing a phone camera at. Read-only by construction, session-only by design — sharing never
  survives a restart.

### Changed

- Renamed from "Offline Wiki" to **Insula**: package `com.offlinewiki.*` → `com.insula.*`, module
  and Maven coordinates to match, and the configuration directory from `~/.offline-wiki` to
  `~/.insula` (`OFFLINE_WIKI_CONFIG_DIR` → `INSULA_CONFIG_DIR`).

### Fixed

- **A table-of-contents click no longer exits Reader View or fights the anchor.** The engine
  reports a `#heading` navigation exactly like a real one, so every anchor was treated as a new
  article: Reader View closed, the reading position was stored per-anchor rather than per-article,
  and a position saved under the anchored spelling could be restored over the heading the reader
  had just clicked. Query strings had the same effect — TED's own script navigates to
  `?lang=undefined`, which scattered one article's saved position across several spellings and
  showed up in the status bar. Article identity is now the path with the fragment and query
  removed (`reader/ArticleLocation`).

- **Video starts almost immediately.** Instead of converting the whole file before anything plays
  (21 seconds for a 25-minute talk), Insula writes the HLS playlist up front from a duration probe
  and converts each 6-second segment only when the player asks for it — measured at ~165 ms per
  segment anywhere in the file. First frame now lands **~0.6 s after the click**, seeking to
  20:00 in a 25-minute talk is just as quick, and only what is watched is ever converted.
  Playback moved out of the article page into a player above it: an inline `<video>` fed the
  on-demand stream crashed WebKit's paint pulse in roughly half of runs (SIGSEGV in
  `libjfxwebkit`), and since WebView is single-process that takes the whole app down. JavaFX's own
  media stack plays the identical stream with no WebKit involvement and survived every run.
- **Videos can now play inside the app.** With ffmpeg installed, the placeholder offers
  **Play here**: the video is converted once to H.264/AAC — read straight off the loopback server,
  so the original is never copied to disk — and then plays inline in the article with working
  controls and seeking. Progress is shown while it converts, and the result is cached, so a
  rewatch is instant. Measured on a 25-minute TED talk: 21 seconds to convert (VP9 *decoding* is
  the bottleneck, not encoding), then playback with a seek to 20:00 landing exactly. ffmpeg is
  optional and self-gating, exactly like the BitTorrent transport — without it, nothing changes
  and videos still open externally. Settings → Reading view has the toggle and reports whether
  ffmpeg was actually found.
- **Seeking works: both servers honour HTTP Range.** An external player handed a video URL used
  to have to read from byte zero to reach a seek point, which makes a 25-minute talk unusable.
  Both the loopback reader server and the LAN server now advertise `Accept-Ranges`, answer a
  single range with `206`/`Content-Range`, and refuse an out-of-range seek with `416` rather than
  quietly serving the start. The slice is read straight out of the archive — `ZimArchive`
  gained `contentLength`/`contentRange`, so a seek costs the window asked for instead of
  materializing the whole blob (measured: 20.6 MB for one TED video). Measured end to end,
  ffmpeg now seeks to 20:00 in that talk and decodes in 159 ms.
- **Video in archives no longer dead-ends.** WebKit's "No compatible source was found for this
  media" is what an archive's WebM looks like in JavaFX: WebView plays only what JavaFX Media
  plays (in practice MP4/H.264), and every video in TED's archive is VP9 + Vorbis. Kiwix
  anticipates this and bundles the ogv.js WASM decoders as a fallback — but that escape hatch is
  closed too, because JavaFX's WebKit has no WebAssembly at all (`typeof WebAssembly` is
  `undefined`). Insula now replaces the dead player with the video's own poster frame, an
  explanation, and a Play button that hands the file to whatever the desktop uses for it —
  streaming straight from the loopback server, with no temp copy and no transcode. Media the
  engine *can* play is left completely alone; the engine itself decides, so an archive shipping
  MP4 keeps its real player.
- **Images in modern archives now display.** Every current Kiwix ZIM stores its images as WebP —
  mwoffliner recompresses them but keeps the original `.jpg`/`.png` file name, so an archive says
  `Walt_Disney_1946.JPG` while the bytes are WebP (measured on a 2021 Bitcoin archive: 502 WebP
  against 1 PNG). JavaFX's WebView cannot decode WebP and reports such an image as *loaded* with
  `naturalWidth == 0`, which paints as an empty box — no broken-image icon, nothing in any log.
  The reader's loopback server now transcodes WebP on the way out (pure-Java decode, no native
  library): an image with alpha becomes PNG so transparency survives, anything else becomes JPEG,
  which measured 1.8× the original bytes against PNG's 8.0×. Results are held in a byte-bounded
  cache, and an image that cannot be decoded is served unchanged rather than failing. LAN sharing
  deliberately keeps serving the original WebP, since real browsers prefer it.
- The Downloads settings page claimed "no torrent transport is installed yet, so downloads use
  HTTP either way" — stale since the BitTorrent transport landed. It now states what actually
  governs the choice (only archives above the 5 GB threshold, only when the native library
  loaded, HTTP always the fallback) and shows live whether BitTorrent is available on this
  machine, disabling the checkbox when it is not.
- Download rows now name the protocol moving the bytes ("via HTTP · 4 mirrors", "via BitTorrent ·
  12 peers"), and relabel themselves when a stalled torrent falls back to HTTP mid-download.

- A download no longer reports itself finished before verification runs. The transport's
  "complete" signal means the bytes landed, not that the archive is usable; treating it as
  terminal caused an unverified file to be presented as ready, and a shutdown at that moment
  cancelled verification entirely.
- Download progress now reaches 100%. The OPDS catalog's `length` attribute is rounded up to a
  whole KiB, so the Metalink's exact size (or the file on disk) is used as the denominator
  instead.
- The Metalink parser no longer mistakes the publisher URL — which sits outside the `<file>`
  element — for a download mirror, and ignores `ftp`/`rsync` mirrors that the HTTP client cannot
  fetch.
- Title search results no longer list a redirect and its target as two separate hits, and a
  lower-case query matches a capitalised title.
- Entry paths containing non-ASCII characters, spaces or quotes are percent-encoded correctly on
  the way to the WebView and decoded on the way back, so such articles and assets load.
