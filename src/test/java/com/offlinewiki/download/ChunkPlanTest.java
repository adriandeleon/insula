package com.offlinewiki.download;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPlanTest {

    private static final long PIECE = 4L * 1024 * 1024;

    @Test
    void chunksTileTheFileWithNoGapsOrOverlap() {
        ChunkPlan plan = ChunkPlan.forFile(100_000_000L, PIECE, 24);
        List<ChunkPlan.Chunk> chunks = plan.chunks();

        assertEquals(0, chunks.getFirst().start());
        assertEquals(100_000_000L, chunks.getLast().end());
        for (int i = 1; i < chunks.size(); i++) {
            assertEquals(chunks.get(i - 1).end(), chunks.get(i).start(), "gap/overlap before chunk " + i);
        }
        assertEquals(
                100_000_000L, chunks.stream().mapToLong(ChunkPlan.Chunk::length).sum());
    }

    @Test
    void chunksAlignToPieceBoundariesSoEachIsVerifiable() {
        ChunkPlan plan = ChunkPlan.forFile(100_000_000L, PIECE, 24);
        for (ChunkPlan.Chunk c : plan.chunks()) {
            assertEquals(0, c.start() % PIECE, "chunk must start on a piece boundary: " + c);
            assertEquals(c.start() / PIECE, c.firstPiece());
        }
    }

    @Test
    void aFileSmallerThanOneChunkIsASingleChunk() {
        ChunkPlan plan = ChunkPlan.forFile(4_621_915L, PIECE, 2);
        assertEquals(1, plan.size());
        assertEquals(4_621_915L, plan.chunks().getFirst().length());
    }

    @Test
    void withoutPieceHashesChunksAreUnverifiableButStillTile() {
        ChunkPlan plan = ChunkPlan.forFile(50_000_000L, 0, 0);
        assertTrue(plan.size() > 1);
        assertTrue(plan.chunks().stream().allMatch(c -> c.firstPiece() == -1));
        assertEquals(
                50_000_000L,
                plan.chunks().stream().mapToLong(ChunkPlan.Chunk::length).sum());
    }

    @Test
    void rangeHeaderUsesInclusiveBounds() {
        ChunkPlan plan = ChunkPlan.forFile(100, 0, 0);
        assertEquals("bytes=0-99", plan.chunks().getFirst().rangeHeader());
    }

    @Test
    void tracksCompletionAndRemainingWork() {
        ChunkPlan plan = ChunkPlan.forFile(100_000_000L, PIECE, 24);
        assertEquals(0, plan.completedBytes());
        assertEquals(plan.size(), plan.pending().size());

        plan.markDone(0);
        assertTrue(plan.isDone(0));
        assertEquals(plan.chunks().getFirst().length(), plan.completedBytes());
        assertEquals(plan.size() - 1, plan.pending().size());
        assertFalse(plan.isComplete());

        for (int i = 0; i < plan.size(); i++) {
            plan.markDone(i);
        }
        assertTrue(plan.isComplete());
        assertEquals(100_000_000L, plan.completedBytes());
    }

    @Test
    void bitmapRoundTripsForResume() {
        ChunkPlan saved = ChunkPlan.forFile(100_000_000L, PIECE, 24);
        saved.markDone(0);
        saved.markDone(2);

        ChunkPlan resumed = ChunkPlan.forFile(100_000_000L, PIECE, 24);
        assertTrue(resumed.applyBitmap(saved.toBitmap()));
        assertTrue(resumed.isDone(0));
        assertFalse(resumed.isDone(1));
        assertTrue(resumed.isDone(2));
        assertEquals(saved.completedBytes(), resumed.completedBytes());
    }

    @Test
    void rejectsABitmapThatDescribesADifferentFile() {
        // Accepting a mismatched bitmap would mark never-downloaded ranges as present,
        // producing a corrupt archive that only fails at the final SHA-256.
        ChunkPlan plan = ChunkPlan.forFile(100_000_000L, PIECE, 24);
        assertFalse(plan.applyBitmap("11"), "too short");
        assertFalse(plan.applyBitmap("1".repeat(plan.size() + 1)), "too long");
        assertFalse(plan.applyBitmap("x".repeat(plan.size())), "not a bitmap");
        assertFalse(plan.applyBitmap(null));
        assertEquals(0, plan.completedBytes(), "a rejected bitmap must leave the plan untouched");
    }

    @Test
    void rejectsANonPositiveFileSize() {
        assertThrows(IllegalArgumentException.class, () -> ChunkPlan.forFile(0, PIECE, 1));
        assertThrows(IllegalArgumentException.class, () -> ChunkPlan.forFile(-5, PIECE, 1));
    }
}
