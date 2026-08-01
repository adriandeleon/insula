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

Keyboard: `Ctrl+L` search · `Ctrl+O` open archive · `Alt+←`/`Alt+→` back/forward ·
`↓` from the search field into results, `Enter` opens.

## Run

```bash
mvn javafx:run
```

or with an archive:

```bash
mvn javafx:run -Djavafx.args=/path/to/some.zim
```

Get archives from the [Kiwix library](https://library.kiwix.org/) — a small one to try:
`wikipedia_en_ray_charles` (a few MB).

## Test

```bash
mvn test
```

Format-core tests run against small fixtures from
[openzim/zim-testing-suite](https://github.com/openzim/zim-testing-suite) committed under
`src/test/resources/zim/` — between them they cover both namespace schemes and all three
cluster compressions.

## Architecture

```
com.offlinewiki.zim     pure ZIM format core (no JavaFX): header, dirents, pointer lists,
                        cluster decompression + LRU cache, ZimArchive facade
com.offlinewiki.server  ZimHttpServer: 127.0.0.1-only, /zim/<token>/<ns>/<path> → blob + MIME
com.offlinewiki.app     JavaFX shell: toolbar, search sidebar, WebView reading pane
```

## Roadmap (not in the POC)

- Library screen: book cards (title, description, icon, size, article count), not a tree.
- Full-text search (the Xapian index inside ZIMs needs native code — likely a Lucene re-index
  on first open instead).
- Tabs, bookmarks, persistent history; reading themes (dark mode CSS injection, width-capped
  column, per-book zoom).
- Catalog browsing + downloads from library.kiwix.org.
- Split archives (`.zimaa` …), service-worker-dependent archives.
- jpackage installers (DMG/MSI/DEB).
