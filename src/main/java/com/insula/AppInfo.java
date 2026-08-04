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
     *
     * <p>A build with no filtered resource reports {@code 0.0.0}, which is visibly not a release
     * rather than a plausible wrong number.
     */
    public static final String VERSION = loadVersion();

    public static final String HOMEPAGE = "https://github.com/adriandeleon/insula";

    /**
     * Sent on every request to Kiwix's catalog and to the donated mirrors.
     *
     * <p>Deliberately identifies the project and links to it. Insula downloads gigabytes from
     * bandwidth other people pay for; if an operator sees traffic they do not like, they should be
     * able to find out what it is and contact us rather than having to block an anonymous Java
     * user-agent.
     */
    public static final String USER_AGENT = NAME + "/" + VERSION + " (+" + HOMEPAGE + ")";

    private static String loadVersion() {
        try (InputStream in = AppInfo.class.getResourceAsStream("build-info.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version", "").trim();
                // An unfiltered run leaves the token itself behind, which must not become a version.
                if (!version.isEmpty() && !version.startsWith("${")) {
                    return version;
                }
            }
        } catch (IOException ignored) {
            // Falls through to the placeholder; identity must never stop the app starting.
        }
        return "0.0.0";
    }

    private AppInfo() {}
}
