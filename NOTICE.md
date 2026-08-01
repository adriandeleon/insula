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
| [JUnit 5](https://junit.org/junit5/) | tests only | EPL-2.0 |

Licenses were read from each artifact's published POM where it declares one (zstd-jni: BSD
2-Clause; xz: 0BSD) and otherwise from the project's own repository (AtlantaFX declares none in
its POM but is MIT upstream).

The OpenJFX Classpath Exception is what allows an application to link against JavaFX without
itself becoming GPL. It is worth being aware of if the licensing of Insula ever changes.

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
