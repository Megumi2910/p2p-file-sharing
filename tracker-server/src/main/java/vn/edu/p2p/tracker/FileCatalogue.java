package vn.edu.p2p.tracker;

import vn.edu.p2p.common.model.FileRecord;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.model.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FileCatalogue {
    private final ConcurrentMap<String, List<FileRecord>> peerSnapshots = new ConcurrentHashMap<>();

    public void publishFiles(String peerId, List<FileRecord> files) {
        if (peerId == null || files == null) {
            return;
        }
        peerSnapshots.put(peerId, List.copyOf(files));
    }

    public void removePeer(String peerId) {
        if (peerId == null) {
            return;
        }
        peerSnapshots.remove(peerId);
    }

    public List<SearchResult> search(String query, PeerRegistry registry) {
        if (registry == null) {
            return List.of();
        }
        List<PeerInfo> livePeers = registry.listExcept(null);
        if (livePeers.isEmpty()) {
            return List.of();
        }
        Map<String, PeerInfo> registeredPeers = new HashMap<>(livePeers.size());
        for (PeerInfo p : livePeers) {
            registeredPeers.put(p.peerId(), p);
        }

        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        record PeerFile(String peerId, FileRecord record) {}
        Map<String, List<PeerFile>> byHash = new LinkedHashMap<>();
        Map<String, Set<String>> providersByHash = new HashMap<>();

        for (Map.Entry<String, List<FileRecord>> entry : peerSnapshots.entrySet()) {
            String peerId = entry.getKey();
            if (!registeredPeers.containsKey(peerId)) {
                continue;
            }
            for (FileRecord file : entry.getValue()) {
                byHash.computeIfAbsent(file.fileId(), k -> new ArrayList<>()).add(new PeerFile(peerId, file));
                providersByHash.computeIfAbsent(file.fileId(), k -> new HashSet<>()).add(peerId);
            }
        }

        Comparator<PeerFile> displayOrder = Comparator
                .comparing((PeerFile pf) -> pf.record().fileName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(pf -> pf.record().fileName())
                .thenComparing(PeerFile::peerId);

        Comparator<PeerInfo> providerOrder = Comparator
                .comparing(PeerInfo::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PeerInfo::displayName)
                .thenComparing(PeerInfo::peerId);

        List<SearchResult> results = new ArrayList<>();

        for (Map.Entry<String, List<PeerFile>> entry : byHash.entrySet()) {
            String fileId = entry.getKey();
            List<PeerFile> allPeerFiles = entry.getValue();

            List<PeerFile> matchingNameRecords = new ArrayList<>();
            if (!normalizedQuery.isEmpty()) {
                for (PeerFile pf : allPeerFiles) {
                    if (pf.record().fileName().toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                        matchingNameRecords.add(pf);
                    }
                }
            }

            boolean matchesHash = !normalizedQuery.isEmpty() && fileId.contains(normalizedQuery);
            boolean matches = normalizedQuery.isEmpty() || matchesHash || !matchingNameRecords.isEmpty();

            if (!matches) {
                continue;
            }

            List<PeerFile> candidates;
            if (normalizedQuery.isEmpty() || matchesHash) {
                candidates = new ArrayList<>(allPeerFiles);
            } else {
                candidates = matchingNameRecords;
            }

            candidates.sort(displayOrder);
            FileRecord selectedRecord = candidates.get(0).record();

            Set<String> peerIds = providersByHash.get(fileId);
            List<PeerInfo> liveProviders = new ArrayList<>(peerIds.size());
            for (String pid : peerIds) {
                PeerInfo p = registeredPeers.get(pid);
                if (p != null) {
                    liveProviders.add(p);
                }
            }
            if (liveProviders.isEmpty()) {
                continue;
            }
            liveProviders.sort(providerOrder);

            results.add(new SearchResult(selectedRecord, liveProviders));
        }

        Comparator<SearchResult> resultOrder = Comparator
                .comparing((SearchResult r) -> r.file().fileName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(r -> r.file().fileName())
                .thenComparing(r -> r.file().fileId());

        results.sort(resultOrder);
        return results;
    }

    public int size() {
        Set<String> distinctHashes = new HashSet<>();
        for (List<FileRecord> files : peerSnapshots.values()) {
            for (FileRecord record : files) {
                distinctHashes.add(record.fileId());
            }
        }
        return distinctHashes.size();
    }
}
