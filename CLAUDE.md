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
  302 so the browser's base URL stays canonical. `LanServer` is the LAN-facing sibling (bind-all,
  stable slugs, a phone-first index page, read-only by construction, **session-only by design**);
  the two are separate classes on purpose — different bind, lifetime, and URL contracts.
- **`catalog/`** — `ZimEntry` (the model), `OpdsCatalogParser` (Kiwix OPDS v2), `CatalogCache`
  (the full 3,611-entry feed cached on disk with ETag/304, parse-before-swap, never-emptier rule,
  7-day auto-refresh), `CatalogGroups` (one card per (name, language), variants smallest-first),
  `StoreFilter` (query × language × category facets, counts ignoring their own dimension),
  `UpdateCheck` (installed file ↔ catalog match by file-name base + `_YYYY-MM` build stamps;
  `supersedes` is deliberately conservative because its caller deletes files), and `StarterPicks`
  (first-run suggestions as catalog **names, never URLs** — `wikipedia_en_simple_all` vanishing
  from the live feed is why).
- **`download/`** — the acquisition pipeline. `DownloadTransport`/`DownloadHandle`/
  `ProgressSnapshot`/`ProgressListener` are the seam; `HttpMultiSourceTransport` and the optional
  `TorrentTransport` are the implementations; `Metalink`/`MetalinkParser`, `ChunkPlan`
  (piece-aligned chunking + resume bitmap), `WebSeeds` (the Metalink→torrent merge),
  `Sha256Verifier`, `Quarantine`, `TransportSelector`, `DownloadManager` (the transport → verify →
  library pipeline), `RecoverySidecar` (title + Metalink URL persisted beside every unfinished
  download so quit-during-verify resumes and Repair works after the catalog rotates), and
  `PieceRepair` (scan a quarantined file against the Metalink's SHA-1 piece hashes, re-fetch only
  the bad ranges — each piece hash-checked before it is written — with the whole-file SHA-256
  keeping the final say).
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
- **`reader/` (Reader View)** — the Firefox-style distilled page, distinct from `ReaderTheme`
  (which styles the archive's page in place). `ReaderView` is the pure core: prefs record with
  clamps, Firefox's palettes, and the injected scripts as pure functions. Extraction is Mozilla's
  vendored Readability 0.6.0 run inside the WebView; the parsed article never crosses the JS↔Java
  bridge (only a word count does — the enter script reads `window.__insulaArticle` in-page).
  `ReaderViewSession` is the state machine over an injected script runner, so it unit-tests with
  a fake and FX-tests against the real engine. Three traps, all hit: (1) the reader-theme user
  stylesheet's `!important` rules outrank ANY author style, so it is lifted on enter and restored
  on exit; (2) the old document's **inline `style` attribute on `<body>`** survives the swap and
  outranks the shell stylesheet — proven by a background that refused to change while `html`
  updated — so the enter script strips root-element attributes; (3) `locationProperty` fires
  before the DOM is parsed, so the readerable probe hangs off the loadWorker SUCCEEDED state,
  and an exit-by-reload fires no location event, so exit resets its own state.
- **`app/`** — the JavaFX shell: `Main`, `ReaderController` (toolbar, search sidebar, WebView,
  and the three-surface switch: Reader / Library `Ctrl+1` / Store `Ctrl+2`, Library being home at
  startup when nothing reopens), `CommandPalette`, `SettingsDialog`, `LibraryPane` (disk gauge +
  Arriving + Needs-attention + device rows + starter empty-state), `StorePane`, `DownloadRow`
  (built once per job, updated in place by the 4 Hz tick), `IconCache`, `QrImage`, and the pure
  `Formats`.

Config lives in `~/.insula/` (`INSULA_CONFIG_DIR` overrides): `settings.properties`,
`library.properties`, `archives/` (plus per-download `*.zim.insula` recovery sidecars and
`*.corrupt` quarantined files), and `catalog/` (cached feed + icons).

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

### WebP: modern ZIMs store images in a format JavaFX cannot read

Kiwix's mwoffliner recompresses every image to **WebP** and keeps the original file name, so the
archive holds `Walt_Disney_1946.JPG` whose bytes begin `RIFF....WEBP`, served under
`image/webp`. Measured on a 2021 Bitcoin archive: **502 WebP against 1 PNG**.

JavaFX's WebView **cannot decode WebP** (verified directly: `naturalWidth == 0` with
`complete == true`, against a control PNG at 10 px in the same document). The failure is silent
and easy to misdiagnose — the image reports success, paints as an empty box rather than a
broken-image icon, and logs nothing. Do not go looking in the ZIM lookup or the HTTP server: both
were correct the whole time. `libjfxwebkit.so` contains the string `image/webp` but no libwebp
decoder symbols, so that string is not evidence of support.

`server/WebpTranscoder` decodes with TwelveMonkeys' pure-Java reader and re-encodes: **alpha →
PNG** (a signature or logo must keep its transparency), **no alpha → JPEG q0.85**. That split is
measured, not taste — over six real photos, PNG was 8.0× the original WebP bytes and JPEG 1.8×.
Decode+encode runs 35–100 ms per image, so results are cached in a byte-bounded LRU. A failed
decode serves the original bytes: no worse than before, never an error.

