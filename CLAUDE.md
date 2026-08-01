# Insula

A keyboard-driven desktop reader for **ZIM** offline content archives (the format Kiwix uses) —
Wikipedia, Wiktionary, StackExchange dumps and similar. JDK 25 + JavaFX 26 + Maven, modular
(JPMS, module `com.insula`).

The project exists because Kiwix's desktop UX is poor. "Better than Kiwix to actually read and
manage archives with" is the product goal, and it should win arguments about scope.

## Commands

- Run the app: `./mvnw javafx:run` (add `-Djavafx.args=/path/to/some.zim` to open an archive)
- Run tests: `./mvnw test` — pure tests plus headless-FX tests, no display needed
- Full check (tests + formatting gate): `./mvnw verify`
- **Format before committing: `./mvnw spotless:apply`** — `spotless:check` is bound to `verify`,
  so unformatted code fails the build (the `javafx:run` / `test` dev loop is deliberately not gated)

## Architecture

Everything below `com.insula`. The dependency rule is one-directional: **the format core and the
download layer never import JavaFX**, and `app` is the only package that builds a scene graph.

- **`zim/`** — the pure ZIM format core. `ZimArchive` is the facade (path lookup by binary search,
  title-prefix search, redirect resolution, metadata, blob access); `ZimHeader`, `Dirent`,
  `MimeList`, `ClusterStore` (XZ/zstd/uncompressed + a bounded LRU of decompressed clusters),
  `LittleEndianFile` (thread-safe positional reads). No JavaFX, no JNI beyond zstd's bundled
  native.
- **`server/`** — `ZimHttpServer`: a **loopback-only** HTTP server that serves each entry at its
  real archive path (`/zim/<token>/<ns>/<path>`). Serving at real paths is what makes relative
  links, images and CSS inside archived HTML work with **zero rewriting**; ZIM redirects answer
  302 so the browser's base URL stays canonical.
- **`catalog/`** — `ZimEntry` (the model), `OpdsCatalogParser` (Kiwix OPDS v2), `CatalogClient`
  (queries off the FX thread, generation-guarded).
- **`download/`** — the acquisition pipeline. `DownloadTransport`/`DownloadHandle`/
  `ProgressSnapshot`/`ProgressListener` are the seam; `HttpMultiSourceTransport` and the optional
  `TorrentTransport` are the implementations; `Metalink`/`MetalinkParser`, `ChunkPlan`
  (piece-aligned chunking + resume bitmap), `WebSeeds` (the Metalink→torrent merge),
  `Sha256Verifier`, `Quarantine`, `TransportSelector`, `DownloadManager` (the transport → verify →
  library pipeline).
- **`library/`** — `Library`/`LibraryEntry`: local archives and, crucially, their **verified**
  flag. Only a verified archive may be opened automatically.
- **`search/`** — cross-archive search. `MatchScore` is the pure ranking function (exact > prefix >
  word-start > substring > subsequence > single Damerau–Levenshtein edit); `TitleIndex` holds one
  archive's titles in memory; `LibrarySearch` merges every archive's results. Fuzzy matching
  cannot use the on-disk title index — that is sorted for prefix lookup, and scanning it per
  keystroke would re-read every dirent (~350 ms on a 192k-entry archive). Titles are therefore
  walked **once, off-thread, lazily on first search** and kept pre-lower-cased, because
  lower-casing 192k strings per keystroke would cost more than the search. Measured: ~13 ms per
  query across 197k entries in four archives.
- **`command/`** — `CommandRegistry`, `Keybindings`, and the pure `PaletteFilter` ranking.
- **`config/`** — `Settings`: a properties file with atomic save, values clamped/normalized on
  read so a hand-edited or truncated file degrades to defaults per field.
- **`reader/`** — `ArticleRenderer` and its `WebViewRenderer` implementation (load, history, zoom,
  CSS injection, scroll position); `ReaderTheme`, the pure generator for the stylesheet layered
  over the archive's own; `ReadingPositions`, scroll positions per article stored as a fraction of
  document height so they survive a resize or font change. The reading layer talks to this, not to
  WebView.
