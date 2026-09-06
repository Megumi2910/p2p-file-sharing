package vn.edu.p2p.tracker;

import vn.edu.p2p.common.model.PeerInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PeerRegistry {
    private final ConcurrentMap<String, PeerInfo> peers = new ConcurrentHashMap<>();

    public boolean register(PeerInfo peer) {
        if (peer == null) {
            return false;
        }
        return peers.putIfAbsent(peer.peerId(), peer) == null;
    }

    public void unregister(PeerInfo peer) {
        if (peer != null) {
            peers.remove(peer.peerId(), peer);
        }
    }

    public List<PeerInfo> listExcept(String peerId) {
        List<PeerInfo> result = new ArrayList<>();
        for (PeerInfo peer : peers.values()) {
            if (peerId == null || !peer.peerId().equals(peerId)) {
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
