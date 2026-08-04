package com.insula.fulltext;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.insula.zim.ZimArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The service that decides what is indexed, indexes it, and searches it. */
class FullTextServiceTest {

    private static final Path ZIM = Path.of("src/test/resources/zim/nons-wikibooks.zim");

    /** Builds an index and waits for it, so a test reads a settled state rather than a racing one. */
    private static boolean buildAndWait(FullTextService service, Path file) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean();
        service.buildIndex(file, p -> {}, result -> {
            ok.set(result);
            done.countDown();
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "indexing did not finish");
        return ok.get();
    }

    private static List<FullTextService.Hit> searchAndWait(FullTextService service, String query) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<FullTextService.Hit>> hits = new AtomicReference<>(List.of());
        service.search(query, 20, found -> {
            hits.set(found);
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "search did not finish");
        return hits.get();
    }

    @Test
    void anArchiveIsNotSearchableUntilItIsIndexed(@TempDir Path config) throws Exception {
        try (ZimArchive archive = ZimArchive.open(ZIM);
                FullTextService service = new FullTextService(config)) {
            service.register(ZIM, "Wikibooks", archive);
            assertEquals(FullTextService.State.ABSENT, service.state(ZIM));
            assertEquals(List.of(ZIM.toAbsolutePath()), service.unindexed());
            assertTrue(searchAndWait(service, "wikibooks").isEmpty(), "no index, no results");

            assertTrue(buildAndWait(service, ZIM));
            assertEquals(FullTextService.State.READY, service.state(ZIM));
            assertTrue(service.unindexed().isEmpty());
            assertFalse(searchAndWait(service, "wikibooks").isEmpty());
        }
    }

    @Test
    void aHitCarriesEnoughToOpenIt(@TempDir Path config) throws Exception {
        // An archive and a path: without both, a result across several archives is unopenable.
        try (ZimArchive archive = ZimArchive.open(ZIM);
                FullTextService service = new FullTextService(config)) {
            service.register(ZIM, "Wikibooks", archive);
            buildAndWait(service, ZIM);
            FullTextService.Hit hit = searchAndWait(service, "wikibooks").getFirst();
            assertEquals(ZIM.toAbsolutePath(), hit.archiveFile());
            assertEquals("Wikibooks", hit.archiveTitle());
            assertTrue(archive.entryByUrl(hit.path()).isPresent(), "the path resolves in the archive");
        }
    }

    @Test
    void anIndexSurvivesTheServiceThatBuiltIt(@TempDir Path config) throws Exception {
        // Keyed on the archive's UUID, so a later session finds the work the last one did.
        try (ZimArchive archive = ZimArchive.open(ZIM);
                FullTextService first = new FullTextService(config)) {
            first.register(ZIM, "Wikibooks", archive);
            buildAndWait(first, ZIM);
        }
        try (ZimArchive archive = ZimArchive.open(ZIM);
                FullTextService second = new FullTextService(config)) {
            second.register(ZIM, "Wikibooks", archive);
            assertEquals(FullTextService.State.READY, second.state(ZIM), "no rebuild needed");
            assertFalse(searchAndWait(second, "wikibooks").isEmpty());
        }
    }

    @Test
    void anIndexCanBeDeletedAndRebuilt(@TempDir Path config) throws Exception {
        try (ZimArchive archive = ZimArchive.open(ZIM);
                FullTextService service = new FullTextService(config)) {
            service.register(ZIM, "Wikibooks", archive);
            buildAndWait(service, ZIM);
            assertTrue(service.indexBytes(ZIM) > 0);
            assertTrue(service.totalIndexBytes() > 0);

            assertTrue(service.deleteIndex(ZIM));
            assertEquals(FullTextService.State.ABSENT, service.state(ZIM));
            assertTrue(searchAndWait(service, "wikibooks").isEmpty());

            assertTrue(buildAndWait(service, ZIM), "and it can be built again");
            assertFalse(searchAndWait(service, "wikibooks").isEmpty());
        }
    }

    @Test
    void progressIsReportedAndFinishes(@TempDir Path config) throws Exception {
        try (ZimArchive archive = ZimArchive.open(ZIM);
                FullTextService service = new FullTextService(config)) {
            service.register(ZIM, "Wikibooks", archive);
            AtomicReference<FullTextService.Progress> last = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            service.buildIndex(ZIM, last::set, ok -> done.countDown());
            assertTrue(done.await(60, TimeUnit.SECONDS));
            assertTrue(last.get().total() > 0);
            assertEquals(last.get().total(), last.get().done(), "the last article is reported");
            assertEquals("Wikibooks", last.get().archiveTitle(), "so a progress line can name it");
            assertTrue(last.get().fraction() > 0.99);
        }
    }

    @Test
    void anUnregisteredArchiveIsNotSearchedOrIndexed(@TempDir Path config) throws Exception {
        try (FullTextService service = new FullTextService(config)) {
            assertEquals(FullTextService.State.ABSENT, service.state(ZIM));
            assertFalse(buildAndWait(service, ZIM), "nothing to index");
            assertTrue(searchAndWait(service, "anything").isEmpty());
        }
    }
}
