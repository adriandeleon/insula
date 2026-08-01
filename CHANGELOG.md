# Changelog

All notable changes to Insula are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Everything below is in `main` and unreleased; there has been no tagged version yet.

### Added

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