Two things to keep in mind when touching this. The TwelveMonkeys jars are **automatic modules**
found through the ImageIO `ServiceLoader`, so `module-info` must `requires` one of them
explicitly — an automatic module nothing requires is never resolved, and the reader silently
disappears on the module path while tests on the classpath still pass. And ImageIO drags in
`java.desktop`, so `Main.main` sets `java.awt.headless=true` as its **first** statement (the
macOS AppKit/JavaFX contention).

`LanServer` deliberately does *not* transcode — its clients are real browsers, which have read
WebP for years and are better served the smaller original.

### Video: WebM, and the WASM fallback that cannot save it

Archives ship **WebM**; JavaFX's WebView plays only what JavaFX Media plays. Measured on TED's
archive: all nine videos are **VP9 + Vorbis**, and the engine answers
`canPlayType('video/webm')` with `""` — WebKit then paints its own dead end, "No compatible
source was found for this media".

Kiwix anticipates the problem and bundles the **ogv.js WASM decoders** (vp8/vp9/av1/theora/opus/
vorbis, ~2 MB, under `C/assets/ogvjs/`) as a video.js fallback tech. Do not spend time trying to
make that path work: JavaFX's WebKit reports **`typeof WebAssembly === "undefined"`** (also no
`MediaSource`, no `SharedArrayBuffer`), so those decoders can never run. There is no in-page way
to play these files, and transcoding VP9 in-process is not a serious option.

So `reader/MediaFallback` replaces unplayable `<video>`/`<audio>` with a poster + Play button
that hands the **loopback URL** to the desktop (`HostServices.showDocument`) — verified with
ffprobe that a real player streams that URL directly, so there is no temp copy. Three points that
matter: the **engine decides** what is unplayable (`canPlayType` per source, never a format list
of ours, or an archive shipping MP4 would lose its working player); sources resolve against
`document.baseURI` **inside a try/catch**, because an `about:blank` document has no base and
`new URL()` throws there (which is also why the FX test serves its pages over real HTTP rather
than using `loadContent`); and the script is **idempotent per document** via a window flag, since
the load-succeeded event can fire more than once.

**Tabs share one engine.** `reader/ReaderTabs` is pure state (open/close/cycle, plus which tab
shows next after a close) and the strip follows it; `reader/ArticleRef` + `ArticleStore` back both
bookmarks and history, which differ in policy, not shape. A WebView per tab would hold an engine,
scene graph and GPU textures per open article — the memory shape this project avoids everywhere
else — so a switch reloads and restores the tab's recorded scroll instead. Two consequences worth
knowing: in-page state (a playing video, an entered Reader View) belongs to the tab that is
showing, and `syncActiveTabToCurrentArticle` must run on every navigation or the strip keeps the
title the tab was opened with. `ArticleStore` keys its properties file by a **zero-padded** index
because `Properties` is unordered — plain keys would reload a twelve-entry history with `C/10`
before `C/2` — and every field is percent-encoded so a `|`, `=` or newline in a path or title
cannot corrupt the store.

**A fragment navigation is not a new article.** The engine fires its location listener for
`#anchor` exactly as for a real navigation (measured), and archive scripts append query strings of
their own — TED's produce `?lang=undefined`. Article identity therefore comes from
`reader/ArticleLocation`, which strips both; treating the raw location as the identity exited
Reader View on every table-of-contents click and split one article's reading position across each
anchor and query spelling. Note the FX test for this must wait for **loadWorker SUCCEEDED**, not
for the location: the location changes when navigation *starts*, and scripting a not-yet-parsed
document silently does nothing (which is exactly how the first version of that test failed).

**Video plays in a JavaFX overlay, not in the page — and that is forced.** Feeding the on-demand
HLS stream to an inline `<video>` inside a *real* archive page crashed the process in about half
of runs: SIGSEGV in `libjfxwebkit`, in the paint pulse (`WebPage.twkUpdateContent`), not in any
script. The same page with a plain MP4 survived 3/3 and a minimal page with HLS survived too, so
the fault is HLS compositing inside WebKit specifically. WebView is single-process, so it is fatal
to the app. `app/VideoPlayerPane` therefore uses `javafx.scene.media`, which plays the identical
stream with no WebKit involvement (3/3 clean). Do not "simplify" this back to an inline element.

**Instant start is a complete playlist over segments made on demand.** `media/HlsPlaylist` writes
the whole playlist up front from an ffprobe duration, ending in `EXT-X-ENDLIST` so the timeline is
seekable, and `media/HlsSession` encodes each 6-second segment when the player asks (~165 ms
anywhere in the file, because `-ss` **before** `-i` is a keyframe seek rather than a decode from
the start; `-output_ts_offset` stamps absolute timestamps so segments stitch). First frame ~0.6 s
after the click, versus 21 s for the whole-file encode this replaced.

**In-app playback is a transcode, and the streaming shapes were measured before choosing.** With
ffmpeg present (optional, self-gating like the torrent transport), `media/Transcoder` +
`TranscodeService` convert the video to H.264/AAC and the placeholder becomes a real inline
`<video>`. Three measurements decided the design, none of them guessable:

