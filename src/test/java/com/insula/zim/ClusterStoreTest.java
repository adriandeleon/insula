package com.insula.zim;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cluster cache is bounded by decompressed <b>bytes</b>, not entry count. Cluster sizes vary
 * by orders of magnitude between archives, so a count-based bound bounds the wrong dimension.
 */
class ClusterStoreTest {

    private static Path fixture(String name) {
        try {
            return Path.of(ClusterStoreTest.class.getResource("/zim/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads every compressed cluster of an archive through a store with the given budget. */
    private static long readAllClusters(String archive, long budget, int[] clustersTouched) throws IOException {
        try (LittleEndianFile in = new LittleEndianFile(fixture(archive))) {
            ZimHeader header = ZimHeader.parse(in);
            ClusterStore store = new ClusterStore(in, header, budget);
            int touched = 0;
            for (long i = 0; i < header.entryCount(); i++) {
                long offset = in.u64(header.pathPtrPos() + 8 * i);
                Dirent d = Dirent.parse(in, i, offset);
                if (!d.isRedirect() && d.hasContent()) {
                    store.blob(d.clusterNumber(), d.blobNumber());
                    touched++;
                }
                assertTrue(
                        store.cachedBytes() <= budget,
                        "cache exceeded its budget: " + store.cachedBytes() + " > " + budget);
            }
            clustersTouched[0] = touched;
            return store.cachedBytes();
        }
    }

    @Test
    void neverRetainsMoreThanTheBudget() throws IOException {
        // 8 KB forces eviction on fixtures whose clusters are tens of KB.
        long budget = 8 * 1024;
        int[] touched = {0};
        long finalBytes = readAllClusters("withns-wikibooks.zim", budget, touched);
        assertTrue(touched[0] > 0, "the fixture should have content entries");
        assertTrue(finalBytes <= budget, "final retention " + finalBytes + " > " + budget);
    }

    @Test
    void contentIsIdenticalWhetherOrNotItWasEvicted() throws IOException {
        // Eviction must only cost time, never correctness.
        try (LittleEndianFile generous = new LittleEndianFile(fixture("nons-wikibooks.zim"));
                LittleEndianFile tiny = new LittleEndianFile(fixture("nons-wikibooks.zim"))) {
            ZimHeader header = ZimHeader.parse(generous);
            ClusterStore roomy = new ClusterStore(generous, header, 64L * 1024 * 1024);
            ClusterStore cramped = new ClusterStore(tiny, ZimHeader.parse(tiny), 4 * 1024);

            int compared = 0;
            for (long i = 0; i < header.entryCount(); i++) {
                Dirent d = Dirent.parse(generous, i, generous.u64(header.pathPtrPos() + 8 * i));
                if (!d.isRedirect() && d.hasContent()) {
                    assertArrayEquals(
                            roomy.blob(d.clusterNumber(), d.blobNumber()),
                            cramped.blob(d.clusterNumber(), d.blobNumber()),
                            "blob differs under eviction at entry " + i);
                    compared++;
                }
            }
            assertTrue(compared > 0);
        }
    }

    @Test
    void aClusterLargerThanTheWholeBudgetIsServedButNotCached() throws IOException {
        // Admitting it would evict everything else to hold one item, which is worse than a miss.
        try (LittleEndianFile in = new LittleEndianFile(fixture("withns-wikibooks.zim"))) {
            ZimHeader header = ZimHeader.parse(in);
            ClusterStore store = new ClusterStore(in, header, 1); // budget smaller than any cluster

            boolean served = false;
            for (long i = 0; i < header.entryCount() && !served; i++) {
                Dirent d = Dirent.parse(in, i, in.u64(header.pathPtrPos() + 8 * i));
                if (!d.isRedirect() && d.hasContent()) {
                    assertTrue(store.blob(d.clusterNumber(), d.blobNumber()).length >= 0);
                    served = true;
                }
            }
            assertTrue(served, "the fixture should have content entries");
            assertEquals(0, store.cachedClusterCount(), "nothing should be cached under a 1-byte budget");
            assertEquals(0, store.cachedBytes());
        }
    }

    @Test
    void theDefaultBudgetIsAMemoryFigureNotAnEntryCount() {
        assertEquals(64L * 1024 * 1024, ClusterStore.MAX_CACHE_BYTES);
    }
}
