package vn.edu.p2p.peer.transfer;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ChunkScheduler {
    private final long totalChunks;
    private final BitSet missingChunks;
    private final BitSet inFlightChunks;
    private final Set<String> failedPeers = new HashSet<>();
    private final Map<String, Long> activeAssignments = new HashMap<>();

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
                if (peerId != null) {
                    activeAssignments.put(peerId, (long) candidate);
                }
                return (long) candidate;
            }
            nextIndex = candidate + 1;
        }
        return null;
    }

    public synchronized void markSuccess(long chunkIndex) {
        int idx = (int) chunkIndex;
        if (idx >= 0 && idx < totalChunks) {
            missingChunks.clear(idx);
            inFlightChunks.clear(idx);
        }
        activeAssignments.values().remove(chunkIndex);
    }

    public synchronized void markFailure(long chunkIndex, String peerId) {
        long targetChunk = chunkIndex;
        if (targetChunk < 0 && peerId != null && activeAssignments.containsKey(peerId)) {
            targetChunk = activeAssignments.remove(peerId);
        }
        if (targetChunk >= 0 && targetChunk < totalChunks) {
            inFlightChunks.clear((int) targetChunk);
        }
        if (peerId != null) {
            failedPeers.add(peerId);
            activeAssignments.remove(peerId);
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
