package vn.edu.p2p.tracker;

import vn.edu.p2p.common.model.PeerInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PeerRegistry {
    private final ConcurrentMap<String, PeerInfo> peers = new ConcurrentHashMap<>();

    public void register(PeerInfo peer) {
        peers.put(peer.peerId(), peer);
    }

    public void unregister(String peerId) {
        if (peerId != null) {
            peers.remove(peerId);
        }
    }

    public List<PeerInfo> listExcept(String peerId) {
        List<PeerInfo> result = new ArrayList<>();
        for (PeerInfo peer : peers.values()) {
            if (!peer.peerId().equals(peerId)) {
                result.add(peer);
            }
        }
        result.sort(Comparator.comparing(PeerInfo::displayName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public int size() {
        return peers.size();
    }
}
