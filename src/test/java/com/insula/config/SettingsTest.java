package com.insula.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    @Test
    void missingFileYieldsDefaults(@TempDir Path dir) {
        Settings settings = Settings.load(dir.resolve("absent.properties"));
        assertEquals(Settings.THEME_LIGHT, settings.getTheme());
        assertEquals(100, settings.getZoomPercent());
        assertEquals(40, settings.getSearchLimit());
        assertTrue(settings.isReopenLastArchive());
        assertEquals("", settings.getLastArchive());
    }

    @Test
    void roundTripsThroughDisk(@TempDir Path dir) {
        Path file = dir.resolve("nested/settings.properties"); // parent dirs are created on save
        Settings saved = Settings.load(file);
        saved.setTheme(Settings.THEME_DARK);
        saved.setZoomPercent(140);
        saved.setSearchLimit(75);
        saved.setReopenLastArchive(false);
        saved.setLastArchive("/tmp/wiki.zim");
        saved.save();

        Settings reloaded = Settings.load(file);
        assertEquals(Settings.THEME_DARK, reloaded.getTheme());
        assertTrue(reloaded.isDark());
        assertEquals(140, reloaded.getZoomPercent());
        assertEquals(75, reloaded.getSearchLimit());
        assertFalse(reloaded.isReopenLastArchive());
        assertEquals("/tmp/wiki.zim", reloaded.getLastArchive());
    }

    @Test
    void clampsOutOfRangeValues(@TempDir Path dir) {
        Settings settings = Settings.load(dir.resolve("s.properties"));
        settings.setZoomPercent(10_000);
        assertEquals(Settings.MAX_ZOOM, settings.getZoomPercent());
        settings.setZoomPercent(-5);
        assertEquals(Settings.MIN_ZOOM, settings.getZoomPercent());
        settings.setSearchLimit(0);
        assertEquals(Settings.MIN_SEARCH_LIMIT, settings.getSearchLimit());
        settings.setSearchLimit(9999);
        assertEquals(Settings.MAX_SEARCH_LIMIT, settings.getSearchLimit());
    }

    @Test
    void garbledFileFallsBackToDefaultsPerField(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("garbled.properties");
        Files.writeString(file, "theme=chartreuse\nzoomPercent=not-a-number\nsearchLimit=\n");

        Settings settings = Settings.load(file);
        assertEquals(Settings.THEME_LIGHT, settings.getTheme(), "unknown theme normalizes to light");
        assertEquals(100, settings.getZoomPercent());
        assertEquals(40, settings.getSearchLimit());
    }

    @Test
    void outOfRangeValuesOnDiskAreClampedOnLoad(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("wild.properties");
        Files.writeString(file, "zoomPercent=5000\nsearchLimit=1\n");

        Settings settings = Settings.load(file);
        assertEquals(Settings.MAX_ZOOM, settings.getZoomPercent());
        assertEquals(Settings.MIN_SEARCH_LIMIT, settings.getSearchLimit());
    }

    @Test
    void saveLeavesNoTempFileBehind(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        Settings settings = Settings.load(file);
        settings.setTheme(Settings.THEME_DARK);
        settings.save();
        settings.save(); // overwrite an existing file

        try (var entries = Files.list(dir)) {
            assertEquals(
                    List.of("settings.properties"),
                    entries.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void readerViewPreferencesRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        Settings settings = Settings.load(file);
        assertEquals("serif", settings.getReaderViewFont(), "Firefox's default is serif");
        assertEquals(20, settings.getReaderViewFontSize());
        assertEquals("light", settings.getReaderViewTheme());

        settings.setReaderViewFont("sans");
        settings.setReaderViewFontSize(24);
        settings.setReaderViewWidth(760);
        settings.setReaderViewLineHeight(1.8);
        settings.setReaderViewTheme("sepia");
        settings.save();

        Settings reloaded = Settings.load(file);
        assertEquals("sans", reloaded.getReaderViewFont());
        assertEquals(24, reloaded.getReaderViewFontSize());
        assertEquals(760, reloaded.getReaderViewWidth());
        assertEquals(1.8, reloaded.getReaderViewLineHeight());
        assertEquals("sepia", reloaded.getReaderViewTheme());
    }

    @Test
    void themeAcceptsSystemAsAFirstClassChoice(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        Settings settings = Settings.load(file);
        assertEquals(Settings.THEME_LIGHT, settings.getTheme(), "light is the default");
        assertFalse(settings.isSystemTheme());

        settings.setTheme("system");
        assertTrue(settings.isSystemTheme());
        // "System" is not "dark": the brightness is resolved where the OS preference is reachable,
        // so isDark() must report only an explicit choice.
        assertFalse(settings.isDark());
        settings.save();
        assertEquals(Settings.THEME_SYSTEM, Settings.load(file).getTheme());

        // Anything unrecognised still falls back to light rather than throwing.
        settings.setTheme("chartreuse");
        assertEquals(Settings.THEME_LIGHT, settings.getTheme());
        settings.setTheme(null);
        assertEquals(Settings.THEME_LIGHT, settings.getTheme());
    }
}
