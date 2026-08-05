package com.insula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the build actually stamps its own version in.
 *
 * <p>Worth a test because the failure is silent and the fallback is plausible: if resource
 * filtering stops reaching {@code build-info.properties}, {@link AppInfo#VERSION} degrades to
 * {@code 0.0.0} — a well-formed version — and the User-Agent starts claiming it to every mirror
 * while every other test still passes.
 */
class AppInfoTest {

    @Test
    void theVersionComesFromTheBuildRatherThanFromSource() {
        assertFalse(AppInfo.VERSION.startsWith("${"), "the Maven placeholder was left unsubstituted");
        assertTrue(AppInfo.VERSION.matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?"), AppInfo.VERSION);
        // The unfiltered fallback is a well-formed version, so the regex above cannot tell the two
        // apart. This can: a Maven run always filters, so seeing the fallback here means it stopped.
        assertNotEquals("0.0.0", AppInfo.VERSION, "resource filtering did not reach build-info.properties");
    }

    @Test
    void theUserAgentCarriesThatVersionAndAWayToReachUs() {
        // Mirror operators see this on gigabytes of traffic; it has to identify the project.
        assertTrue(AppInfo.USER_AGENT.startsWith("Insula/" + AppInfo.VERSION));
        assertTrue(AppInfo.USER_AGENT.contains(AppInfo.HOMEPAGE));
    }

    @Test
    void snapshotsAreRecognizedAndComparedByTheirReleaseNumber() {
        assertEquals(AppInfo.VERSION.endsWith("-SNAPSHOT"), AppInfo.isSnapshot());
        assertFalse(AppInfo.releaseVersion().endsWith("-SNAPSHOT"));
        assertTrue(AppInfo.releaseVersion().matches("\\d+\\.\\d+\\.\\d+"));
    }

    @Test
    void theReleaseEndpointsPointAtThisProject() {
        assertTrue(AppInfo.LATEST_RELEASE_API.startsWith("https://api.github.com/repos/" + AppInfo.GITHUB_REPO));
        assertTrue(AppInfo.RELEASES_PAGE.startsWith("https://github.com/" + AppInfo.GITHUB_REPO));
    }
}
