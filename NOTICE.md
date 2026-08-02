# Third-party components

Insula itself is MIT-licensed (see [LICENSE](LICENSE)). This file records what else ships in the
repository or in a build, and under what terms.

## Runtime dependencies

| Component | Used for | License |
| --- | --- | --- |
| [OpenJFX](https://openjfx.io) (`javafx-controls`, `javafx-web`) | the UI toolkit and embedded WebView | GPL-2.0 **with Classpath Exception** |
| [AtlantaFX](https://github.com/mkpaz/atlantafx) (`atlantafx-base`) | theming | MIT |
| [XZ for Java](https://tukaani.org/xz/java.html) (`org.tukaani:xz`) | XZ/LZMA2 cluster decompression | 0BSD |
| [zstd-jni](https://github.com/luben/zstd-jni) | Zstandard cluster decompression | BSD-2-Clause |
| [jlibtorrent](https://github.com/frostwire/frostwire-jlibtorrent) (vendored, see below) | the optional BitTorrent transport | MIT (wrapping [libtorrent](https://libtorrent.org), BSD-3-Clause) |
| [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) (`imageio-webp`) | decoding the WebP images every modern ZIM stores, which JavaFX cannot read | BSD-3-Clause |
| [QR Code generator](https://github.com/nayuki/QR-Code-generator) (`io.nayuki:qrcodegen`) | the QR code shown when sharing the library on the local network | MIT |
| [JUnit 5](https://junit.org/junit5/) | tests only | EPL-2.0 |

Licenses were read from each artifact's published POM where it declares one (zstd-jni: BSD
2-Clause; xz: 0BSD) and otherwise from the project's own repository (AtlantaFX declares none in
its POM but is MIT upstream).

The OpenJFX Classpath Exception is what allows an application to link against JavaFX without
itself becoming GPL. It is worth being aware of if the licensing of Insula ever changes.

## Bundled fonts

| Component | Used for | License |
| --- | --- | --- |
| [Inter](https://github.com/rsms/inter) 4.1 (Regular, Medium, SemiBold, Bold, Italic) | the UI typeface, per the design kit | SIL Open Font License 1.1 |

## Vendored source

| Component | Where | License |
| --- | --- | --- |
| [Readability](https://github.com/mozilla/readability) 0.6.0 (`Readability.js`, `Readability-readerable.js`) — the article extractor behind Firefox's Reader View | `src/main/resources/com/insula/reader/` | Apache-2.0 (originally Arc90 Inc) |

## Vendored binaries

`m2-repo/` is an in-project Maven repository holding **jlibtorrent 2.0.12.9** — the API jar plus
the five desktop natives (linux x86_64/arm64, macOS x86_64/arm64, windows x86_64), about 21 MB in
total. They are vendored because jlibtorrent 2.0.12.x is published **only to GitHub Releases**;
Maven Central's newest is 1.2.0.18 from 2018, whose macOS artifact contains no Apple Silicon
native and so cannot run on a modern Mac.

The jars are byte-for-byte as downloaded from
[release/2.0.12.9](https://github.com/frostwire/frostwire-jlibtorrent/releases/tag/release%2F2.0.12.9);
their SHA-256 checksums are recorded in `m2-repo/CHECKSUMS.txt` so the provenance can be
re-verified. A build pulls in only the native matching its own platform, via the `native-*`
profiles in `pom.xml`.

## Test fixtures committed to this repository

`src/test/resources/zim/` contains four small ZIM archives taken from
[openzim/zim-testing-suite](https://github.com/openzim/zim-testing-suite):

| File here | Upstream path |
| --- | --- |
| `withns-small.zim` | `data/withns/small.zim` |
| `nons-small.zim` | `data/nons/small.zim` |
| `withns-wikibooks.zim` | `data/withns/wikibooks_be_all_nopic_2017-02.zim` |
| `nons-wikibooks.zim` | `data/nons/wikibooks_be_all_nopic_2017-02.zim` |

They are published by the openZIM project expressly so that libzim *and other implementations*
can test against real archives, which is exactly what they are used for here. Note, however, that
**the upstream repository states no license** — it carries no `LICENSE` file and declares none in
its metadata. They are included so the test suite runs offline and deterministically; if openZIM
would prefer they not be redistributed, they can be removed and fetched at test time instead.

The two `wikibooks` fixtures contain a snapshot of Belarusian Wikibooks content, which is
Wikimedia material under [CC BY-SA](https://creativecommons.org/licenses/by-sa/4.0/).

`src/test/resources/opds/entries-sample.xml` and `src/test/resources/meta4/*.meta4` are small
responses captured from `opds.library.kiwix.org` and `download.kiwix.org`, kept so the parsers are
tested against the real formats rather than hand-written approximations.

## Not affiliated

The ZIM format, the archives, and the mirror and catalog infrastructure Insula downloads from are
the work of the [openZIM](https://openzim.org) and [Kiwix](https://kiwix.org) projects. Insula is
an independent reader with no affiliation to or endorsement from either.
