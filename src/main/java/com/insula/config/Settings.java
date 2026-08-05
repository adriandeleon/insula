package com.insula.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * User preferences, persisted as a properties file (default {@code ~/.insula/settings.properties}).
 * Loads fall back to defaults on a missing or garbled file; values are clamped/normalized on read.
 * Writes go through a temp file + atomic move so a crash can never leave half a config.
 */
public final class Settings {

    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_SYSTEM = "system";

    public static final int MIN_ZOOM = 50;
    public static final int MAX_ZOOM = 300;
    public static final int MIN_SEARCH_LIMIT = 5;
    public static final int MAX_SEARCH_LIMIT = 200;

    private final Path file;

    private String theme = THEME_LIGHT;
    private int zoomPercent = 100;
    private boolean reopenLastArchive = true;
    private String lastArchive = "";

    /** Where downloads land. Blank means the default beside the config, resolved by the caller. */
    private String archivesFolder = "";

    /** Newline-separated MRU of opened archives; see {@link RecentList}. */
    private String recentArchives = "";

    private int searchLimit = 40;

    /**
     * Prefer BitTorrent for archives above {@code TransportSelector.DEFAULT_TORRENT_THRESHOLD}
     * when the native library loaded. Off by default: on the restricted networks this app targets
     * (schools, NGOs) BitTorrent is frequently blocked, so HTTP stays the guaranteed path — and it
     * remains the fallback even when this is on.
     */
    private boolean torrentEnabled = false;
    /**
     * Seeding is opt-in and default-off on purpose: a meaningful share of these users are on
     * metered or expensive connections, and silently uploading tens of GB is how an app earns an
     * uninstall.
     */
    private boolean seedingEnabled = false;

    private boolean workOffline = false;

    /**
     * Archives at least this many GB prefer BitTorrent. Stored in whole gigabytes because that is
     * the unit the choice is actually made in; 0 means "always prefer it".
     */
    private int torrentThresholdGb = DEFAULT_TORRENT_THRESHOLD_GB;

    /** Reader mode: how much of the archive's own styling to override ("original"/"comfortable"/"dark"). */
    private String readerMode = "original";
    /** Content column in pixels; MAX means unconstrained. */
    private int readerWidth = 900;
    /** Whether to return to where you left off in an article. */
    private boolean rememberPosition = true;

    /** How the shelf is arranged. Kept here rather than in the index so it is a preference. */
    /** What to do with a superseded archive once its replacement verifies. */
    private String updatePolicy = "ask";

    /** How many downloads may run at once. */
    private int maxConcurrentDownloads = DEFAULT_CONCURRENT_DOWNLOADS;

    /** Reader sidebar: whether it is showing, and which edge it lives on. */
    private boolean sidebarVisible = true;

    private String sidebarSide = "left";

    /**
     * Distraction-free reading: the article and nothing else.
     *
     * <p>Persisted, so a mode entered deliberately is still on tomorrow. Safe to persist because it
     * is never a trap — the floating exit button, Escape, {@code Ctrl+Shift+Z} and leaving the
     * Reader surface all get out of it.
     */
    private boolean zenMode;

    /**
     * Whether to ask GitHub, once a day, whether a newer Insula has been published.
     *
     * <p>On by default, and honest about it: the app already fetches the catalog unprompted, so
     * this is not a new kind of traffic. It is off whenever {@link #workOffline} is on — an app
     * whose premise is working without a connection must not be the one thing that ignores the
     * switch — and the Settings entry says what it contacts and that it sends nothing.
     */
    private boolean updateCheck = true;

    /** When the background check last ran, so it runs at most daily rather than at every launch. */
    private long lastUpdateCheckEpoch;

    /**
     * A version the user has already been shown and acted on.
     *
     * <p>The notice is a small permanent thing in the status bar, not a dialog, so it needs a way
     * to stop being there. Recording the <em>version</em> rather than a flag means the next release
     * is announced again without anything having to clear it.
     */
    private String dismissedUpdateVersion = "";

    private String libraryGroupBy = "THEME";

    private String librarySortBy = "CUSTOM";

    /** Port for "Share on local network"; 0 = pick an ephemeral port. Sharing itself is session-only. */
    private int lanPort = 8181;

