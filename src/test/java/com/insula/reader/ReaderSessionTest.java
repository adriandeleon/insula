package com.insula.reader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Remembering the open tabs, including what happens when an archive disappears between sittings. */
class ReaderSessionTest {

    private static ArticleRef ref(String archive, String path) {
        return new ArticleRef(Path.of("/zim", archive), path, path, archive);
    }

    @Test
    void tabsAndTheActiveOneSurviveARoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("session.txt");
        ReaderSession session = ReaderSession.load(file);
        session.set(List.of(ref("a.zim", "C/One"), ref("a.zim", "C/Two"), ref("b.zim", "C/Three")), 1);
        session.save();

        ReaderSession.Restored restored = ReaderSession.load(file).restore(p -> true);
        assertEquals(3, restored.open().size());
        assertEquals(1, restored.activeIndex());
        assertEquals("C/Two", restored.open().get(1).articlePath());
        assertEquals(0, restored.dropped());
    }

    @Test
    void aTabWhoseArchiveIsGoneIsDroppedRatherThanRestoredBroken(@TempDir Path dir) {
        Path file = dir.resolve("session.txt");
        ReaderSession session = ReaderSession.load(file);
        session.set(List.of(ref("gone.zim", "C/One"), ref("here.zim", "C/Two")), 1);
        session.save();

        ReaderSession.Restored restored =
                ReaderSession.load(file).restore(p -> p.getFileName().toString().equals("here.zim"));
        assertEquals(1, restored.open().size());
        assertEquals("C/Two", restored.open().getFirst().articlePath());
        assertEquals(1, restored.dropped(), "the caller can say so rather than silently losing a tab");
    }

    @Test
    void theActiveIndexIsRederivedFromTheSurvivors(@TempDir Path dir) {
        // Trusting the stored index would select the wrong article once a tab ahead of it is gone.
        Path file = dir.resolve("session.txt");
        ReaderSession session = ReaderSession.load(file);
        session.set(List.of(ref("gone.zim", "C/One"), ref("gone.zim", "C/Two"), ref("here.zim", "C/Three")), 2);
        session.save();

        ReaderSession.Restored restored =
                ReaderSession.load(file).restore(p -> p.getFileName().toString().equals("here.zim"));
        assertEquals(1, restored.open().size());
        assertEquals(0, restored.activeIndex(), "the survivor is index 0 now, not 2");
        assertEquals("C/Three", restored.open().getFirst().articlePath());
    }

    @Test
    void anActiveTabThatVanishesLeavesTheFirstSurvivorSelected(@TempDir Path dir) {
        Path file = dir.resolve("session.txt");
        ReaderSession session = ReaderSession.load(file);
        session.set(List.of(ref("here.zim", "C/One"), ref("gone.zim", "C/Two")), 1);
        session.save();

        ReaderSession.Restored restored =
                ReaderSession.load(file).restore(p -> p.getFileName().toString().equals("here.zim"));
        assertEquals(0, restored.activeIndex());
    }

    @Test
    void anEmptySessionRestoresNothingAndSelectsNothing(@TempDir Path dir) {
        ReaderSession.Restored restored =
                ReaderSession.load(dir.resolve("missing.txt")).restore(p -> true);
        assertTrue(restored.open().isEmpty());
        assertEquals(-1, restored.activeIndex());
    }

    @Test
    void aDamagedSessionFileIsIgnoredRatherThanFailingTheLaunch(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("session.txt");
        Files.writeString(file, "not a ref\n\n||||\n", StandardCharsets.UTF_8);
        assertTrue(ReaderSession.load(file).restore(p -> true).open().isEmpty());
    }

    @Test
    void theTabCountIsCappedSoARunawayFileCannotSlowTheLaunch(@TempDir Path dir) {
        Path file = dir.resolve("session.txt");
        ReaderSession session = ReaderSession.load(file);
        session.set(
                java.util.stream.IntStream.range(0, ReaderSession.MAX_TABS + 20)
                        .mapToObj(i -> ref("a.zim", "C/" + i))
                        .toList(),
                0);
        session.save();
        assertEquals(
                ReaderSession.MAX_TABS,
                ReaderSession.load(file).restore(p -> true).open().size());
    }

    @Test
    void anArticlePathCarryingTheSeparatorRoundTrips(@TempDir Path dir) {
        Path file = dir.resolve("session.txt");
        ReaderSession session = ReaderSession.load(file);
        session.set(List.of(ref("a.zim", "C/Weird|Path=1")), 0);
        session.save();
        assertEquals(
                "C/Weird|Path=1",
                ReaderSession.load(file).restore(p -> true).open().getFirst().articlePath());
    }
}
