package com.insula.reader;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure half of the unplayable-media fallback. */
class MediaFallbackTest {

    @Test
    void theEngineDecidesWhatIsPlayableNotAFormatListOfOurs() {
        String script = MediaFallback.installScript("label", "Play");
        assertTrue(script.contains("canPlayType"), "the engine is asked directly");
        assertFalse(script.toLowerCase(java.util.Locale.ROOT).contains("'webm'"), "no hardcoded format list");
        assertFalse(script.contains("video/mp4"), "an archive shipping MP4 must be left alone");
    }

    @Test
    void relativeSourcesAreResolvedAgainstTheDocument() {
        // The markup says src="videos/129163/video.webm"; an external player needs the absolute
        // loopback URL, so a bare relative string would hand the player something unopenable.
        assertTrue(MediaFallback.installScript("l", "p").contains("new URL(src, document.baseURI).href"));
    }

    @Test
    void labelsAreEscapedAsData() {
        String script = MediaFallback.installScript("Quote \" and <tag>", "Play \"now\"");
        assertFalse(script.contains("Quote \" and <tag>"), "raw text must never be spliced in");
        assertTrue(script.contains("\\u003Ctag>"), "angle brackets are escaped");
        assertTrue(script.contains("textContent"), "labels are assigned as text, never innerHTML");
    }

    @Test
    void theScriptIsIdempotentPerDocument() {
        // It runs on every load-succeeded event, and some pages fire more than one.
        String script = MediaFallback.installScript("l", "p");
        assertTrue(script.contains("__insulaMediaDone"), "a re-run must not replace placeholders again");
    }

    @Test
    void theBridgeCallIsNamedAndGuarded() {
        String script = MediaFallback.installScript("l", "p");
        assertTrue(script.contains("window." + MediaFallback.BRIDGE + ".playExternal("));
        assertTrue(script.contains("try {"), "a missing bridge must not throw inside page script");
    }

    @Test
    void bridgePassesOnlyNonBlankUrls() {
        List<String> opened = new ArrayList<>();
        MediaBridge bridge = new MediaBridge(opened::add);
        bridge.playExternal("http://127.0.0.1:9/zim/z1/C/videos/1/video.webm");
        bridge.playExternal(null);
        bridge.playExternal("   ");
        assertEquals(List.of("http://127.0.0.1:9/zim/z1/C/videos/1/video.webm"), opened);
    }

    @Test
    void theLabelSaysWhatHappensNext() {
        assertTrue(MediaFallback.defaultLabel().contains("outside"), MediaFallback.defaultLabel());
    }
}
