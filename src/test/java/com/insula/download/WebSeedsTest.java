package com.insula.download;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSeedsTest {

    private static final String FILE = "wikipedia_en_ray-charles_mini_2026-05.zim";
    private static final String US_A = "https://ny.mirror.driftle.ss/kiwix/zim/wikipedia/" + FILE;
    private static final String US_B = "https://wi.mirror.driftle.ss/kiwix/zim/wikipedia/" + FILE;
    private static final String FR = "https://mirror.download.kiwix.org/zim/wikipedia/" + FILE;
    private static final String DK = "https://mirrors.dotsrc.org/kiwix/zim/wikipedia/" + FILE;

    @Test
    void findsMirrorsTheTorrentOmits() {
        List<String> missing = WebSeeds.missingFrom(List.of(US_A, US_B), List.of(US_A, US_B, FR, DK));
        assertEquals(List.of(FR, DK), missing);
    }

    @Test
    void addsNothingWhenTheTorrentAlreadyHasThemAll() {
        assertTrue(WebSeeds.missingFrom(List.of(US_A, FR), List.of(FR, US_A)).isEmpty());
    }

    @Test
    void treatsTrailingSlashAndHostCaseAsTheSameMirror() {
        // Reporting these as "added" would overstate what the merge contributed.
        assertTrue(WebSeeds.missingFrom(List.of(US_A), List.of(US_A + "/")).isEmpty());
        assertTrue(WebSeeds.missingFrom(List.of(US_A), List.of(US_A.replace("ny.mirror", "NY.MIRROR")))
                .isEmpty());
    }

    @Test
    void keepsPathCaseSignificant() {
        // Mirror paths are case-sensitive on the server, so these are genuinely different URLs.
        String other = US_A.replace("/wikipedia/", "/Wikipedia/");
        assertEquals(List.of(other), WebSeeds.missingFrom(List.of(US_A), List.of(other)));
    }

    @Test
    void deduplicatesWithinTheMetalinkItself() {
        assertEquals(List.of(FR), WebSeeds.missingFrom(List.of(), List.of(FR, FR, FR)));
    }

    @Test
    void toleratesNullAndBlankInput() {
        assertTrue(WebSeeds.missingFrom(null, null).isEmpty());
        assertTrue(WebSeeds.missingFrom(List.of(), List.of("", "   ")).isEmpty());
    }

    @Test
    void dropsMirrorsThatDoNotAddressTheTorrentsFile() {
        // A web seed pointing at a different file makes libtorrent fetch bytes that fail every
        // piece hash — worse than having no web seed at all.
        String wrongFile = "https://mirror.example/kiwix/zim/wikipedia/some_other_archive.zim";
        assertEquals(List.of(FR, DK), WebSeeds.matchingFile(List.of(FR, wrongFile, DK), FILE));
    }

    @Test
    void ignoresAQueryStringWhenMatchingTheFileName() {
        assertEquals(List.of(FR + "?mirror=1"), WebSeeds.matchingFile(List.of(FR + "?mirror=1"), FILE));
    }

    @Test
    void withoutAFileNameEverythingIsKept() {
        assertEquals(2, WebSeeds.matchingFile(List.of(FR, DK), "").size());
    }
}
