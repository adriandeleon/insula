package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the capture keeps and how it renders it. */
class DebugLogTest {

    @AfterEach
    void reset() {
        DebugLog.clear();
    }

    @Test
    void aRecordCarriesItsLevelSourceAndText() {
        DebugLog.append(DebugLog.format(new LogRecord(Level.WARNING, "the catalog did not answer")));
        String out = DebugLog.snapshot();
        assertTrue(out.contains("WARNING"), out);
        assertTrue(out.contains("the catalog did not answer"), out);
    }

    @Test
    void aThrownExceptionBringsItsStackTrace() {
        LogRecord record = new LogRecord(Level.SEVERE, "failed");
        record.setThrown(new IllegalStateException("boom"));
        DebugLog.append(DebugLog.format(record));
        String out = DebugLog.snapshot();
        assertTrue(out.contains("IllegalStateException: boom"), out);
        assertTrue(out.contains("at com.insula.app.DebugLogTest"), "the frames are the point: " + out);
    }

    @Test
    void thePackagePrefixIsNoiseInAViewer() {
        assertEquals("TorrentTransport", DebugLog.shortName("com.insula.download.TorrentTransport"));
        assertEquals("?", DebugLog.shortName(null));
        assertEquals("root", DebugLog.shortName("root"));
    }

    @Test
    void aLongSessionCannotGrowWithoutLimit() {
        for (int i = 0; i < DebugLog.MAX_RECORDS + 20; i++) {
            DebugLog.append("line " + i);
        }
        String out = DebugLog.snapshot();
        assertFalse(out.contains("line 0\n"), "the oldest are evicted");
        assertTrue(out.contains("line " + (DebugLog.MAX_RECORDS + 19)));
    }

    @Test
    void theAppsOwnFineMessagesAreCaptured() {
        // Eighteen of the app's twenty-one log calls are FINE — a torrent that died, a web seed
        // that would not attach — and at the default INFO threshold the log would miss every one.
        //
        // Deliberately not a com.insula.download logger: this project's test-only
        // logging.properties silences that package at SEVERE so failure-path tests do not print
        // stack traces on a green run, and an explicit level on a descendant beats anything set
        // on an ancestor. Picking that name would test the test config, not the product.
        DebugLog.install();
        java.util.logging.Logger.getLogger("com.insula.app.SomeFeature").fine("could not open the archive");
        assertTrue(DebugLog.snapshot().contains("could not open the archive"), DebugLog.snapshot());
    }

    @Test
    void aThirdPartysFineChatterIsDropped() {
        // The threshold is off the root logger, so third-party FINE now reaches the handler. The
        // filter is what keeps a bounded buffer from being spent on WebKit internals.
        DebugLog.install();
        java.util.logging.Logger.getLogger("com.frostwire.jlibtorrent.Noise").fine("chatter");
        assertFalse(DebugLog.snapshot().contains("chatter"), DebugLog.snapshot());
    }

    @Test
    void aThirdPartysWarningIsKept() {
        DebugLog.install();
        // Muted at the console for the duration: this project keeps a green run's output clean so
        // that anything printed there is worth reading, and the record still reaches our handler.
        java.util.logging.Handler console =
                java.util.logging.Logger.getLogger("").getHandlers()[0];
        java.util.logging.Level previous = console.getLevel();
        console.setLevel(java.util.logging.Level.OFF);
        try {
            java.util.logging.Logger.getLogger("com.frostwire.jlibtorrent.Noise")
                    .warning("a real problem");
        } finally {
            console.setLevel(previous);
        }
        assertTrue(DebugLog.snapshot().contains("a real problem"), DebugLog.snapshot());
    }

    @Test
    void theSessionFileIsOwnerOnlyBecauseItRecordsWhatWasRead(@TempDir Path dir) throws Exception {
        DebugLog.append("something happened");
        DebugLog.attachFile(dir);
        Path log = DebugLog.sessionFile(dir);
        assertTrue(Files.isRegularFile(log));
        assertTrue(Files.readString(log).contains("something happened"), "capture so far is flushed");
        if (Files.getFileStore(log).supportsFileAttributeView("posix")) {
            assertEquals(
                    "rw-------",
                    java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(log)));
        }
        DebugLog.attachFile(null); // detach so later tests do not write into a deleted temp dir
    }
}
