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

    @Test
    void theInAppButtonAppearsOnlyWhenATranscoderExists() {
        // Offering "Play here" without ffmpeg would be a button that cannot work. The branch is
        // guarded at runtime rather than omitted from the template, so this checks the guard —
        // MediaFallbackFxTest proves the button count that results.
        String without = MediaFallback.installScript("l", "Open externally", null);
        assertTrue(without.contains("var inApp = null"), "the in-app branch is switched off");

        String with = MediaFallback.installScript("l", "Open externally", "Play here");
        assertTrue(with.contains("window." + MediaFallback.BRIDGE + ".playInApp(boxId,"));
        assertTrue(with.contains("Play here"));
        assertTrue(with.contains("playExternal("), "the external route stays available either way");
    }

    @Test
    void placeholdersAreIdentifiableSoProgressCanTargetThem() {
        assertTrue(MediaFallback.installScript("l", "p", "go").contains("box.id = boxId"));
        assertTrue(
                MediaFallback.progressScript("insula-media-0", 40, "Preparing…").contains("insula-media-0"));
        assertTrue(MediaFallback.progressScript("b", 40, "x").contains("width:40%"));
        // Out-of-range percentages must not produce nonsense CSS.
        assertTrue(MediaFallback.progressScript("b", 500, "x").contains("width:100%"));
        assertTrue(MediaFallback.progressScript("b", -5, "x").contains("width:0%"));
    }

    @Test
    void bridgeIgnoresIncompleteInAppRequests() {
        java.util.List<String> boxes = new java.util.ArrayList<>();
        MediaBridge bridge = new MediaBridge(u -> {}, (box, url) -> boxes.add(box + " " + url));
        bridge.playInApp("box-1", "http://h/v.webm");
        bridge.playInApp(null, "http://h/v.webm");
        bridge.playInApp("box-2", "  ");
        assertEquals(java.util.List.of("box-1 http://h/v.webm"), boxes);
    }
}
