package com.insula.app;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The status history: what it keeps, what it refuses, and in what order. */
class MessageLogTest {

    @Test
    void newestFirstIsHowItReads() {
        MessageLog log = new MessageLog();
        log.add("first", 1);
        log.add("second", 2);
        assertEquals(
                List.of("second", "first"),
                log.entries().stream().map(MessageLog.Entry::text).toList());
    }

    @Test
    void blankIsAClearedLineRatherThanAMessage() {
        MessageLog log = new MessageLog();
        log.add("", 1);
        log.add("   ", 2);
        log.add(null, 3);
        assertTrue(log.isEmpty());
    }

    @Test
    void theSameMessageTwiceRunningIsOneThingHappening() {
        // Several code paths set the same line; two identical rows read as two events.
        MessageLog log = new MessageLog();
        log.add("Saved", 1);
        log.add("Saved", 2);
        log.add("Opened", 3);
        log.add("Saved", 4);
        assertEquals(3, log.size(), "only consecutive repeats collapse");
    }

    @Test
    void aLongSessionCannotGrowWithoutLimit() {
        MessageLog log = new MessageLog();
        for (int i = 0; i < MessageLog.MAX_ENTRIES + 50; i++) {
            log.add("m" + i, i);
        }
        assertEquals(MessageLog.MAX_ENTRIES, log.size());
        assertEquals(
                "m" + (MessageLog.MAX_ENTRIES + 49), log.entries().getFirst().text());
    }

    @Test
    void theTimeIsKeptBecauseItIsWhatMakesTheListReadable() {
        MessageLog log = new MessageLog();
        log.add("done", 1_700_000_000_000L);
        assertEquals(1_700_000_000_000L, log.entries().getFirst().epochMillis());
    }
}
