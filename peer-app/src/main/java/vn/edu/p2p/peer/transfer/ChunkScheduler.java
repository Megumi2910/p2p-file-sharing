package vn.edu.p2p.peer.transfer;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

public final class ChunkScheduler {
    private final long totalChunks;
    private final BitSet missingChunks;
    private final BitSet inFlightChunks;
    private final Set<String> failedPeers = new HashSet<>();

    public ChunkScheduler(long totalChunks, TransferMeta meta) {
        this.totalChunks = totalChunks;
        this.missingChunks = new BitSet((int) Math.min(totalChunks, Integer.MAX_VALUE));
        this.inFlightChunks = new BitSet((int) Math.min(totalChunks, Integer.MAX_VALUE));

        for (int i = 0; i < totalChunks; i++) {
            if (meta == null || !meta.isChunkReceived(i)) {
                missingChunks.set(i);
            }
        }
    }

    public synchronized Long pollNextChunk(String peerId) {
        if (peerId != null && failedPeers.contains(peerId)) {
            return null;
        }

        int nextIndex = 0;
        while (nextIndex < totalChunks) {
            int candidate = missingChunks.nextSetBit(nextIndex);
            if (candidate < 0 || candidate >= totalChunks) {
                break;
            }
            if (!inFlightChunks.get(candidate)) {
                inFlightChunks.set(candidate);
                return (long) candidate;
            }
            nextIndex = candidate + 1;
        }
        return null;
    }

    public synchronized void markSuccess(long chunkIndex) {
        int idx = (int) chunkIndex;
        missingChunks.clear(idx);
        inFlightChunks.clear(idx);
    }

    public synchronized void markFailure(long chunkIndex, String peerId) {
        int idx = (int) chunkIndex;
        inFlightChunks.clear(idx);
        if (peerId != null) {
            failedPeers.add(peerId);
        }
    }

    public synchronized boolean isDone() {
        return missingChunks.isEmpty();
    }

    public synchronized int remainingCount() {
        return missingChunks.cardinality();
    }

    public synchronized int inFlightCount() {
        return inFlightChunks.cardinality();
    }

    public synchronized boolean isPeerFailed(String peerId) {
        return peerId != null && failedPeers.contains(peerId);
    }
}