- **`app/`** — the JavaFX shell: `Main`, `ReaderController` (toolbar, search sidebar, WebView),
  `CommandPalette`, `SettingsDialog`, `LibraryPane`, and the pure `Formats`.

Config lives in `~/.insula/` (`INSULA_CONFIG_DIR` overrides): `settings.properties`,
`library.properties`, and `archives/`.

## Conventions

- **Every user-facing action is a `Command`.** Register it in
  `ReaderController.registerCommands()` and it appears in the palette automatically; add a chord
  in `bindKeys()` only if it needs one. Toolbar buttons dispatch through the registry rather than
  calling logic directly — nothing should be reachable by mouse only. A setting must also have a
  palette command, not just a checkbox in the Settings window.
- **Never block or flood the FX thread.** Network, disk, hashing and parsing all run off-thread
  and marshal back. Progress is **sampled on a timer at ~4 Hz** (`LibraryPane.REFRESH_MILLIS`),
  never pushed one `Platform.runLater` per event — a transport emits progress far faster than the
  UI can paint, and per-event hops make the window stutter. A timer behind a hidden pane must be
  stopped.
- **Prefer a pure core with a thin impure shell.** Anything that can be a pure function of its
  inputs (`PaletteFilter`, `ChunkPlan`, `Formats`, the parsers) lives apart from the I/O and is
  unit-tested directly. This is why the test suite is fast and needs no display.
- **Remote input is hostile.** Both XML parsers (OPDS, Metalink) are XXE-hardened — DOCTYPE and
  external entities disallowed, errors thrown rather than printed. The HTTP server binds loopback
  only. A malformed catalog entry is skipped rather than failing the whole feed.
- **Integrity is not optional.** A download is not usable until its whole-file SHA-256 matches.
  A file that fails is **quarantined, never deleted** — discarding tens of GB over one bad piece
  is hostile on the connections this app targets.
- **Code style: Palantir Java Format via Spotless.** Import order: JDK → `javafx` → everything
  else → static last. Run `./mvnw spotless:apply` before committing.

## Testing

- `./mvnw test`. Three tiers: pure unit tests; **loopback-server** tests for anything networked
  (`FakeMirror` can serve corrupt bytes, ignore `Range`, or fail, so the failure modes that
  corrupt a file are covered rather than just the happy path); and **headless FX** tests.
- FX tests run on **JavaFX 26's built-in Headless Glass platform** (`-Dglass.platform=Headless`
  in the surefire config) — no Monocle jar, no display, no xvfb. `FxTestSupport` boots the
  toolkit once and marshals assertions onto the FX thread.
