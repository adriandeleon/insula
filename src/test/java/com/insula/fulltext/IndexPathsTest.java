package com.insula.fulltext;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where an index lives, and what that survives. */
class IndexPathsTest {

    private static final Path CONFIG = Path.of("/home/x/.insula");

    private static byte[] uuid(int seed) {
        byte[] b = new byte[16];
        java.util.Arrays.fill(b, (byte) seed);
        return b;
    }

    @Test
    void theKeyIsTheArchivesOwnIdentityNotItsFileName() {
        // Renaming an archive, or moving it to another disk, keeps its index.
        assertEquals(IndexPaths.forArchive(CONFIG, uuid(1)), IndexPaths.forArchive(CONFIG, uuid(1)));
    }

    @Test
    void aNewEditionGetsItsOwnIndex() {
        // The case that matters: a 2026 archive must not inherit an index built from 2025 text.
        assertNotEquals(IndexPaths.forArchive(CONFIG, uuid(1)), IndexPaths.forArchive(CONFIG, uuid(2)));
    }

    @Test
    void theNameIsSafeOnAnyFilesystem() {
        String key = IndexPaths.key(uuid(0xAB));
        assertTrue(key.matches("[0-9a-f]+"), key);
    }

    @Test
    void anArchiveWithNoUuidStillGetsAStableName() {
        // Some older files carry none. Sharing one name is a real collision; refusing to index
        // them at all is worse.
        assertEquals("no-uuid", IndexPaths.key(null));
        assertEquals("no-uuid", IndexPaths.key(new byte[0]));
    }

    @Test
    void everyIndexSitsUnderOneFolderThatCanBeShownAndCleared() {
        assertTrue(IndexPaths.forArchive(CONFIG, uuid(1)).startsWith(IndexPaths.root(CONFIG)));
    }
}
