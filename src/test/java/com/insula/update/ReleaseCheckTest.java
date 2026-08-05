package com.insula.update;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing GitHub's answer, deciding what is newer, and deciding when to ask again. */
class ReleaseCheckTest {

    private static ReleaseInfo parse(String json) {
        return ReleaseCheck.parseLatest(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsTheFieldsItNeedsOutOfAFullPayload() {
        // Shaped like the real thing: the wanted scalars are scattered among objects and arrays
        // the walk has to step over rather than trip on.
        ReleaseInfo release = parse("""
                {
                  "url": "https://api.github.com/repos/adriandeleon/insula/releases/1",
                  "author": {"login": "adriandeleon", "id": 42, "site_admin": false},
                  "html_url": "https://github.com/adriandeleon/insula/releases/tag/v0.2.0",
                  "tag_name": "v0.2.0",
                  "name": "Insula 0.2.0",
                  "draft": false,
                  "prerelease": false,
                  "assets": [
                    {"name": "insula_0.2.0_amd64.deb", "size": 91234567, "uploader": {"login": "x"}},
                    {"name": "Insula-0.2.0.dmg", "size": 88123456, "uploader": {"login": "x"}}
                  ],
                  "body": "### Added\\n- Zen mode\\n"
                }
                """);

        assertNotNull(release);
        assertEquals("0.2.0", release.version(), "the leading v is stripped");
        assertEquals("https://github.com/adriandeleon/insula/releases/tag/v0.2.0", release.url());
        assertEquals("Insula 0.2.0", release.name());
    }

    @Test
    void refusesADraftOrAPrerelease() {
        // Nobody should be nudged toward something not published yet.
        assertNull(parse("{\"tag_name\": \"v0.3.0\", \"draft\": true}"));
        assertNull(parse("{\"tag_name\": \"v0.3.0-rc1\", \"prerelease\": true}"));
    }

    @Test
    void refusesAnythingItCannotUse() {
        assertNull(parse("{\"name\": \"no tag here\"}"));
        assertNull(parse("{\"tag_name\": \"\"}"));
        assertNull(parse("[]"), "an array is not a release");
        assertNull(parse("{\"tag_name\": "), "truncated mid-object");
        assertNull(parse("not json at all"));
        assertNull(ReleaseCheck.parseLatest(new byte[0]));
        assertNull(ReleaseCheck.parseLatest(null));
    }

    @Test
    void aTagThatIsNotAStringIsNoTag() {
        // Types are the server's choice, not ours.
        assertNull(parse("{\"tag_name\": 2}"));
        assertNull(parse("{\"tag_name\": {\"nested\": \"v1.0.0\"}}"));
    }

    @Test
    void versionsCompareNumericallyNotAlphabetically() {
        // The bug every project gets exactly once, at its tenth release: "0.10.0" sorts before
        // "0.9.0" as text, so the update is never offered.
        assertTrue(ReleaseCheck.compareVersions("0.10.0", "0.9.0") > 0);
        assertTrue(ReleaseCheck.compareVersions("1.0.0", "0.99.99") > 0);
        assertEquals(0, ReleaseCheck.compareVersions("1.2", "1.2.0"), "a missing part is zero");
        assertTrue(ReleaseCheck.compareVersions("2.0.1", "2.0.1") == 0);
    }

    @Test
    void aVersionWeCannotReadIsNeverNewer() {
        // This runs on a tag someone typed by hand. Quiet beats wrong, and beats throwing.
        assertFalse(ReleaseCheck.isNewer("0.1.0", "not-a-version"));
        assertFalse(ReleaseCheck.isNewer("0.1.0", ""));
        assertFalse(ReleaseCheck.isNewer("0.1.0", null));
    }

    @Test
    void aBuildWithNoVersionIsNotToldToUpgrade() {
        // AppInfo answers "" when a run bypassed Maven's filtering. "Is anything newer than
        // nothing?" has no useful answer, and "yes, download this" is the wrong one.
        assertFalse(ReleaseCheck.isNewer("", "9.9.9"));
        assertFalse(ReleaseCheck.isNewer(null, "9.9.9"));
    }

    @Test
    void olderAndEqualReleasesAreNotOffered() {
        assertFalse(ReleaseCheck.isNewer("0.2.0", "0.1.0"));
        assertFalse(ReleaseCheck.isNewer("0.2.0", "0.2.0"));
        assertTrue(ReleaseCheck.isNewer("0.2.0", "0.2.1"));
    }

    @Test
    void everyBackgroundGateStopsTheCheckOnItsOwn() {
        // Each of these is checked separately because the controller cannot be: a test build is
        // always a snapshot, so driving the real window exercises that gate and nothing behind it.
        long day = ReleaseCheck.DEFAULT_INTERVAL_MS;
        long now = 10 * day; // far enough in that "half a day ago" is still a positive stamp
        assertTrue(ReleaseCheck.shouldCheckInBackground(true, false, false, 0, now, day), "all clear");

        assertFalse(ReleaseCheck.shouldCheckInBackground(false, false, false, 0, now, day), "setting off");
        assertFalse(
                ReleaseCheck.shouldCheckInBackground(true, true, false, 0, now, day),
                "working offline means no requests, not fewer");
        assertFalse(
                ReleaseCheck.shouldCheckInBackground(true, false, true, 0, now, day),
                "a snapshot build is meant to differ from the latest release");
        assertFalse(
                ReleaseCheck.shouldCheckInBackground(true, false, false, now - day / 2, now, day),
                "already checked today");
    }

    @Test
    void checksAreDueOnceADayAndOnAFirstRun() {
        long day = ReleaseCheck.DEFAULT_INTERVAL_MS;
        assertTrue(ReleaseCheck.isDue(0, 1_000_000, day), "never checked");
        assertFalse(ReleaseCheck.isDue(1_000_000, 1_000_000 + day / 2, day));
        assertTrue(ReleaseCheck.isDue(1_000_000, 1_000_000 + day, day));
    }

    @Test
    void aClockMovedBackwardsDoesNotStopTheChecksForever() {
        // Otherwise the app quietly gives up until the calendar catches up with the stamp.
        assertTrue(ReleaseCheck.isDue(9_000_000, 1_000, ReleaseCheck.DEFAULT_INTERVAL_MS));
    }

    @Test
    void normalizeStripsOnlyALeadingV() {
        assertEquals("1.2.3", ReleaseCheck.normalizeVersion("v1.2.3"));
        assertEquals("1.2.3", ReleaseCheck.normalizeVersion("V1.2.3"));
        assertEquals("1.2.3", ReleaseCheck.normalizeVersion("  1.2.3  "));
        assertEquals("", ReleaseCheck.normalizeVersion(null));
    }
}
