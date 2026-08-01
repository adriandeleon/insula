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
  `ProgressSnapshot`/`ProgressListener` are the seam; `HttpMultiSourceTransport` is the
  implementation; `Metalink`/`MetalinkParser`, `ChunkPlan` (piece-aligned chunking + resume
  bitmap), `Sha256Verifier`, `Quarantine`, `TransportSelector`, `DownloadManager` (the
  transport → verify → library pipeline).
- **`library/`** — `Library`/`LibraryEntry`: local archives and, crucially, their **verified**
  flag. Only a verified archive may be opened automatically.
- **`command/`** — `CommandRegistry`, `Keybindings`, and the pure `PaletteFilter` ranking.
- **`config/`** — `Settings`: a properties file with atomic save, values clamped/normalized on
  read so a hand-edited or truncated file degrades to defaults per field.
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

**Tooling**
- jlibtorrent on Maven Central is **1.2.0.18 from 2018** and its macOS artifact is x86_64-only.
  The current 2.0.12.x releases (with Apple Silicon natives) are **GitHub-Releases-only** and would
  need vendoring into an in-project repo.

## Roadmap

BitTorrent transport behind the existing seam (plus seeding settings, opt-in and off by default);
full-text search (the Xapian index inside ZIMs needs native code — a Lucene re-index on first open
is the likelier path); richer library screen (thumbnails, language/category filters, paging); tabs,
bookmarks and history; reading themes; split archives (`.zimaa`…); jpackage installers.
