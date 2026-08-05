package com.insula;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Identity of this build: the single source for the app name, version and User-Agent. */
public final class AppInfo {

    public static final String NAME = "Insula";

    /**
     * Read from a Maven-filtered resource rather than written here, so {@code <version>} in the
     * pom is the only place a release number exists. Hardcoded, the two drift the moment one is
     * bumped without the other — and this one is not cosmetic: it goes out as the
     * {@link #USER_AGENT} on every request to Kiwix's catalog and to the donated mirrors, where a
     * version that is not what is really running is a lie told to somebody paying for bandwidth.
     * The update check compares against it too, so a stale one would report "up to date" forever.
     *
     * <p>A build with no filtered resource reports {@code 0.0.0}, which is visibly not a release
     * rather than a plausible wrong number. The update check then treats it as older than any real
     * release and offers the newest one, which for a run straight from an IDE is the useful answer.
     */
    public static final String VERSION = loadVersion();

    public static final String HOMEPAGE = "https://github.com/adriandeleon/insula";

    /** The repository that publishes releases — where the update check looks. */
    public static final String GITHUB_REPO = "adriandeleon/insula";

    /** The latest published release: never a draft, never a prerelease. */
    public static final String LATEST_RELEASE_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";

    /** Where a human goes to get it, and the fallback when a release carries no {@code html_url}. */
    public static final String RELEASES_PAGE = "https://github.com/" + GITHUB_REPO + "/releases/latest";

    /**
     * Sent on every request to Kiwix's catalog and to the donated mirrors.
     *
     * <p>Deliberately identifies the project and links to it. Insula downloads gigabytes from
     * bandwidth other people pay for; if an operator sees traffic they do not like, they should be
     * able to find out what it is and contact us rather than having to block an anonymous Java
     * user-agent. GitHub's API separately <em>requires</em> a User-Agent and answers 403 without
     * one, so the update check sends this too.
     */
    public static final String USER_AGENT = NAME + "/" + VERSION + " (+" + HOMEPAGE + ")";

    /** Maven's marker for a version that has not been released. */
    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    private AppInfo() {}

    /**
     * Whether this is an unreleased build — the pom carries {@code -SNAPSHOT} between releases.
     *
     * <p>The update check reads it to stay quiet: a development build is <em>expected</em> to differ
     * from the latest release, and telling its author to go and download the version they are
     * sitting on top of is noise.
     */
    public static boolean isSnapshot() {
        return VERSION.endsWith(SNAPSHOT_SUFFIX);
    }

    /** The version with any {@code -SNAPSHOT} removed, for comparing against a release tag. */
    public static String releaseVersion() {
        return VERSION.endsWith(SNAPSHOT_SUFFIX)
                ? VERSION.substring(0, VERSION.length() - SNAPSHOT_SUFFIX.length())
                : VERSION;
    }

    /** The build timestamp, or a marker when this run bypassed Maven's filtering. */
    public static String buildTime() {
        return property("build.time", "(dev build)");
    }

    private static String loadVersion() {
        return property("version", "0.0.0");
    }

    private static String property(String key, String fallback) {
        try (InputStream in = AppInfo.class.getResourceAsStream("build-info.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String value = properties.getProperty(key, "").trim();
                // An unfiltered run leaves the token itself behind, which must not become a value.
                if (!value.isEmpty() && !value.startsWith("${")) {
                    return value;
                }
            }
        } catch (IOException ignored) {
            // Falls through to the placeholder; identity must never stop the app starting.
        }
        return fallback;
    }
}
