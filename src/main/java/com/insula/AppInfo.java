package com.insula;

/** Identity of this build: the single source for the app name, version and User-Agent. */
public final class AppInfo {

    public static final String NAME = "Insula";
    public static final String VERSION = "0.1.0";
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

    private AppInfo() {}
}
