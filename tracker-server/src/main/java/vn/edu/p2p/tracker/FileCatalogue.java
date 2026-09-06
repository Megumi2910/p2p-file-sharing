package vn.edu.p2p.tracker;

import vn.edu.p2p.common.model.FileRecord;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.model.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FileCatalogue {
    private static final class Entry {
        private final FileRecord file;
        private final Set<String> providerPeerIds = ConcurrentHashMap.newKeySet();

        private Entry(FileRecord file) {
            this.file = file;
        }
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> peerFiles = new ConcurrentHashMap<>();

    public void publishFiles(String peerId, List<FileRecord> files) {
        if (peerId == null || files == null) {
            return;
        }

        Set<String> newIds = new HashSet<>(files.size());
        for (FileRecord f : files) {
            newIds.add(f.fileId());
        }

        Set<String> currentTracked = ConcurrentHashMap.newKeySet();
        currentTracked.addAll(newIds);
        Set<String> oldIds = peerFiles.put(peerId, currentTracked);

        // Prune associations no longer present in the updated file list
        if (oldIds != null) {
            for (String oldId : oldIds) {
                if (!newIds.contains(oldId)) {
                    removeProviderFromFile(oldId, peerId);
                }
            }
        }

        for (FileRecord file : files) {
            entries.compute(file.fileId(), (id, existing) -> {
                Entry entry = existing != null ? existing : new Entry(file);
                entry.providerPeerIds.add(peerId);
                return entry;
            });
        }
    }

    public void removePeer(String peerId) {
        if (peerId == null) {
            return;
        }
        Set<String> files = peerFiles.remove(peerId);
        if (files != null) {
            for (String fileId : files) {
                removeProviderFromFile(fileId, peerId);
            }
        }
    }

    private void removeProviderFromFile(String fileId, String peerId) {
        entries.computeIfPresent(fileId, (id, entry) -> {
            entry.providerPeerIds.remove(peerId);
            return entry.providerPeerIds.isEmpty() ? null : entry;
        });
    }

    public List<SearchResult> search(String query, PeerRegistry registry) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<SearchResult> results = new ArrayList<>();

        for (Entry entry : entries.values()) {
            FileRecord file = entry.file;
            boolean match = normalizedQuery.isEmpty()
                    || file.fileName().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                    || file.fileId().contains(normalizedQuery);

            if (match) {
                List<PeerInfo> liveProviders = new ArrayList<>();
                for (PeerInfo registered : registry.listExcept(null)) {
                    if (entry.providerPeerIds.contains(registered.peerId())) {
                        liveProviders.add(registered);
                    }
                }
                if (!liveProviders.isEmpty()) {
                    liveProviders.sort(Comparator.comparing(PeerInfo::displayName, String.CASE_INSENSITIVE_ORDER));
                    results.add(new SearchResult(file, liveProviders));
                }
            }
        }

        results.sort(Comparator.comparing(r -> r.file().fileName(), String.CASE_INSENSITIVE_ORDER));
        return results;
    }

    public int size() {
        return entries.size();
    }
}
