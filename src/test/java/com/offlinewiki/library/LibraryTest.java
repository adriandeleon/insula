package com.offlinewiki.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryTest {

    private static Path archive(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, "zim");
        return file;
    }

    private static LibraryEntry entry(Path file, boolean verified) {
        return new LibraryEntry(file, "Title of " + file.getFileName(), 3, "abc", verified, System.currentTimeMillis());
    }

    @Test
    void roundTripsThroughDisk(@TempDir Path dir) throws IOException {
        Path index = dir.resolve("library.properties");
        Path zim = archive(dir, "a.zim");

        Library library = Library.load(index);
        library.put(entry(zim, true));
        library.save();

        Library reloaded = Library.load(index);
        assertEquals(1, reloaded.size());
        LibraryEntry restored = reloaded.entries().getFirst();
        assertEquals(zim.toAbsolutePath(), restored.file().toAbsolutePath());
        assertTrue(restored.verified());
        assertEquals("abc", restored.sha256());
    }

    @Test
    void onlyVerifiedEntriesAreOfferedForAutomaticOpening(@TempDir Path dir) throws IOException {
        Library library = Library.load(dir.resolve("library.properties"));
        Path good = archive(dir, "good.zim");
        Path bad = archive(dir, "bad.zim");
        library.put(entry(good, true));
        library.put(entry(bad, false));

        assertEquals(2, library.entries().size(), "both stay listed so the user can retry the bad one");
        assertEquals(1, library.verifiedEntries().size());
        assertEquals(
                good.toAbsolutePath(),
                library.verifiedEntries().getFirst().file().toAbsolutePath());
        assertTrue(library.isVerified(good));
        assertFalse(library.isVerified(bad));
        assertFalse(library.isVerified(dir.resolve("never-seen.zim")));
    }

    @Test
    void dropsEntriesWhoseFileWasDeletedOutsideTheApp(@TempDir Path dir) throws IOException {
        Path index = dir.resolve("library.properties");
        Path zim = archive(dir, "gone.zim");
        Library library = Library.load(index);
        library.put(entry(zim, true));
        library.save();

        Files.delete(zim);

        assertEquals(0, Library.load(index).size(), "the library must not offer a file that is not there");
    }

    @Test
    void puttingTheSameFileTwiceReplacesRatherThanDuplicates(@TempDir Path dir) throws IOException {
        Library library = Library.load(dir.resolve("library.properties"));
        Path zim = archive(dir, "a.zim");
        library.put(entry(zim, false));
        library.put(entry(zim, true));

        assertEquals(1, library.size());
        assertTrue(library.entries().getFirst().verified());
    }

    @Test
    void removeDropsTheEntry(@TempDir Path dir) throws IOException {
        Library library = Library.load(dir.resolve("library.properties"));
        Path zim = archive(dir, "a.zim");
        library.put(entry(zim, true));
        library.remove(zim);
        assertEquals(0, library.size());
        assertTrue(library.find(zim).isEmpty());
    }

    @Test
    void missingOrGarbledIndexStartsEmptyInsteadOfFailing(@TempDir Path dir) throws IOException {
        assertEquals(0, Library.load(dir.resolve("absent.properties")).size());

        Path garbled = dir.resolve("garbled.properties");
        Files.writeString(garbled, "1.path=/nonexistent/x.zim\n2.nonsense\n");
        assertEquals(0, Library.load(garbled).size());
    }
}
