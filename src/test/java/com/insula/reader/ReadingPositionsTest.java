package com.insula.reader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingPositionsTest {

    @Test
    void roundTripsThroughDisk(@TempDir Path dir) {
        Path file = dir.resolve("reading.properties");
        ReadingPositions store = ReadingPositions.load(file);
        String key = ReadingPositions.key(Path.of("/archives/wiki.zim"), "C/Some Article");
        store.remember(key, 0.42);
        store.save();

        assertEquals(0.42, ReadingPositions.load(file).positionOf(key), 0.0001);
    }

    @Test
    void keysDistinguishArchivesAndArticles() {
        Path a = Path.of("/archives/a.zim");
        Path b = Path.of("/archives/b.zim");
        assertTrue(ReadingPositions.key(a, "C/X").equals(ReadingPositions.key(a, "C/X")));
        assertTrue(!ReadingPositions.key(a, "C/X").equals(ReadingPositions.key(b, "C/X")));
        assertTrue(!ReadingPositions.key(a, "C/X").equals(ReadingPositions.key(a, "C/Y")));
    }

    @Test
    void anUnreadArticleStartsAtTheTop() {
        ReadingPositions store = ReadingPositions.load(Path.of("/nonexistent/reading.properties"));
        assertEquals(0.0, store.positionOf("never seen"), 0.0001);
    }

    @Test
    void aPositionAtTheTopIsForgottenRatherThanStored() {
        // Storing "0.001" for every article merely glanced at would fill the file with noise.
        ReadingPositions store = ReadingPositions.load(Path.of("/nonexistent/x.properties"));
        store.remember("k", 0.5);
        store.remember("k", 0.001);
        assertEquals(0, store.size());
    }

    @Test
    void evictsTheLeastRecentlyUsedBeyondTheCap() {
        ReadingPositions store = ReadingPositions.load(Path.of("/nonexistent/x.properties"));
        for (int i = 0; i < ReadingPositions.MAX_ENTRIES + 50; i++) {
            store.remember("key" + i, 0.5);
        }
        assertEquals(ReadingPositions.MAX_ENTRIES, store.size());
        assertEquals(0.0, store.positionOf("key0"), 0.0001, "the oldest should have been dropped");
        assertEquals(0.5, store.positionOf("key" + (ReadingPositions.MAX_ENTRIES + 49)), 0.0001);
    }

    @Test
    void recentListsMostRecentlyReadFirst() {
        ReadingPositions store = ReadingPositions.load(Path.of("/nonexistent/x.properties"));
        store.remember("a", 0.3);
        store.remember("b", 0.3);
        store.remember("c", 0.3);
        assertEquals("c", store.recent(3).getFirst());
        // Reading "a" again promotes it.
        store.positionOf("a");
        assertEquals("a", store.recent(3).getFirst());
    }

    @Test
    void aGarbledFileDegradesToDefaultsPerEntry(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("reading.properties");
        Files.writeString(file, "good=0.5\nbad=not-a-number\nout-of-range=7.5\n");

        ReadingPositions store = ReadingPositions.load(file);
        assertEquals(0.5, store.positionOf("good"), 0.0001);
        assertEquals(0.0, store.positionOf("bad"), 0.0001);
        assertEquals(0.0, store.positionOf("out-of-range"), 0.0001);
    }
}
