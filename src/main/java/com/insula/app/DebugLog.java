package com.insula.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * In-memory capture of the app's {@code java.util.logging} output and any uncaught exception.
 *
 * <p>Insula is normally run as a packaged bundle, where stderr goes nowhere anybody will look — so
 * when an archive fails to open or a download dies, the explanation the app already wrote is lost.
 * This keeps the last {@link #MAX_RECORDS} of them so Settings can show them, and mirrors the same
 * lines to a file in the config dir so they survive the crash that produced them and can be
 * attached to a bug report.
 *
 * <p>Install from {@code Main.main} before anything else logs; attach the file from
 * {@code Main.start} once the config dir is known. Thread-safe, because log records arrive on
 * whichever thread did the work.
 */
public final class DebugLog {

    /** Retained records. Each may be multi-line — a stack trace is one record. */
    static final int MAX_RECORDS = 2000;

    static final String FILE_NAME = "insula-session.log";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final Object LOCK = new Object();
    private static final Deque<String> RECORDS = new ArrayDeque<>();
    private static boolean installed;
    private static PrintWriter file;

    /**
     * A strong reference, deliberately. LogManager holds its loggers <em>weakly</em>: a level set
     * on a logger nobody else is holding survives only until the next collection, after which the
     * logger is recreated at the inherited default and the level silently reverts.
     */
    private static final Logger APP_LOGGER = Logger.getLogger("com.insula");

    private DebugLog() {}

    /** Attaches a root-logger handler and an uncaught-exception handler. Idempotent. */
    public static void install() {
        synchronized (LOCK) {
            if (installed) {
                return;
            }
            installed = true;
        }
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (isLoggable(record)) {
                    append(format(record));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);
        // What to keep, decided here rather than by logger levels: everything at INFO and above
        // from anywhere, plus everything Insula itself logs at any level.
        //
        // That second half is load-bearing. Eighteen of the app's twenty-one log calls are FINE —
        // a torrent that died, a web seed that would not attach, an archive that would not open —
        // and a log holding only the three SEVERE ones misses exactly what somebody opens it to
        // read. Third-party FINE chatter is dropped because a bounded buffer spent on WebKit
        // internals is a buffer without the line that matters in it.
        handler.setFilter(record -> record.getLevel().intValue() >= Level.INFO.intValue()
                || (record.getLoggerName() != null && record.getLoggerName().startsWith("com.insula")));
        Logger.getLogger("").addHandler(handler);

        // Opened on our own namespace rather than on the root: a root at ALL would make every
        // FINE call anywhere in the JVM materialise a LogRecord for the filter above to throw
        // away, which is a cost paid on every log call in WebKit and libtorrent for nothing.
        // Descendants inherit unless something sets them explicitly. The console handler keeps its
        // own level, so stderr is unchanged and the extra records go only to the buffer and file.
        APP_LOGGER.setLevel(Level.ALL);

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> append(
                line(Instant.now(), "SEVERE", "uncaught", "Uncaught exception in thread \"" + thread.getName() + "\"")
                        + System.lineSeparator()
                        + stackTrace(error)));
    }

    /**
     * Mirrors captured records to {@code <configDir>/insula-session.log}, truncating the previous
     * session's file and flushing everything captured so far.
     *
     * <p>Owner-only: this records the paths of everything read and the text of every error, which
     * is nobody else's business on a shared machine. Best-effort — a failure here disables the
     * mirror and leaves the in-memory capture alone, because a log that cannot be written is not a
     * reason to fail to start. A null dir detaches.
     */
    public static void attachFile(Path configDir) {
        synchronized (LOCK) {
            if (file != null) {
                file.close();
                file = null;
            }
            if (configDir == null) {
                return; // detach: the in-memory capture carries on
            }
            try {
                Path path = configDir.resolve(FILE_NAME);
                Files.createDirectories(configDir);
                Files.deleteIfExists(path);
                try {
                    Files.createFile(
                            path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException e) {
                    Files.createFile(path); // not a POSIX filesystem; Windows inherits its own ACL
                }
                file = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
                for (String record : RECORDS) {
                    file.println(record);
                }
                file.flush();
            } catch (IOException | RuntimeException e) {
                file = null;
            }
        }
    }

    /** Where the session log lives, for showing in the viewer. */
    public static Path sessionFile(Path configDir) {
        return configDir == null ? null : configDir.resolve(FILE_NAME);
    }

    /** Everything captured, oldest first. */
    public static String snapshot() {
        synchronized (LOCK) {
            return String.join(System.lineSeparator(), RECORDS);
        }
    }

    /** Empties the buffer. Does not delete the session file. */
    public static void clear() {
        synchronized (LOCK) {
            RECORDS.clear();
        }
    }

    // --- internals, package-private so they can be tested without a logger ------------------

    static void append(String record) {
        synchronized (LOCK) {
            RECORDS.addLast(record);
            while (RECORDS.size() > MAX_RECORDS) {
                RECORDS.removeFirst();
            }
            if (file != null) {
                file.println(record);
                file.flush();
            }
        }
    }

    static String format(LogRecord record) {
        String base = line(
                record.getInstant(),
                record.getLevel().getName(),
                shortName(record.getLoggerName()),
                formatMessage(record));
        return record.getThrown() == null ? base : base + System.lineSeparator() + stackTrace(record.getThrown());
    }

    private static String line(Instant when, String level, String logger, String message) {
        return TIME.format(when) + "  " + level + "  " + logger + ": " + message;
    }

    /** The last segment of a dotted logger name — the package prefix is noise in a viewer. */
    static String shortName(String loggerName) {
        if (loggerName == null || loggerName.isBlank()) {
            return "?";
        }
        int dot = loggerName.lastIndexOf('.');
        return dot >= 0 && dot < loggerName.length() - 1 ? loggerName.substring(dot + 1) : loggerName;
    }

    private static String formatMessage(LogRecord record) {
        String message = record.getMessage();
        if (message == null) {
            return "";
        }
        Object[] params = record.getParameters();
        if (params != null && params.length > 0 && message.contains("{0")) {
            try {
                return MessageFormat.format(message, params);
            } catch (RuntimeException ignored) {
                // a malformed pattern is not worth losing the message over
            }
        }
        return message;
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString().stripTrailing();
    }
}