- VP9 → H.264 runs at **70× realtime** (a 25-minute talk in 21 s), and the bottleneck is VP9
  **decoding** — `ultrafast` measured 72× and doubled the output, so `veryfast` wins.
- **Live HLS does not work**: JavaFX's MediaPlayer reads the playlist **once, at load**. Pointed
  at a growing playlist it reported the duration of the segments that existed at that instant
  (14 s of a 25-minute talk), never re-read it, and clamped a later seek to that stale end. Do not
  re-attempt streaming without solving that.
- A finished MP4 played inline in WebView reaches `readyState 4` and seeks **exactly** (a seek to
  1200 s lands at 1200.0) — but only because the server does Range.

Two traps: ffmpeg infers its container from the output extension, so writing to a `.part` file
(which is what keeps a killed encode from being served as complete) fails with "Unable to choose
an output format" unless `-f mp4` is explicit. And the cache is LRU by **touching the modification
time on a hit**, not by reading atime — most mounts are `relatime`/`noatime`, and an atime-driven
eviction deleted the newest entry in a test with staggered timestamps.

**Seeking needs Range, and Range needs a ranged read.** Both servers honour a single `bytes=`
range (`server/ByteRanges`, pure); multi-range deliberately falls back to the full body, which the
spec allows and no player asks for. A seek past the end must answer **416**, never byte 0 — the
player would read the start as a successful seek to the wrong place. The slice comes from
`ZimArchive.contentRange`, not from slicing a materialized blob: `ClusterStore.blobRange` preads
the window for an uncompressed cluster and copies out of the cached decompressed one otherwise, so
a seek costs the window rather than the file (a TED video blob is 20.6 MB). Verified with ffmpeg:
seek to 20:00 of a 25-minute talk decodes in 159 ms.

The click comes back through `reader/MediaBridge`, the app's only JS→Java surface — one method
taking one string, with `opens com.insula.reader to javafx.web` because `JSObject.setMember`
dispatches reflectively, and `requires jdk.jsobject`. The binding belongs to the document, so it
is re-installed on every load.

## UI kit (v2.1)

The design kit is the source of truth for the interface. `app/insula.css` holds the Lagoon &
Shore tokens and maps them onto AtlantaFX's `-color-*` looked-up colors, so stock controls and
anything already reading those variables re-theme for free; **dark mode is the `insula-dark`
style class on the scene root**, not a second stylesheet. `app/Pills` owns the state vocabulary —
add a state there, not at a call site, or the Catalog and Library start describing the same
archive differently. Two rules from the kit that are easy to lose: verifying is never folded into
downloading, and a quarantined file advertises the cost of the **repair**, not the size of the
loss.

**Testing UI structure:** do not walk the scene graph from a pane's root. A `ScrollPane`'s content
is not reachable through `getChildrenUnmodifiable()` until it has been skinned, so an
absence-assertion ("no Downloading pill is shown") passes vacuously against an empty walk. Panes
expose the real nodes instead (`CatalogPane.cardNodesForTest()`), and a test that asserts an
absence should also assert the collection it walked was non-empty.

## Packaging

`-Pdist` runs moditect → `maven-dependency-plugin` → `scripts/package.sh` (jpackage). Things that
bit, in order:

- **jlink cannot link automatic modules**, and the TwelveMonkeys WebP stack is six of them;
  moditect generates descriptors into `target/modules`, which the staging step then prefers over
  the originals. The generated `imageio-webp` descriptor **does** carry
  `provides javax.imageio.spi.ImageReaderSpi` — worth re-checking after any bump, because ImageIO
  finds the reader by service loader and a dropped provider breaks WebP *only* in packaged builds.
- **jlibtorrent cannot be packaged at all**: its natives live in a separate jar whose derived name
  (`jlibtorrent.linux.x86.64`) is not a legal module name, and JPMS resource encapsulation would
  hide the `.so` from the API module anyway. It is `requires static`, and the call site catches
  `Throwable` because merely touching `TorrentTransport` throws `NoClassDefFoundError` when the
  classes are absent — before any guard inside it can run.
- The classifier-less `javafx-*.jar` artifacts are empty aggregators; the real modules are the
  `-linux`/`-mac`/`-win` classifier jars. Staging both puts two modules of the same name on the
  path.
- Installer-only flags (`--linux-shortcut`) make jpackage **fail** for `--type app-image`, so the
  script adds them only for real installer types. And jpackage rejects a version with a qualifier,
  hence stripping `-SNAPSHOT`.

## Roadmap

Full-text search (the Xapian index inside ZIMs needs native code — a Lucene re-index on first
open is the likelier path); tabs, bookmarks and history; split archives (`.zimaa`…); store polish (pagination past MAX_CARDS, locale-aware starter picks); LAN sharing
extras (mDNS discovery, choosing which archives to share); in-app video playback (see below). Done since the original roadmap:
BitTorrent transport, the card Store with facets, Library-as-home, update pills, starters,
piece-level Repair, LAN serving + QR.