    // Reader View (the Firefox-style distilled page) typography. Defaults mirror Firefox's:
    // serif at 20px in a ~680px column with 1.6 line height, light background.
    private String readerFontFamily = "";
    private int readerFontPercent = 100;
    private String readerViewFont = "serif";
    private int readerViewFontSize = 20;
    private int readerViewWidth = 680;
    private double readerViewLineHeight = 1.6;
    private String readerViewTheme = "light";

    /** In-app video: transcode WebM to H.264 with ffmpeg. Inert until ffmpeg is found on PATH. */
    private boolean videoTranscode = true;

    private String ffmpegPath = "";
    private String ffprobePath = "";

    private Settings(Path file) {
        this.file = file;
    }

    public static Settings load(Path file) {
        Settings settings = new Settings(file);
        if (Files.isRegularFile(file)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                return settings; // unreadable file: defaults
            }
            settings.theme = normalizeTheme(props.getProperty("theme", settings.theme));
            settings.zoomPercent =
                    clamp(parseInt(props.getProperty("zoomPercent"), settings.zoomPercent), MIN_ZOOM, MAX_ZOOM);
            settings.reopenLastArchive =
                    Boolean.parseBoolean(props.getProperty("reopenLastArchive", String.valueOf(true)));
            settings.lastArchive = props.getProperty("lastArchive", "");
            settings.searchLimit = clamp(
                    parseInt(props.getProperty("searchLimit"), settings.searchLimit),
                    MIN_SEARCH_LIMIT,
                    MAX_SEARCH_LIMIT);
            settings.readerMode = props.getProperty("readerMode", settings.readerMode);
            settings.readerWidth = parseInt(props.getProperty("readerWidth"), settings.readerWidth);
            settings.rememberPosition = Boolean.parseBoolean(props.getProperty("rememberPosition", "true"));
            settings.torrentEnabled = Boolean.parseBoolean(props.getProperty("torrentEnabled", "false"));
            settings.seedingEnabled = Boolean.parseBoolean(props.getProperty("seedingEnabled", "false"));
            settings.workOffline = Boolean.parseBoolean(props.getProperty("workOffline", "false"));
            settings.lanPort = clamp(parseInt(props.getProperty("lanPort"), settings.lanPort), 0, 65535);
            settings.archivesFolder = props.getProperty("archivesFolder", settings.archivesFolder);
            settings.recentArchives = props.getProperty("recentArchives", settings.recentArchives);
            settings.torrentThresholdGb = clamp(
                    parseInt(props.getProperty("torrentThresholdGb"), settings.torrentThresholdGb),
                    MIN_TORRENT_THRESHOLD_GB,
                    MAX_TORRENT_THRESHOLD_GB);
            settings.sidebarVisible = Boolean.parseBoolean(props.getProperty("sidebarVisible", "true"));
            settings.sidebarSide = props.getProperty("sidebarSide", settings.sidebarSide);
            settings.zenMode = Boolean.parseBoolean(props.getProperty("zenMode", "false"));
            settings.updateCheck = Boolean.parseBoolean(props.getProperty("updateCheck", "true"));
            settings.lastUpdateCheckEpoch = Math.max(0, parseLong(props.getProperty("lastUpdateCheckEpoch"), 0));
            settings.dismissedUpdateVersion = props.getProperty("dismissedUpdateVersion", "");
            settings.updatePolicy = props.getProperty("updatePolicy", settings.updatePolicy);
            settings.maxConcurrentDownloads = clamp(
                    parseInt(props.getProperty("maxConcurrentDownloads"), settings.maxConcurrentDownloads),
                    MIN_CONCURRENT_DOWNLOADS,
                    MAX_CONCURRENT_DOWNLOADS);
            settings.libraryGroupBy = props.getProperty("libraryGroupBy", settings.libraryGroupBy);
            settings.librarySortBy = props.getProperty("librarySortBy", settings.librarySortBy);
            settings.readerFontFamily = props.getProperty("readerFontFamily", settings.readerFontFamily);
            settings.readerFontPercent = clamp(
                    parseInt(props.getProperty("readerFontPercent"), settings.readerFontPercent),
                    MIN_FONT_PERCENT,
                    MAX_FONT_PERCENT);
            settings.readerViewFont = props.getProperty("readerViewFont", settings.readerViewFont);
            settings.readerViewFontSize =
                    parseInt(props.getProperty("readerViewFontSize"), settings.readerViewFontSize);
            settings.readerViewWidth = parseInt(props.getProperty("readerViewWidth"), settings.readerViewWidth);
            settings.readerViewLineHeight =
                    parseDouble(props.getProperty("readerViewLineHeight"), settings.readerViewLineHeight);
            settings.readerViewTheme = props.getProperty("readerViewTheme", settings.readerViewTheme);
            settings.videoTranscode = Boolean.parseBoolean(props.getProperty("videoTranscode", "true"));
            settings.ffmpegPath = props.getProperty("ffmpegPath", "");
            settings.ffprobePath = props.getProperty("ffprobePath", "");
        }
        return settings;
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty("theme", theme);
        props.setProperty("zoomPercent", String.valueOf(zoomPercent));
        props.setProperty("reopenLastArchive", String.valueOf(reopenLastArchive));
        props.setProperty("lastArchive", lastArchive);
        props.setProperty("searchLimit", String.valueOf(searchLimit));
        props.setProperty("readerMode", readerMode);
        props.setProperty("readerWidth", String.valueOf(readerWidth));
        props.setProperty("rememberPosition", String.valueOf(rememberPosition));
        props.setProperty("torrentEnabled", String.valueOf(torrentEnabled));
        props.setProperty("seedingEnabled", String.valueOf(seedingEnabled));
        props.setProperty("workOffline", String.valueOf(workOffline));
        props.setProperty("lanPort", String.valueOf(lanPort));
        props.setProperty("archivesFolder", archivesFolder);
        props.setProperty("recentArchives", recentArchives);
        props.setProperty("torrentThresholdGb", String.valueOf(torrentThresholdGb));
        props.setProperty("sidebarVisible", String.valueOf(sidebarVisible));
        props.setProperty("sidebarSide", sidebarSide);
        props.setProperty("zenMode", String.valueOf(zenMode));
        props.setProperty("updateCheck", String.valueOf(updateCheck));
        props.setProperty("lastUpdateCheckEpoch", String.valueOf(lastUpdateCheckEpoch));
        props.setProperty("dismissedUpdateVersion", dismissedUpdateVersion);
        props.setProperty("updatePolicy", updatePolicy);
        props.setProperty("maxConcurrentDownloads", String.valueOf(maxConcurrentDownloads));
        props.setProperty("libraryGroupBy", libraryGroupBy);
        props.setProperty("librarySortBy", librarySortBy);
        props.setProperty("readerFontFamily", readerFontFamily);
        props.setProperty("readerFontPercent", String.valueOf(readerFontPercent));
        props.setProperty("readerViewFont", readerViewFont);
        props.setProperty("readerViewFontSize", String.valueOf(readerViewFontSize));
        props.setProperty("readerViewWidth", String.valueOf(readerViewWidth));
        props.setProperty("readerViewLineHeight", String.valueOf(readerViewLineHeight));
        props.setProperty("readerViewTheme", readerViewTheme);
        props.setProperty("videoTranscode", String.valueOf(videoTranscode));
        props.setProperty("ffmpegPath", ffmpegPath);
        props.setProperty("ffprobePath", ffprobePath);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                props.store(out, "Insula settings");
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save settings to " + file, e);
        }
    }

    private static String normalizeTheme(String value) {
        if (THEME_DARK.equalsIgnoreCase(value)) {
            return THEME_DARK;
        }
        return THEME_SYSTEM.equalsIgnoreCase(value) ? THEME_SYSTEM : THEME_LIGHT;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Path file() {
        return file;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = normalizeTheme(theme);
    }

    /** True only for an explicit dark choice; "system" resolves where the OS is reachable. */
    public boolean isDark() {
        return THEME_DARK.equals(theme);
    }

    public boolean isSystemTheme() {
        return THEME_SYSTEM.equals(theme);
    }

    public int getZoomPercent() {
        return zoomPercent;
    }

    public void setZoomPercent(int zoomPercent) {
        this.zoomPercent = clamp(zoomPercent, MIN_ZOOM, MAX_ZOOM);
    }

    public boolean isReopenLastArchive() {
        return reopenLastArchive;
    }

    public void setReopenLastArchive(boolean reopenLastArchive) {
        this.reopenLastArchive = reopenLastArchive;
    }

    public String getLastArchive() {
        return lastArchive;
    }

    public void setLastArchive(String lastArchive) {
        this.lastArchive = lastArchive == null ? "" : lastArchive;
    }

    public String getReaderMode() {
        return readerMode;
    }

    public void setReaderMode(String readerMode) {
        this.readerMode = readerMode == null ? "original" : readerMode;
    }

    public int getReaderWidth() {
        return readerWidth;
    }

    public void setReaderWidth(int readerWidth) {
        this.readerWidth = readerWidth;
    }

    public boolean isRememberPosition() {
        return rememberPosition;
    }

    public void setRememberPosition(boolean rememberPosition) {
        this.rememberPosition = rememberPosition;
    }

    public boolean isTorrentEnabled() {
        return torrentEnabled;
    }

    public void setTorrentEnabled(boolean torrentEnabled) {
        this.torrentEnabled = torrentEnabled;
    }

    /** Whether the user has asked the app to stay off the network entirely. */
    /** Steps the article text can take. Beyond these it is either unreadable or one word a line. */
    public static final int MIN_FONT_PERCENT = 70;

    public static final int MAX_FONT_PERCENT = 250;

    /**
     * The typeface imposed on article prose as a CSS family list, or blank to leave every archive
     * with the typefaces it was published with — which is the honest default for a reader whose
     * job is to show you what is in the file.
     */
    public String getReaderFontFamily() {
        return readerFontFamily;
    }

    public void setReaderFontFamily(String family) {
        this.readerFontFamily = family == null ? "" : family.trim();
    }

    /** Article text size as a percentage. Distinct from zoom, which scales images too. */
    public int getReaderFontPercent() {
        return readerFontPercent;
    }

    public void setReaderFontPercent(int percent) {
        this.readerFontPercent = clamp(percent, MIN_FONT_PERCENT, MAX_FONT_PERCENT);
    }

    public boolean isWorkOffline() {
        return workOffline;
    }

    public void setWorkOffline(boolean workOffline) {
        this.workOffline = workOffline;
    }

    public boolean isSeedingEnabled() {
        return seedingEnabled;
    }

    public void setSeedingEnabled(boolean seedingEnabled) {
        this.seedingEnabled = seedingEnabled;
    }

    // Reader View typography. Range clamping lives in ReaderView.Prefs.normalized — the single
    // authority — so these store what they are given and the reader normalizes on use.

    public String getReaderViewFont() {
        return readerViewFont;
    }

    public void setReaderViewFont(String font) {
        this.readerViewFont = font == null ? "serif" : font;
    }

    public int getReaderViewFontSize() {
        return readerViewFontSize;
    }

    public void setReaderViewFontSize(int px) {
        this.readerViewFontSize = px;
    }

    public int getReaderViewWidth() {
        return readerViewWidth;
    }

    public void setReaderViewWidth(int px) {
        this.readerViewWidth = px;
    }

    public double getReaderViewLineHeight() {
        return readerViewLineHeight;
    }

    public void setReaderViewLineHeight(double lineHeight) {
        this.readerViewLineHeight = lineHeight;
    }

    public String getReaderViewTheme() {
        return readerViewTheme;
    }

    public void setReaderViewTheme(String theme) {
        this.readerViewTheme = theme == null ? "light" : theme;
    }

    public boolean isVideoTranscode() {
        return videoTranscode;
    }

    public void setVideoTranscode(boolean videoTranscode) {
        this.videoTranscode = videoTranscode;
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }

    public void setFfmpegPath(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath == null ? "" : ffmpegPath;
    }

    public String getFfprobePath() {
        return ffprobePath;
    }

    public void setFfprobePath(String ffprobePath) {
        this.ffprobePath = ffprobePath == null ? "" : ffprobePath;
    }

    public static final int MIN_CONCURRENT_DOWNLOADS = 1;

    /**
     * More than a handful of parallel archive downloads makes every one of them slower without
     * making the set finish sooner — the bottleneck is the link, not the number of sockets.
     */
    public static final int MAX_CONCURRENT_DOWNLOADS = 6;

    public static final int DEFAULT_CONCURRENT_DOWNLOADS = 2;

    /** 0 means every archive prefers BitTorrent, whatever its size. */
    public static final int MIN_TORRENT_THRESHOLD_GB = 0;

    public static final int MAX_TORRENT_THRESHOLD_GB = 64;

    public static final int DEFAULT_TORRENT_THRESHOLD_GB = 1;

    public int getTorrentThresholdGb() {
        return torrentThresholdGb;
    }

    public void setTorrentThresholdGb(int gb) {
        this.torrentThresholdGb = clamp(gb, MIN_TORRENT_THRESHOLD_GB, MAX_TORRENT_THRESHOLD_GB);
    }

    /** The threshold as the selector wants it. */
    public long getTorrentThresholdBytes() {
        return (long) torrentThresholdGb * 1024 * 1024 * 1024;
    }

    public boolean isSidebarVisible() {
        return sidebarVisible;
    }

    public void setSidebarVisible(boolean visible) {
        this.sidebarVisible = visible;
    }

    /** {@code "right"} or {@code "left"}; anything else means left. */
    public boolean isSidebarOnRight() {
        return "right".equalsIgnoreCase(sidebarSide);
    }

    public void setSidebarOnRight(boolean right) {
        this.sidebarSide = right ? "right" : "left";
    }

    /** Distraction-free reading. See {@code Chrome} for what it actually hides. */
    public boolean isZenMode() {
        return zenMode;
    }

    public void setZenMode(boolean zenMode) {
        this.zenMode = zenMode;
    }

    /** Whether to check GitHub for a newer Insula. Never runs while working offline. */
    public boolean isUpdateCheck() {
        return updateCheck;
    }

    public void setUpdateCheck(boolean updateCheck) {
        this.updateCheck = updateCheck;
    }

    public long getLastUpdateCheckEpoch() {
        return lastUpdateCheckEpoch;
    }

    public void setLastUpdateCheckEpoch(long epochMillis) {
        this.lastUpdateCheckEpoch = Math.max(0, epochMillis);
    }

    /** The newest version whose notice the user has already dealt with; {@code ""} for none. */
    public String getDismissedUpdateVersion() {
        return dismissedUpdateVersion;
    }

    public void setDismissedUpdateVersion(String version) {
        this.dismissedUpdateVersion = version == null ? "" : version.strip();
    }

    /** The configured archives folder, or {@code defaultFolder} when the user has not chosen one. */
    public java.nio.file.Path getArchivesFolder(java.nio.file.Path defaultFolder) {
        return archivesFolder.isBlank() ? defaultFolder : java.nio.file.Path.of(archivesFolder);
    }

    /** Pass null or the default back to mean "unset", so the folder follows the config dir again. */
    public void setArchivesFolder(java.nio.file.Path folder) {
        this.archivesFolder = folder == null ? "" : folder.toAbsolutePath().toString();
    }

    public java.util.List<String> getRecentArchives() {
        return RecentList.decode(recentArchives);
    }

    public void setRecentArchives(java.util.List<String> entries) {
        this.recentArchives = RecentList.encode(entries == null ? java.util.List.of() : entries);
    }

    public com.insula.library.UpdateReplacement.Policy getUpdatePolicy() {
        return com.insula.library.UpdateReplacement.Policy.parse(updatePolicy);
    }

    public void setUpdatePolicy(com.insula.library.UpdateReplacement.Policy policy) {
        this.updatePolicy = (policy == null ? com.insula.library.UpdateReplacement.Policy.ASK : policy).stored();
    }

    public int getMaxConcurrentDownloads() {
        return maxConcurrentDownloads;
    }

    public void setMaxConcurrentDownloads(int value) {
        this.maxConcurrentDownloads = clamp(value, MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS);
    }

    public String getLibraryGroupBy() {
        return libraryGroupBy;
    }

    public void setLibraryGroupBy(String value) {
        this.libraryGroupBy = value == null ? "THEME" : value;
    }

    public String getLibrarySortBy() {
        return librarySortBy;
    }

    public void setLibrarySortBy(String value) {
        this.librarySortBy = value == null ? "CUSTOM" : value;
    }

    public int getLanPort() {
        return lanPort;
    }

    public void setLanPort(int lanPort) {
        this.lanPort = clamp(lanPort, 0, 65535);
    }

    public int getSearchLimit() {
        return searchLimit;
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = clamp(searchLimit, MIN_SEARCH_LIMIT, MAX_SEARCH_LIMIT);
    }
}