- Format fixtures come from [openzim/zim-testing-suite](https://github.com/openzim/zim-testing-suite)
  and are committed under `src/test/resources/zim/`; between them they cover both namespace
  schemes and all three cluster compressions. `src/test/resources/opds/` and `meta4/` hold real
  captured sidecars.
- Manual-test archives live in the gitignored `zims/`. Prefer a *live* end-to-end run against
  `download.kiwix.org` when touching the download layer — every bug listed below was found that
  way and none of them by the unit tests.
- Surefire runs classpath-mode (`useModulePath=false`) with `--enable-native-access=ALL-UNNAMED`
  for zstd's native library.

## Hard-won facts — don't re-derive these

**ZIM format**
- Header is 80 bytes little-endian, magic `0x44D495A`. `minorVersion >= 1` means the **new C/M/W/X
  namespace scheme**; older files use A/I/M/-. Both must keep working (2026 archives report
  minor=3).
- Cluster info byte: low nibble is compression (**0/1 = none, 4 = XZ/LZMA2, 5 = Zstandard**, the
  default since 2021), bit `0x10` means extended (u64 blob offsets instead of u32).
- There is **no maintained Java ZIM library** — `openzim/zimreader-java` is archived and the
  official bindings are C++/Node/Android. That is why the core is hand-written.

**Kiwix catalog and mirrors** (each of these cost real debugging)
- The OPDS acquisition link points at the **`.meta4`**, not the `.zim` (verified 200/200 across
  the live feed). Everything else derives by suffix from it.
- The `.meta4` carries **4 MiB piece-level SHA-1 hashes** as well as the mirror list, which is
  what gives HTTP per-chunk integrity — the property usually attributed to BitTorrent.
- **The OPDS `length` attribute is rounded UP to a whole KiB** (712704 published for a
  712215-byte archive). Use the Metalink `<size>` or the file on disk, or progress never reaches
  100%.
- The `.meta4` also contains `<publisher><url>https://kiwix.org</url>` **outside** `<file>`; a
  naive scan for `<url>` counts it as a mirror and hands the transport the project home page.
- MirrorBrain advertises `ftp://` and `rsync://` mirrors too, which `java.net.http` cannot fetch.
- The small Ray Charles test archive is `wikipedia_en_ray-charles` — **hyphen**, not underscore.

**Pipeline semantics**
- A transport reporting `COMPLETED` means *the bytes landed*, not that the archive is usable.
  If that leaks through as the job's state, a UI polling for a terminal state shows "Ready" on an
  unverified file and a shutdown at that moment cancels verification. `DownloadManager` maps it to
  `VERIFYING` and owns the real terminal state.
- A resume bitmap whose length disagrees with the current chunk plan describes a *different* file;
  it must be rejected outright, or never-downloaded ranges get marked present and the archive is
  silently corrupt.

**Rendering — WebView measured, and it is fine**

The "JavaFX WebView is ancient WebKit" reputation does not hold for 26.x. Measured against the
worst content in a real archive (OpenStreetMap Wiki), loading through the loopback server and
scrolling the whole document:

| article | load | scroll frames | heap |
| --- | --- | --- | --- |
| 3.4 MB, 924 rows | 770 ms | p50 16 ms, worst 25 ms, 0 janky of 231 | flat |
| 2.7 MB, 781 rows | 438 ms | p50 16 ms, worst 16 ms, 0 janky of 207 | flat |
| 2.5 MB, 3957 rows | 785 ms | p50 15 ms, worst 16 ms, 0 janky of 241 | flat |
| 1.1 MB, **13,347 rows** | 899 ms | p50 15 ms, worst 16 ms, 0 janky of 246 | flat |

A locked 60 fps with no frame over 32 ms, and heap stayed at ~90–96 MB throughout. **Stay on
WebView**; there is no performance case for JCEF. The reason `reader/ArticleRenderer` exists
anyway is that WebView is *single-process*, so a WebKit crash kills the whole JVM mid-download —
the interface makes swapping to an out-of-process engine a new implementation rather than a
rewrite. Untested: MathML (no local archive contains any).

*Measuring this is easy to get wrong.* The first run reported 4 ms loads and flat memory because
the harness had been given article **titles** where paths were required, so every case 404'd and
it was timing error pages. Assert the loaded DOM (`document.body.innerHTML.length`, `<tr>` count)
against what the source says, or the numbers are fiction. Frame pacing likewise needs a real
`Timeline` between scroll steps — chained `Platform.runLater` calls complete without any frames
elapsing and record nothing.

**Reader mode CSS — specificity is the whole problem**

Overriding an archive's own stylesheet is what the brief means by "Kiwix's dark mode fights
embedded stylesheets and loses". `!important` is mandatory here (we cannot edit the sheet we are
overriding), but the trap is *self*-inflicted specificity: a blanket
`*:not(img):not(video):not(canvas):not(svg):not(svg *)` scores **(0,0,5)** — higher than a bare
`th` — so it silently defeated the theme's own table-header shading and every table rendered flat.
Wrap blanket selectors in **`:where(...)`**, which contributes zero specificity, and put the whole
selector inside it (`:where(*:not(img)…)`, not `:where(*):not(img)…` — only what is *inside*
`:where` is zeroed). `html`/`body` must be excluded from the transparent-everything rule or the
page keeps no colour of its own. Images are **dimmed, never inverted**; an inverted photo or map
is the usual giveaway of a naive dark mode.

