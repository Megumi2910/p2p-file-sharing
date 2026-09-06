package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSchedulerTest {

    @Test
    void testAllocationWithoutDuplication() {
        ChunkScheduler scheduler = new ChunkScheduler(3, null);
        assertEquals(3, scheduler.remainingCount());

        Long c0 = scheduler.pollNextChunk("peer1");
        Long c1 = scheduler.pollNextChunk("peer2");
        Long c2 = scheduler.pollNextChunk("peer1");
        Long c3 = scheduler.pollNextChunk("peer2");

        assertEquals(0L, c0);
        assertEquals(1L, c1);
        assertEquals(2L, c2);
        assertNull(c3, "All chunks are in flight, should return null");

        scheduler.markSuccess(0L);
        scheduler.markSuccess(1L);
        scheduler.markSuccess(2L);
        assertTrue(scheduler.isDone());
    }

    @Test
    void testFailoverReassignsChunkToDifferentPeer() {
        ChunkScheduler scheduler = new ChunkScheduler(2, null);

        Long c0 = scheduler.pollNextChunk("peer1");
        assertEquals(0L, c0);

        // peer1 fails on chunk 0
        scheduler.markFailure(0L, "peer1");
        assertTrue(scheduler.isPeerFailed("peer1"));

        // peer1 cannot poll anymore
        assertNull(scheduler.pollNextChunk("peer1"));

        // peer2 polls and receives the failed chunk 0!
        Long c0Retry = scheduler.pollNextChunk("peer2");
        assertEquals(0L, c0Retry);

        scheduler.markSuccess(0L);
        Long c1 = scheduler.pollNextChunk("peer2");
        assertEquals(1L, c1);
        scheduler.markSuccess(1L);
        assertTrue(scheduler.isDone());
    }

    @Test
    void testPreExistingChunksExcludedFromScheduler() {
        String sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        TransferMeta meta = new TransferMeta(sha, 3000, 1000, 3);
        meta.markChunkReceived(0); // chunk 0 already on disk

        ChunkScheduler scheduler = new ChunkScheduler(3, meta);
        assertEquals(2, scheduler.remainingCount());

        // Should start from chunk 1
        Long first = scheduler.pollNextChunk("peer1");
        assertEquals(1L, first);
    }
}
