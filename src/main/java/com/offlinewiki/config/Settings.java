package com.offlinewiki.config;

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
 * User preferences, persisted as a properties file (default {@code ~/.offline-wiki/settings.properties}).
 * Loads fall back to defaults on a missing or garbled file; values are clamped/normalized on read.
 * Writes go through a temp file + atomic move so a crash can never leave half a config.
 */
public final class Settings {

    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    public static final int MIN_ZOOM = 50;
    public static final int MAX_ZOOM = 300;
    public static final int MIN_SEARCH_LIMIT = 5;
    public static final int MAX_SEARCH_LIMIT = 200;

    private final Path file;

    private String theme = THEME_LIGHT;
    private int zoomPercent = 100;
    private boolean reopenLastArchive = true;
    private String lastArchive = "";
    private int searchLimit = 40;

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
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                props.store(out, "Offline Wiki settings");
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
        return THEME_DARK.equalsIgnoreCase(value) ? THEME_DARK : THEME_LIGHT;
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

    public boolean isDark() {
        return THEME_DARK.equals(theme);
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

    public int getSearchLimit() {
        return searchLimit;
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = clamp(searchLimit, MIN_SEARCH_LIMIT, MAX_SEARCH_LIMIT);
    }
}