Verify by reading back `getComputedStyle` on a real article rather than by eye — both bugs above
looked plausible in a screenshot.

**Delta updates — measured, and the answer is no**

Re-downloading a whole archive for a monthly rebuild is the obvious thing to want to fix. It was
measured with the rsync experiment (an old build on disk, renamed to the new build's name, then
`rsync --stats --partial --inplace --no-whole-file` from a mirror that runs an rsync daemon —
`ftp.nluug.nl::kiwix` works; `mirror.download.kiwix.org` allows only one connection at a time).
Every result below produced a file whose SHA-256 matched the published digest, so the numbers are
real transfers, not aborted ones.

| archive | build pair | bytes saved |
| --- | --- | --- |
| `wikipedia_fr_chemistry_nopic` | 2026-04 → 2026-07 | **−0.0%** (10 KB *more* than a fresh download) |
| `bitcoin_en_all_nopic` | 2021-02 → 2021-03 | 0.9% |
| `alpinelinux_en_all_maxi` | 2026-04 → 2026-07 | 5.1% |
| `wikipedia_gan_all_maxi` | 2026-04 → 2026-07 | 11.3% |
| `openzim_en_all_maxi` | 2026-02 → 2026-05 | 11.9% |
| `termux_en_all_maxi` | 2022-09 → 2022-12 | 27.4% |

Median about 8%; only one of six clears 20% and none comes close to 50%. One real Wikipedia pair
found **essentially nothing in common** — the delta cost slightly more than downloading the file
outright. This is the recompression problem: ZIM compresses whole clusters, and between builds
cluster *membership* shifts, so even unchanged articles land in different clusters and compress to
different bytes. Rolling-hash tools handle shifted offsets fine; they cannot handle recompression.

**Verdict: do not build delta updates.** `zimdiff`/`zimpatch` are separately unusable (nobody
publishes diffs, and zimpatch has never produced byte-identical output — openzim/zim-tools#8). The
real fix is stable cluster assignment and rsyncable framing in the ZIM *writer*, which is an
openZIM conversation, not a client feature. The table above is a concrete thing to bring them.

**BitTorrent**
- jlibtorrent on Maven Central is **1.2.0.18 from 2018** with an x86_64-only macOS artifact, so
  2.0.12.9 is **vendored** in `m2-repo/` (~21 MB, checksums in `m2-repo/CHECKSUMS.txt`). A build
  pulls only its own platform's native via the `native-*` profiles.
- `TorrentStatus.totalDone()` counts **verified whole pieces**, so on a torrent with few large
  pieces it reads 0 for the entire transfer. Judge liveness on `totalPayloadDownload()` (bytes
  received) — watching `totalDone()` made a healthy single-piece download trip the stall timeout
  and report failure while data was arriving.
- The sidecar URLs are built by suffixing the base, so a blank entry still yields a non-blank
  `".torrent"`. Gate on `zimUrl()`, not on the derived URL.
- `SessionManager.find(TorrentInfo)` logs noisily about a missing v2 info-hash on Kiwix's v1
  torrents; it falls back correctly and the messages are harmless.
- Measured: Kiwix's `.torrent` carried 4 web seeds against the Metalink's 9, and a real download
  completed with **0 peers** entirely from merged web seeds. Swarms here are genuinely thin.

## Roadmap

BitTorrent transport behind the existing seam (plus seeding settings, opt-in and off by default);
full-text search (the Xapian index inside ZIMs needs native code — a Lucene re-index on first open
is the likelier path); richer library screen (thumbnails, language/category filters, paging); tabs,
bookmarks and history; reading themes; split archives (`.zimaa`…); jpackage installers.
