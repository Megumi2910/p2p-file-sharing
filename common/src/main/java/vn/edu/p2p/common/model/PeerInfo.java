package vn.edu.p2p.common.model;

public record PeerInfo(String peerId, String displayName, String host, int port) {
    @Override
    public String toString() {
        return displayName + " (" + host + ":" + port + ")";
    }
}
